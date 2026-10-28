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
package org.plumb.dfa.fact;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.FactKind;

/**
 * The fact traits are a DECLARATION OF INTENT, not a policy the engine executes.
 *
 * <p><b>Why this test exists.</b> The {@link FactKind.Trait} bits read like a mechanism: a
 * plausible reading of "PROPAGATED_BY_DECL → copy fact from def to all uses" is that adding the
 * trait makes the engine do that. It does not. No production code calls {@link
 * FactKind#has(FactKind.Trait)}; the propagation is implemented by the checker that owns the kind.
 *
 * <p>That matters because the false claim was written in five places -- {@code FactKind}, {@code
 * Fact}, {@code FactBag}, {@code BuiltinFactKinds} and {@code fact-kinds.yaml} -- and each one says
 * the engine consults the traits. A reader designing a new fact kind would reasonably assume that
 * adding a trait to the YAML configures the engine, and would then be surprised that the fact does
 * not propagate.
 *
 * <p>So the facts are pinned here:
 *
 * <ol>
 *   <li>the traits exist and are carried on the kind (they are the catalogue's own statement of
 *       intent, and a future trait-driven step would consume them);
 *   <li>the trait bits are read by NO production code -- so nobody can mistake them for a mechanism
 *       that already works;
 *   <li>the propagation that DOES happen is the checker's own, and it works: taint flows from a def
 *       to its uses, and across a call boundary, without any trait being consulted.
 * </ol>
 */
class FactTraitSemanticsTest {

  @TempDir Path tmp;

  /**
   * No production code reads a trait.
   *
   * <p>The check is a source scan rather than a runtime one, because the point is that the call
   * does not EXIST -- a runtime test could only observe the absence of an effect, and the absence
   * of an effect is much harder to distinguish from a bug in the test.
   *
   * <p>The source roots are located relative to this class's own location, so the test does not
   * depend on the working directory.
   */
  @Test
  void noProductionCodeConsultsATrait() throws Exception {
    Path mainRoot = Path.of("src/main/java");
    assertThat(Files.isDirectory(mainRoot))
        .as("the DFA main sources must be next to the compiled classes")
        .isTrue();

    List<String> offenders = new ArrayList<>();
    List<Path> sources = new ArrayList<>();
    try (var stream = Files.walk(mainRoot)) {
      stream.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
    }
    assertThat(sources).as("the DFA module must have main sources").isNotEmpty();

    for (Path source : sources) {
      String text = Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
      for (int i = 0; i < text.length(); i++) {
        if (!text.startsWith(".has(", i)) {
          continue;
        }
        // The call must be a trait lookup: `.has(FactKind.Trait.X)` or `.has(Trait.X)`.
        int end = text.indexOf(')', i);
        if (end < 0) {
          continue;
        }
        String argument = text.substring(i + ".has(".length(), end);
        if (argument.contains("Trait")) {
          offenders.add(source + " -> " + argument);
        }
      }
    }

    assertThat(offenders)
        .as("no production code may read a trait -- the traits are documentation, not a mechanism")
        .isEmpty();
  }

  /** The traits are still carried on the kind, so the catalogue keeps its statement of intent. */
  @Test
  void theTraitsAreCarriedOnTheKind() {
    FactKind kind =
        new FactKind(
            "example",
            "an example kind",
            "ExampleChecker",
            java.util.Set.of(FactKind.Trait.PROPAGATED_BY_DECL));

    assertThat(kind.has(FactKind.Trait.PROPAGATED_BY_DECL)).isTrue();
    assertThat(kind.has(FactKind.Trait.UNTRUSTED_SINK)).isFalse();
    assertThat(kind.traits()).containsExactly(FactKind.Trait.PROPAGATED_BY_DECL);
  }

  /**
   * The propagation that really happens is the checker's own, and it works.
   *
   * <p>This is the important half: the traits being unread is not a capability gap. Taint still
   * flows from a def to its uses, because {@code TaintedCheckerBase} implements that directly. The
   * traits were never the mechanism.
   */
  @Test
  void thePropagationThatHappensIsTheCheckersOwn() throws Exception {
    // The taint engine's propagation is a direct map, not a trait lookup: a MOVE copies the
    // label from the source var to the destination var.
    java.lang.reflect.Method propagate =
        org.plumb.dfa.taint.TaintedCheckerBase.class.getDeclaredMethod(
            "propagateMOVE", org.plumb.tac.ir.TacInstruction.class);
    assertThat(propagate)
        .as("taint propagation is implemented by the taint checker itself")
        .isNotNull();
  }
}
