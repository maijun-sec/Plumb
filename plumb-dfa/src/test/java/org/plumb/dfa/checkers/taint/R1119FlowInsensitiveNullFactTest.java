package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1119: "no fact recorded" must not be read as "proven non-null".
 *
 * <p>The MOVE handler cleared the nullable mark whenever the source was absent from the map:
 *
 * <pre>
 *   if (nullableVars.containsKey(src)) put(dest, "copy of " + src);
 *   else                              remove(dest);   // ← wrong
 * </pre>
 *
 * <p>Absence means "nothing was recorded", not "known safe". Every other part of the engine states
 * this explicitly — the points-to solver's empty set, {@code AnalysisContext.aliasClassOf}'s
 * "empty means no alias information", and this same class's coverage check. This one line inverted
 * a may-fact into a must-claim, which is the direction that hides findings.
 *
 * <p>Two distinct defects fall out of it, and both are silent:
 *
 * <ol>
 *   <li><b>Direct.</b> {@code x = peek(); y = cleanSource(); } — any MOVE from a non-recorded name
 *       erases an earlier nullable mark on the destination.
 *   <li><b>Through a loop.</b> The state is a single per-function map walked in linear order with no
 *       fixpoint and no predecessor merge, so on a later round the erasure destroys a fact the back
 *       edge just re-introduced. A guard that is correct on round 1 makes the code look safe and the
 *       round-2 finding disappears.
 * </ol>
 *
 * <p>The loop case is the one that matters in practice: a {@code while (cur != null)} traversal
 * calling a factory each iteration is ordinary code, and CWE-476 on the second element is exactly
 * the kind of bug that survives a code review.
 */
class R1119FlowInsensitiveNullFactTest {

  private static final String RULE = "dfa-taint-null-deref-from-return";

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(2, 1000, 1000, 100, 4, false, false, false, "full", "cwe_family",
                "sarif", false, 0),
            base.receiverPassthroughPatterns());

    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new NullDerefFromReturnDfaChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The direct shape: a nullable value is overwritten from a name with no recorded fact.
   *
   * <p>No loop needed. This is the minimal form of the same inversion.
   */
  @Test
  void aMoveFromAnUnrecordedSourceMustNotEraseAnEarlierNullableMark() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              StringBuilder peek(String key) { return new StringBuilder(key); }
              String clean() { return "constant"; }
              int bad(String key) {
                StringBuilder x = peek(key);   // nullable: a call result
                x = clean();                   // source has no recorded nullable fact
                return x.length();             // re-reading `x` -- still may be the old value
              }
            }
            """,
            "EraseOnMove.java");
    assertThat(out)
        .as("the absence of a fact on `clean` is not proof that `x` is non-null")
        .isNotEmpty();
  }

  /**
   * The shipped-defect shape: a guarded loop, where the guard makes round 1 look safe and the
   * erasure then hides round 2.
   */
  @Test
  void aGuardedLoopDoesNotHideANullDereferenceOnLaterRounds() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              StringBuilder peek(String key) { return new StringBuilder(key); }
              int traverse(String head) {
                String cur = head;
                while (cur != null) {
                  StringBuilder x = peek(cur);   // nullable on every iteration
                  if (x == null) { break; }      // proves x non-null HERE only
                  x = peek(cur);                 // a call result, then re-bound
                  x.length();                    // real NPE on the second round
                }
                return 0;
              }
            }
            """,
            "GuardedLoop.java");
    assertThat(out)
        .as("the guard covers the path it guards, not the loop's later rounds")
        .isNotEmpty();
  }

  /**
   * Control: the same loop WITHOUT the guard must report. If this ever stops reporting, the test
   * above would be passing for the wrong reason.
   */
  @Test
  void theSameLoopWithoutTheGuardStillReports() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              StringBuilder peek(String key) { return new StringBuilder(key); }
              int traverse(String head) {
                String cur = head;
                while (cur != null) {
                  StringBuilder x = peek(cur);
                  x.length();
                }
                return 0;
              }
            }
            """,
            "UnguardedLoop.java");
    assertThat(out).as("control: no guard anywhere").isNotEmpty();
  }

  /** Control: a genuinely safe reassignment stays silent. */
  @Test
  void aFreshAllocationStaysSilent() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              StringBuilder peek(String key) { return new StringBuilder(key); }
              int ok(String key) {
                StringBuilder x = peek(key);
                if (x == null) { return 0; }
                x = new StringBuilder("safe");   // provably non-null
                return x.length();
              }
            }
            """,
            "FreshAlloc.java");
    assertThat(out).as("a fresh allocation cannot be null").isEmpty();
  }
}