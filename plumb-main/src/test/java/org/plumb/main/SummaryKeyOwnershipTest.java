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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.summary.SummaryKeyOwnership;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A summary belongs to the class that DECLARES it -- not to whichever class the engine happened to
 * write first.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The engine stores one method's summary under several spellings, so that a call site finds it
 * whichever way the callee is named there. Some of those spellings name no class at all -- the bare
 * {@code helper/1} -- and some name a class only by its simple name. Such a spelling addresses
 * EVERY method of that name and arity in the program, so when two classes declare such a method
 * only one summary can sit under it.
 *
 * <p>Which one it is used to be decided by the engine's schedule: the first writer won, and the
 * schedule is not a property of the program. So the summary a call site read, and therefore the
 * findings, moved when the schedule moved -- with the engine never having got anything wrong.
 *
 * <p>The fix has two halves, and this test covers both:
 *
 * <ul>
 *   <li>a key that more than one method claims is <b>not published at all</b>, so no call site can
 *       read another method's facts (the white-box test below);
 *   <li>a call to an <b>inherited</b> method finds the summary of the class that DECLARES it,
 *       because the lookup now asks under the superclasses too (the end-to-end test below).
 * </ul>
 *
 * <p>The second half is what keeps the first one cheap. Without it, the inherited call would have
 * nothing left to find, and the fix would trade a wrong answer for a missing one.
 */
@DisplayName("a summary belongs to the class that declares it")
class SummaryKeyOwnershipTest {

  /** The rule the end-to-end fixtures reach. */
  private static final String CWE = "CWE-78";

  /** A class whose method hands its parameter back: the taint survives the call. */
  private static final String PROPAGATING =
      """
      class Base {
        String helper(String s) {
          return s;
        }
      }
      """;

  /**
   * Another class with a method of the SAME name and arity: it does not hand the parameter back.
   */
  private static final String NOT_PROPAGATING =
      """
      class Other {
        String helper(String s) {
          return "safe";
        }
      }
      """;

  /** The caller: it reaches the method it INHERITS, and the name it calls is the shared one. */
  private static final String CALLER =
      """
      class Sub extends Base {
        void go(String userInput) throws java.io.IOException {
          Runtime.getRuntime().exec(helper(userInput));
        }
      }
      """;

  /**
   * A key that two methods claim is not published.
   *
   * <p>This is the white-box half of the fix, and it is here because the end-to-end half cannot
   * distinguish "the key is not published" from "the lookup found the right summary anyway".
   */
  @Test
  @DisplayName("a bare key that two classes claim is not published to either of them")
  void aBareKeyClaimedByTwoClassesIsNotPublished(@TempDir Path dir) throws IOException {
    TacProgram merged =
        new MergedProgram(
                List.of(
                    translate(write(dir, "Base.java", PROPAGATING)),
                    translate(write(dir, "Other.java", NOT_PROPAGATING))))
            .asProgram();

    SummaryKeyOwnership ownership = SummaryKeyOwnership.of(merged);
    TacFunction declaring = functionOf(merged, "Base", "helper");
    TacFunction other = functionOf(merged, "Other", "helper");

    assertThat(
            ownership.isExclusivelyClaimedBy("helper/1", SummaryKeyOwnership.identityOf(declaring)))
        .as("the bare key names no class, so both classes claim it and neither may publish it")
        .isFalse();
    assertThat(ownership.isExclusivelyClaimedBy("helper/1", SummaryKeyOwnership.identityOf(other)))
        .as("the bare key names no class, so both classes claim it and neither may publish it")
        .isFalse();
    assertThat(
            ownership.isExclusivelyClaimedBy(
                "Base.helper/1", SummaryKeyOwnership.identityOf(declaring)))
        .as("the class-qualified key names exactly one class, so it may be published")
        .isTrue();
    assertThat(
            ownership.isExclusivelyClaimedBy(
                "Other.helper/1", SummaryKeyOwnership.identityOf(other)))
        .as("the class-qualified key names exactly one class, so it may be published")
        .isTrue();
  }

  /**
   * A method name that only ONE class uses keeps its bare key.
   *
   * <p>The gate must not take the bare key away from every method: a name that is unique in the
   * program addresses exactly one method, so publishing it is safe, and taking it away would cost a
   * finding for nothing.
   */
  @Test
  @DisplayName("a bare key that only one class claims is still published")
  void aBareKeyClaimedByOneClassIsPublished(@TempDir Path dir) throws IOException {
    TacProgram merged =
        new MergedProgram(List.of(translate(write(dir, "Base.java", PROPAGATING)))).asProgram();

    SummaryKeyOwnership ownership = SummaryKeyOwnership.of(merged);
    TacFunction declaring = functionOf(merged, "Base", "helper");

    assertThat(
            ownership.isExclusivelyClaimedBy("helper/1", SummaryKeyOwnership.identityOf(declaring)))
        .as("only one class declares a method of that name, so the bare key addresses it alone")
        .isTrue();
  }

  /**
   * A call to an inherited method finds the summary of the class that DECLARES it.
   *
   * <p>The call site names the receiver's class, but the method is declared in the superclass, so
   * the receiver's own keys name a class that does not declare it. Before the fix the lookup fell
   * through to the bare key -- the key two classes contend for -- and read whichever summary was
   * there. Now it asks under the superclass, and the taint crosses the call.
   */
  @Test
  @DisplayName("a call to an inherited method reaches the sink through the declaring class")
  void anInheritedMethodReachesTheSink(@TempDir Path dir) throws IOException {
    List<Finding> findings =
        scan(
            List.of(
                translate(write(dir, "Base.java", PROPAGATING)),
                translate(write(dir, "Other.java", NOT_PROPAGATING)),
                translate(write(dir, "Sub.java", CALLER))));

    assertThat(cwe78(findings))
        .as("the inherited method must hand the tainted parameter back, so the sink is reached")
        .hasSize(1);
  }

  /**
   * The findings do not depend on the order the files are analysed in.
   *
   * <p>This is the property the whole change is about. The order used to decide which class's
   * summary occupied the shared key, so it decided which file the findings landed in.
   */
  @Test
  @DisplayName("the findings do not depend on the order the files are analysed in")
  void theFindingsDoNotDependOnTheAnalysisOrder(@TempDir Path dir) throws IOException {
    TacProgram base = translate(write(dir, "Base.java", PROPAGATING));
    TacProgram other = translate(write(dir, "Other.java", NOT_PROPAGATING));
    TacProgram caller = translate(write(dir, "Sub.java", CALLER));

    List<String> first = identities(cwe78(scan(List.of(base, other, caller))));
    List<String> second = identities(cwe78(scan(List.of(other, base, caller))));

    assertThat(second)
        .as("the same corpus analysed in the other order must yield the same findings")
        .isEqualTo(first);
  }

  /** Write a fixture and parse it into a program. */
  private static Path write(Path dir, String name, String source) throws IOException {
    Path file = dir.resolve(name);
    Files.writeString(file, source);
    return file;
  }

  /** Parse and translate one file into a TAC program. */
  private static TacProgram translate(Path file) throws IOException {
    ParsedSource parsed = new JavaSourceParser().parse(file);
    return new TacTranslator(parsed).translate();
  }

  /** The function a class declares under a name. */
  private static TacFunction functionOf(TacProgram program, String enclosingClass, String name) {
    for (TacFunction fn : program.functions()) {
      if (!name.equals(fn.name())) continue;
      String enclosing = fn.enclosingClass();
      if (enclosing != null && enclosing.endsWith(enclosingClass)) return fn;
    }
    throw new AssertionError(
        "no function named " + name + " in class " + enclosingClass + " in " + program.functions());
  }

  /** Scan the programs and return the findings. */
  private static List<Finding> scan(List<TacProgram> programs) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new CommandInjectionChecker());

    Map<String, Path> sourcePaths = new LinkedHashMap<>();
    for (TacProgram program : programs) {
      Path source = program.sourcePath();
      if (source == null) continue;
      for (TacFunction fn : program.functions()) {
        String enclosing = fn.enclosingClass();
        if (enclosing != null && !enclosing.isEmpty()) {
          sourcePaths.putIfAbsent(enclosing, source);
        }
      }
    }

    DfaEngine engine =
        new DfaEngine(config, checkers, "global", null).withFunctionSourcePaths(sourcePaths);
    FindingReporter reporter = engine.runWithGlobalStore(programs, 0);
    return reporter.all();
  }

  /** The command-injection findings. */
  private static List<Finding> cwe78(List<Finding> findings) {
    List<Finding> out = new ArrayList<>();
    for (Finding f : findings) {
      if (CWE.equals(f.cwe())) out.add(f);
    }
    return out;
  }

  /**
   * The identity of a finding: the file, the line, the rule and the CWE.
   *
   * <p>A finding's identity is not its message: the message carries the value that was found, and
   * that value is not part of what the finding IS.
   */
  private static List<String> identities(List<Finding> findings) {
    List<String> out = new ArrayList<>();
    for (Finding f : findings) {
      out.add(f.filePath() + ":" + f.line() + " " + f.ruleId() + " " + f.cwe());
    }
    return out;
  }
}
