/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.LogInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1103: the {@code log.sinks} family had no consumer, and a field-held logger had no type.
 *
 * <h2>Layer 1 — the missing checker</h2>
 *
 * <p>{@code log.sinks} (CWE-117) was configured in {@code sink-families.yaml} with no rule bound to
 * it, exactly like the {@code deserialization.sinks} gap closed in R1099. {@link
 * LogInjectionChecker} now consumes it, and the family-resolution test pins that half.
 *
 * <h2>Layer 2 — the receiver type that made it unmatchable</h2>
 *
 * <p>Even with the checker registered, a logger held in a <b>static field</b> produced no finding:
 *
 * <pre>
 *   private static final Logger LOG = Logger.getLogger(...);
 *   LOG.info("agent: " + req.getHeader("User-Agent"));
 * </pre>
 *
 * <p>lowered to the callee signature {@code LOG.info/1} — the bare variable name, no type — while a
 * logger in a local variable lowered to {@code java.util.logging.Logger.info/1}. Every pattern in
 * {@code log.sinks} (and in every other FQN-anchored family) is written against the latter, so the
 * static-field form matched nothing. This is not logging-specific: it affects every object reached
 * through a field, and in real code most loggers and most connection holders are fields.
 *
 * <p>The declared type is read syntactically from the enclosing type's field declarations rather
 * than through JavaParser's resolver. Both resolver entry points fail on this shape — {@code
 * NameExpr.resolve()} because the reference's ancestor chain contains the {@code
 * VariableDeclarationExpr} that declared it, {@code calculateResolvedType()} with {@code "not
 * inserted in a CompilationUnit"} — and neither failure means the parent chain is broken. An
 * earlier round concluded the parser and translator shared disjoint trees on the strength of that
 * message; that conclusion was wrong, and the diagnostic test {@code
 * R1104AstParentChainDiagnosisTest} exists to keep it from being repeated.
 *
 * <h2>What is proved here</h2>
 *
 * <ul>
 *   <li>the family resolves, including the {@code log(Level, msg)} arg-index special case
 *   <li>both a local and a field-held logger keep a usable FQN in the callee signature
 *   <li>the taint side was never the problem — the same source reaching a SQL sink always worked
 *   <li>the full Log4Shell shape now reports CWE-117
 * </ul>
 */
class R1103LogInjectionDiagnosisTest {

  private static final String STATIC_FIELD_LOGGER =
      """
      import java.util.logging.Logger;
      class L8 {
        private static final Logger LOG = Logger.getLogger(L8.class.getName());
        void logHeader(RequestLike req) {
          LOG.info("agent: " + req.getHeader("User-Agent"));
        }
      }
      interface RequestLike { String getHeader(String n); }
      """;

  private static final String LOCAL_LOGGER =
      """
      import java.util.logging.Logger;
      class L9 {
        void logHeader(RequestLike req) {
          Logger log = Logger.getLogger(L9.class.getName());
          log.info("agent: " + req.getHeader("User-Agent"));
        }
      }
      interface RequestLike { String getHeader(String n); }
      """;

  /** Control: same source shape, a sink family that is known to work. */
  private static final String SAME_SOURCE_TO_SQL =
      """
      import java.sql.Statement;
      import java.sql.SQLException;
      interface RequestLike { String getHeader(String n); }
      class L7 {
        void toSql(RequestLike req, Statement st) throws SQLException {
          st.executeQuery("SELECT * FROM t WHERE ua = '" + req.getHeader("User-Agent") + "'");
        }
      }
      """;

  @TempDir Path tmp;

  private TacProgram tac(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new TacTranslator(ps).translate();
  }

  private FindingReporter run(String source, String name, DfaChecker... checkers) throws Exception {
    TacProgram program = tac(source, name);
    EngineConfig base = ConfigLoader.loadFromClasspath();
    return new DfaEngine(base, List.of(checkers), "global", null)
        .runWithGlobalStore(List.of(program), 0);
  }

  private List<String> callSigs(TacProgram program) {
    List<String> out = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL) out.add(ins.calleeSig);
      }
    }
    return out;
  }

  @Test
  void theLogSinksFamilyResolvesLoggerCalls() {
    SinkFamily log =
        ConfigLoader.loadFromClasspath().sinkFamilies().byName("log.sinks").orElseThrow();
    assertThat(log.cwe()).isEqualTo("CWE-117");
    assertThat(log.argIndexFor("java.util.logging.Logger.info/2"))
        .as("LOG.info(String, Throwable) -- the message is arg 0")
        .isZero();
    assertThat(log.argIndexFor("java.util.logging.Logger.log/3"))
        .as("LOG.log(Level, String, Throwable) -- the message is arg 1")
        .isEqualTo(1);
    assertThat(log.matches("java.util.logging.Logger.info/1")).isTrue();
  }

  @Test
  void aLocalLoggerReceiverKeepsItsFqN() throws Exception {
    assertThat(callSigs(tac(LOCAL_LOGGER, "L9.java")))
        .as("a logger in a local variable must keep its declared type in the callee sig")
        .contains("java.util.logging.Logger.info/1");
  }

  @Test
  void aStaticFieldLoggerKeepsItsDeclaredType() throws Exception {
    List<String> sigs = callSigs(tac(STATIC_FIELD_LOGGER, "L8.java"));
    assertThat(sigs)
        .as("a logger held in a static field must keep its declared type in the callee sig")
        .contains("java.util.logging.Logger.info/1")
        .doesNotContain("LOG.info/1");
  }

  @Test
  void theTaintSideWorksSoThisIsNotATaintGap() throws Exception {
    FindingReporter reporter =
        run(
            SAME_SOURCE_TO_SQL,
            "L7.java",
            new LogInjectionChecker(),
            new org.plumb.dfa.checkers.taint.SqlInjectionChecker());
    assertThat(reporter.all())
        .as("getHeader on a local interface must resolve as a taint source")
        .anyMatch(f -> "dfa-taint-sql-injection".equals(f.ruleId()));
  }

  @Test
  void aTaintedHeaderLoggedThroughAStaticFieldIsReported() throws Exception {
    FindingReporter reporter = run(STATIC_FIELD_LOGGER, "L8b.java", new LogInjectionChecker());
    List<Finding> logFindings = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-log-injection".equals(f.ruleId())) logFindings.add(f);
    }
    assertThat(logFindings)
        .as(
            "the Log4Shell shape: a request header concatenated into a message written through a"
                + " logger held in a static field")
        .hasSize(1);
    assertThat(logFindings.get(0).cwe()).isEqualTo("CWE-117");
    assertThat(logFindings.get(0).line()).isEqualTo(5);
  }
}
