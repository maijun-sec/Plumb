package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.NullDerefFromReturnDfaChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1113: the rule must fire on a LOCAL container, which is what every real fixture uses.
 *
 * <p>Found by running the CLI rather than the unit tests: on a single-file scan the rule produced
 * nothing at all, while the same fixture scanned as a merged directory produced findings. The unit
 * tests never caught it because they exercise {@code runWithGlobalStore} over a one-element program
 * list, which is not the same shape as the per-file CLI path.
 *
 * <p>Kept as a permanent gate because the failure shape is the dangerous one: a security rule that
 * is registered, compiles, appears in a builder list, and silently reports nothing on the most
 * common code shape there is.
 */
class R1113LocalContainerDetectionTest {

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
            org.plumb.dfa.fact.BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new org.plumb.dfa.config.TuningConfig(
                2, 1000, 1000, 100, 4, false, false, false, "full", "cwe_family", "sarif", false, 0),
            base.receiverPassthroughPatterns());
    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new NullDerefFromReturnDfaChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-null-deref-from-return".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /** A list element fetched under an index the guard does not cover. */
  @Test
  void anUnguardedListElementIsReported() throws Exception {
    String src =
        """
        import java.util.*;
        class T {
          StringBuilder make(String s) { return new StringBuilder(s); }
          void bad(String input) {
            List<StringBuilder> items = new ArrayList<>();
            StringBuilder first = make(input);
            StringBuilder second = new StringBuilder("second");
            items.add(first);
            items.add(second);
            StringBuilder other = items.get(1);
            if (first == null) {
              return;
            }
            other.length();
          }
        }
        """;
    assertThat(scan(src, "LocalContainer.java"))
        .as(
            "the guard is on position 0 and the dereference is on position 1, over a container"
                + " allocated in this method")
        .isNotEmpty();
  }

  /**
   * The same shape with a map instead of a list, so a future regression in either accessor is
   * visible separately.
   */
  @Test
  void anUnguardedMapEntryIsReported() throws Exception {
    String src =
        """
        import java.util.*;
        class T {
          StringBuilder make(String s) { return new StringBuilder(s); }
          void bad(String input) {
            Map<String, StringBuilder> store = new HashMap<>();
            StringBuilder guarded = make(input);
            StringBuilder other = make(input);
            store.put("a", guarded);
            store.put("b", other);
            StringBuilder fetched = store.get("b");
            if (guarded == null) {
              return;
            }
            fetched.length();
          }
        }
        """;
    assertThat(scan(src, "LocalMap.java"))
        .as("the guard is on key \"a\" and the dereference is on key \"b\"")
        .isNotEmpty();
  }
}