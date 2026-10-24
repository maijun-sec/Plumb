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
 * R1122: four unsound suppressions found by the third independent audit.
 *
 * <p>All four are the same underlying mistake wearing different clothes — <b>stale facts read as
 * current ones</b>. Each was reproduced before the fix and mutant-proved, and all four are
 * pre-existing rather than regressions from the R1119/R1120/R1121 work.
 *
 * <ol>
 *   <li><b>Loop-exit guard leak</b> (GuardTracker). A {@code while (v != null)} condition pushes a
 *       guard, but the loop's exit edge is the BRANCH's false edge and <em>falls through with no
 *       JUMP</em>, so nothing ever pops it. {@code while (a != null) {...} a.length();} reported
 *       nothing.
 *   <li><b>Stale alias transfer</b> (NullDerefChecker). Copy-equivalence is a monotonic union-find,
 *       so {@code b = a} followed by {@code b = call()} leaves {@code b} in {@code a}'s class
 *       forever, and a guard on {@code a} silenced {@code b}. The sibling rule disagreed on
 *       identical code.
 *   <li><b>Stale points-to as proof of freshness</b> (isFreshAllocation). Points-to is a
 *       may-analysis that never retracts, so a variable that once received {@code new} still
 *       answers "fresh" after being rebound — erasing a real nullable fact.
 *   <li><b>Two sources of truth</b> (isFullyGuardedByAlias). The finding is driven by the checker's
 *       {@code nullableVars}, but the suppression consulted only the solver's null fact. An
 *       unresolvable callee leaves the solver with no fact, and absence was read as proof of
 *       safety.
 * </ol>
 *
 * <p>Each case is paired with a CONTROL that must stay silent. That pairing is the point: a fix
 * that simply stopped suppressing would pass the positive half while quietly breaking the
 * copy-equivalence coverage R1110/R1113 exist to provide.
 */
class R1122StaleFactSuppressionTest {

  private static final String FROM_RETURN = "dfa-taint-null-deref-from-return";
  private static final String AST_RULE = "dfa-taint-null-dereference";

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    return scan(source, name, new NullDerefFromReturnDfaChecker());
  }

  private List<Finding> scan(String source, String name, org.plumb.dfa.engine.DfaChecker checker)
      throws Exception {
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
            new TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());

    FindingReporter reporter =
        new DfaEngine(cfg, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (FROM_RETURN.equals(f.ruleId()) || AST_RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  // ── BUG-1: the loop condition's guard must not survive the loop ───────────

  /**
   * {@code while (a != null) { ... } a.length();} is a real NPE -- the loop may have exited
   * precisely because {@code a} was null.
   */
  @Test
  void aWhileConditionGuardDoesNotSurviveTheLoopExit() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              String peek() { return null; }
              int bad() {
                String a = peek();
                while (a != null) {
                  a.trim();
                }
                return a.length();
              }
            }
            """,
            "LoopExit.java");
    assertThat(out)
        .as("the loop may have exited BECAUSE a was null, so the guard does not hold here")
        .isNotEmpty();
  }

  /**
   * CONTROL: the same code with no loop must still report, so the test is not passing vacuously.
   */
  @Test
  void theUnguardedFormStillReports() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              String peek() { return null; }
              int bad() {
                String a = peek();
                return a.length();
              }
            }
            """,
            "NoLoop.java");
    assertThat(out).as("no guard at all").isNotEmpty();
  }

  // ── BUG-2: a rebinding retires the alias relationship a guard relied on ──

  /**
   * Copy-equivalence cannot un-union, so {@code b} keeps {@code a}'s alias class after being
   * rebound. The guard on {@code a} says nothing about the value {@code b} now holds.
   */
  @Test
  void aReboundVariableIsNotCoveredByItsFormerAliasGuard() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              String getNullValue() { return null; }
              int bad(String head) {
                String a = head;
                if (a == null) { return 0; }
                String b = a;
                b = getNullValue();
                return b.length();
              }
            }
            """,
            "StaleAlias.java",
            new NullDerefChecker());
    assertThat(out)
        .as("`b` was rebound; the guard on `a` describes a different reference")
        .isNotEmpty();
  }

  /** CONTROL: without the rebinding, `a` and `b` are one reference and the guard does cover `b`. */
  @Test
  void aPlainCopyOfTheGuardedVariableStaysSilent() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              StringBuilder make(String s) { return new StringBuilder(s); }
              void ok(String input) {
                StringBuilder a = make(input);
                StringBuilder b = a;
                if (a == null) { return; }
                b.length();
              }
            }
            """,
            "PlainCopy.java");
    assertThat(out).as("b and a denote ONE reference, so the guard on a covers b").isEmpty();
  }

  // ── BUG-3: freshness is a property of the binding, not of the may-set ───

  /**
   * Points-to never retracts, so a variable that once received {@code new} keeps that allocation in
   * its set forever. Reading the non-empty set as "provably fresh" erased a real nullable fact.
   */
  @Test
  void aStaleAllocationDoesNotEraseANullableFact() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              static class Box { String v; }
              String peek() { return null; }
              int bad(Box b) {
                String h = new String("x");
                h = b.v;
                String x = peek();
                x = h;
                return x.length();
              }
            }
            """,
            "StaleAllocation.java");
    assertThat(out)
        .as("`h` was rebound away from its allocation, so `x = h` must not erase x's null fact")
        .isNotEmpty();
  }

  /** CONTROL: a genuinely fresh allocation still licenses the subtraction MOVE. */
  @Test
  void aRealFreshAllocationStillClearsTheNullableFact() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              String peek() { return null; }
              int ok() {
                String x = peek();
                x = new String("fresh");
                return x.length();
              }
            }
            """,
            "RealFresh.java");
    assertThat(out).as("a fresh allocation is an object by construction").isEmpty();
  }

  // ── BUG-4: one method must not read two different sources of truth ──────

  /**
   * The finding is driven by the checker's {@code nullableVars}; the suppression read only the
   * solver's null fact, which an unresolvable callee never populates.
   */
  @Test
  void anUnresolvableCalleeResultIsNotCoveredByAnAliasGuard() throws Exception {
    List<Finding> out =
        scan(
            """
            interface Sink { String get(); }
            class T {
              int bad(String head, Sink sink) {
                String a = head;
                if (a == null) { return 0; }
                String b = a;
                b = sink.get();
                return b.length();
              }
            }
            """,
            "UnresolvableCallee.java");
    assertThat(out).as("the value came from outside; no guard in scope constrains it").isNotEmpty();
  }

  /** CONTROL: the same copy without the rebinding stays silent. */
  @Test
  void theUnreboundCopyStaysSilentForTheSameShape() throws Exception {
    List<Finding> out =
        scan(
            """
            interface Sink { String get(); }
            class T {
              int ok(String head, Sink sink) {
                String a = head;
                if (a == null) { return 0; }
                String b = a;
                return b.length();
              }
            }
            """,
            "UnreboundCopy.java");
    assertThat(out).as("no rebinding: a and b are one reference").isEmpty();
  }
}
