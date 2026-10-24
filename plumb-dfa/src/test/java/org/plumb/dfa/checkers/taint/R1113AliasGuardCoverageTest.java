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
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1113: guard transfer across aliases must require the guard to COVER the reference.
 *
 * <p>The rule previously suppressed a finding whenever <em>some</em> alias of the receiver was
 * proven non-null. The alias map is a may-relation, so that test is a false-negative generator:
 *
 * <pre>
 *   items.add(first); items.add(second);
 *   StringBuilder other = items.get(1);   // may hold first's object OR second's
 *   if (first == null) return;            // proves nothing about second
 *   other.length();                       // real NPE, silently dropped
 * </pre>
 *
 * <p>These tests pin both directions of the fix, because "made it stricter" is only half the job:
 *
 * <ul>
 *   <li>the <b>unsound</b> shapes must be reported -- a guard covering one of several possible
 *       objects is not a guard;
 *   <li>the <b>sound</b> shapes must stay silent -- the cheap copy case ({@code b = a}), and the
 *       symmetric container round trip ({@code put(k,v); get(k)}) where the unknown key makes the
 *       possibility set wider than reality but not wider than the writer's contribution.
 * </ul>
 *
 * <p>Mutation-checked: restoring "some alias is guarded" turns the first test red while the rest
 * stay green, which is what makes the pair meaningful rather than decorative.
 */
class R1113AliasGuardCoverageTest {

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
            new org.plumb.dfa.config.TuningConfig(
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

  // ── unsound: must be reported ─────────────────────────────────────────────

  /** Two writes into one ANY_KEY cell; a guard on one of them covers only one possible object. */
  @Test
  void guardOnOneListElementDoesNotCoverAnother() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.util.*;
            class T {
              StringBuilder make(String s) { return new StringBuilder(s); }
              void bad(String input) {
                List<StringBuilder> items = new ArrayList<>();
                StringBuilder first = make(input);
                StringBuilder second = make(input);
                items.add(first);
                items.add(second);
                StringBuilder other = items.get(1);
                if (first == null) { return; }
                other.length();
              }
            }
            """,
            "ListPartialCover.java");
    assertThat(out).as("`other` may be `second`, which no guard covers").isNotEmpty();
  }

  /** Same argument through a Map whose unknown key merges two entries. */
  @Test
  void guardOnOneMapEntryDoesNotCoverAnother() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.util.*;
            class T {
              StringBuilder make(String s) { return new StringBuilder(s); }
              void bad(String key) {
                Map<String, StringBuilder> store = new HashMap<>();
                StringBuilder guarded = make(key);
                StringBuilder other = make(key);
                store.put(key, guarded);
                store.put(key, other);
                StringBuilder fetched = store.get(key);
                if (guarded == null) { return; }
                fetched.length();
              }
            }
            """,
            "MapPartialCover.java");
    assertThat(out)
        .as("two writes under the same unknown key; the guard names only one of them")
        .isNotEmpty();
  }

  // ── sound: must stay silent ────────────────────────────────────────────────

  /** A plain copy: both names denote exactly the same objects, so the guard does cover it. */
  @Test
  void aGuardedCopyStaysSilent() throws Exception {
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
    assertThat(out).as("b's points-to set is contained in a's").isEmpty();
  }

  /**
   * A symmetric container round trip: one write, one read, same unknown key. The possibility set is
   * wider than reality, but every object in it was put there by the guarded name.
   */
  @Test
  void aSymmetricContainerRoundTripStaysSilent() throws Exception {
    List<Finding> out =
        scan(
            """
            import java.util.*;
            class T {
              StringBuilder make(String s) { return new StringBuilder(s); }
              void ok(String key) {
                Map<String, StringBuilder> store = new HashMap<>();
                StringBuilder created = make(key);
                store.put(key, created);
                StringBuilder fetched = store.get(key);
                if (created == null) { return 0; }
                fetched.append("x");
              }
            }
            """,
            "SymmetricRoundTrip.java");
    assertThat(out)
        .as("the guarded name is the only writer of the cell the read observes")
        .isEmpty();
  }
}
