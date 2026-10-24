package org.plumb.dfa.checkers.boundary;

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
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1099 regression gate: {@code Math.min} / {@code Math.max} narrow the range domain.
 *
 * <p>{@code int c = Math.max(0, Math.min(index, 2)); arr[c];} is the canonical bounds-safe idiom
 * and is semantically identical to writing the guard out by hand. Before this, the clamp call was
 * treated as an ordinary call with no summary, so {@code c} kept an unknown range and the
 * array-bounds checker reported {@code out-of-bounds: (c < 0)} — a false positive on code that is
 * correct.
 *
 * <p>The subtle part: a clamp bounds the result on ONE side even when the other argument is
 * entirely unbounded. {@code min(x, 2)} is at most 2 no matter what {@code x} is. A plain range
 * intersection cannot express that (it treats "unknown" as "no constraint" and returns the other
 * side unchanged), which is why this needs dedicated handling rather than {@code meet()}.
 */
class R1099MathClampRangeTest {

  private static final String CLAMPED =
      """
      class Rcv {
        String go(int index) {
          String[] arr = new String[3];
          int clamped = Math.max(0, Math.min(index, 2));
          return arr[clamped];
        }
      }
      """;

  private static final String GUARD_EQUIVALENT =
      """
      class Rcv2 {
        String go(int index) {
          String[] arr = new String[3];
          int c = index;
          if (c < 0) { c = 0; }
          if (c > 2) { c = 2; }
          return arr[c];
        }
      }
      """;

  private static final String UNCLAMPED =
      """
      class Rcv3 {
        String go(int index) {
          String[] arr = new String[3];
          return arr[index];
        }
      }
      """;

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(new ArrayBoundsConstraintChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-boundary-array-out-of-bounds".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void clampSuppressesTheBoundsFindingJustLikeTheEquivalentGuard() throws Exception {
    assertThat(scan(CLAMPED, "Rcv.java"))
        .as("Math.max(0, Math.min(index, 2)) must narrow the index to [0,2]")
        .isEmpty();
  }

  @Test
  void theHandWrittenGuardIsStillSilent() throws Exception {
    assertThat(scan(GUARD_EQUIVALENT, "Rcv2.java")).isEmpty();
  }

  @Test
  void anUnclampedIndexStillFires() throws Exception {
    assertThat(scan(UNCLAMPED, "Rcv3.java"))
        .as("the clamp fix must not silence genuinely unsafe indexing")
        .isNotEmpty();
  }
}
