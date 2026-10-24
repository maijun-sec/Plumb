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
 * R1114: the null direction of the heap, which the points-to fact domain could not previously hold.
 *
 * <p>The solver's five stores all answered "which objects". {@code x.f = null} therefore recorded
 * nothing, and the later load of {@code x.f} returned an empty set -- which by contract means
 * "unknown", not "null". Every consumer was right to ignore it and none could use it.
 *
 * <p>Nullness is now a separate channel ({@code nullFields}, {@code nullArrayCells}, {@code
 * nullContainerCells}, {@code nullReturns}, {@code nullVars}), deliberately NOT an {@code Obj}: one
 * shared null sentinel would make every null in the program compare equal, so it would leak into
 * {@code mayAlias} and into the guard-coverage test, where "the guard covers this null" is exactly
 * the false negative R1113 removed.
 *
 * <p>The tests pin all three states, because the third is the one that must not regress:
 * <b>definitely null</b> (report), <b>guarded</b> (silent), <b>unknown</b> (silent -- inventing a
 * finding here would flood the corpus).
 */
class R1114NullFactDomainTest {

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
        new DfaEngine(cfg, List.of(new NullDerefFromReturnDfaChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /** The category-03 case that motivated all of this: an explicitly-null field, dereferenced. */
  @Test
  void aFieldAssignedNullAndDereferencedIsReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class Node { StringBuilder payload = new StringBuilder("x"); }
            class Root { Node node; }
            class T {
              public int bad() {
                Root right = new Root();
                right.node = null;
                return right.node.payload.length();
              }
            }
            """,
            "NullField.java");
    assertThat(out).as("`right.node` is explicitly null and never checked").isNotEmpty();
  }

  /** The control the whole design turns on: an unmodelled field must stay UNKNOWN, not null. */
  @Test
  void aFieldNeverAssignedStaysUnknownAndIsNotReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class Node { StringBuilder payload = new StringBuilder("x"); }
            class Root { Node node; }
            class T {
              public int ok(Root r) {
                // `r.node` was never assigned in this program, so the solver knows NOTHING about
                // it. Reporting here would mean treating "no fact" as "null", which would turn
                // every unmodelled field read in the corpus into a CWE-476.
                if (r.node == null) { return 0; }
                return r.node.payload.length();
              }
            }
            """,
            "UnknownField.java");
    assertThat(out).as("guarded, and the solver never proved it null either").isEmpty();
  }

  /** A guard on the loaded temp must silence it -- the guard covers nullness as well as objects. */
  @Test
  void aGuardedNullFieldStaysSilent() throws Exception {
    List<Finding> out =
        scan(
            """
            class Node { StringBuilder payload = new StringBuilder("x"); }
            class Root { Node node; }
            class T {
              public int ok() {
                Root right = new Root();
                right.node = null;
                Node n = right.node;
                if (n == null) { return 0; }
                return n.payload.length();
              }
            }
            """,
            "GuardedNullField.java");
    assertThat(out).as("the guard proves the reference non-null").isEmpty();
  }

  /** A local assigned null then dereferenced: the plain MOVE shape. */
  @Test
  void aLocalAssignedNullAndDereferencedIsReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public int bad() {
                StringBuilder b = null;
                return b.length();
              }
            }
            """,
            "NullLocal.java");
    assertThat(out).isNotEmpty();
  }

  /** A container cell explicitly filled with null. */
  @Test
  void aMapEntryAssignedNullIsReported() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.util.*;
            class T {
              public int bad() {
                Map<String, StringBuilder> store = new HashMap<>();
                store.put("a", null);
                StringBuilder got = store.get("a");
                return got.length();
              }
            }
            """,
            "NullMapEntry.java");
    assertThat(out).as("`store.get(\"a\")` may return the null that was stored").isNotEmpty();
  }

  /**
   * The shape that reaches the guard-coverage test's null handling.
   *
   * <p>Every other case here is short-circuited before {@code isFullyGuardedByAlias}: either the
   * receiver was never marked nullable, or a direct guard on it answered first. This one is built
   * so the only path to a suppression runs through the coverage test -- the receiver's points-to
   * set is empty <em>because</em> the solver knows it is null, the alias class is non-empty (a
   * plain copy), and the guard names the alias rather than the receiver.
   *
   * <p>Added after a mutation proved the guard inert: disabling the null check entirely left all
   * five tests green, because no test reached it. A test that cannot fail is not a gate, and the
   * mutant is what said so.
   */
  @Test
  void aGuardOnACopyDoesNotSilenceAKnownNullAlias() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public int bad() {
                StringBuilder a = null;
                StringBuilder b = a;
                if (b == null) { return 0; }
                // `a` was assigned null and the guard is on `b`. For a syntactic copy that guard
                // would normally be enough, but the solver holds a null fact for `a` -- it saw the
                // assignment. Reporting `a` is the conservative reading; suppressing it is the
                // false negative this branch exists to prevent.
                return a.length();
              }
            }
            """,
            "NullAliasWithGuard.java");
    assertThat(out)
        .as("the solver knows `a` is null; a guard on its copy does not erase that")
        .isNotEmpty();
  }
}
