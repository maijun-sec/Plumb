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
package org.plumb.tac.frontend.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * The TAC's variable identity, and what it means for the Kotlin frontend.
 *
 * <p><b>The rule.</b> A {@link TacOperand.TacVar}'s identity is its <i>name</i> and its
 * <i>temp flag</i> -- deliberately not its type. A frontend may learn a variable's type
 * after it first saw the variable (unresolved {@code UNKNOWN} becomes a resolved class; a
 * platform type becomes nullable; a smart cast narrows a nullable type), and each
 * refinement must produce the <i>same</i> storage location. If it did not, the taint
 * recorded under the old key would not be found under the new one, and the failure would
 * be invisible: no exception, just a missing finding.
 */
class KotlinTacVarIdentityTest {

  /** The shared session. */
  private static KotlinTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new KotlinTestSupport();
  }

  @AfterAll
  static void tearDown() {
    // The session is a JVM-wide singleton and is released by a shutdown hook.
    if (support != null) {
      support.cleanUp();
    }
  }

  @Test
  void aVariableDeclaredUnknownAndLaterRefinedIsTheSameMapKey() {
    // The frontend declares a variable, and a refinement produces a second TacVar with
    // the same name and the same temp flag but a better type. Both must hash and compare
    // equal, so a fact recorded under one is found under the other.
    TacOperand.TacVar unresolved = new TacOperand.TacVar("x", TacType.UNKNOWN, false);
    TacOperand.TacVar refined = new TacOperand.TacVar("x", TacType.STRING.nonNull(), false);

    assertThat(refined).isEqualTo(unresolved);
    assertThat(refined.hashCode()).isEqualTo(unresolved.hashCode());

    Map<TacOperand.TacVar, String> facts = new HashMap<>();
    facts.put(unresolved, "tainted");
    assertThat(facts).containsKey(refined);
    assertThat(facts.get(refined)).isEqualTo("tainted");
  }

  @Test
  void aRefinementToANonNullTypeKeepsTheSameIdentity() {
    TacOperand.TacVar nullable = new TacOperand.TacVar("s", TacType.STRING, false);
    TacOperand.TacVar nonNull = new TacOperand.TacVar("s", TacType.STRING.nonNull(), false);

    assertThat(nonNull).isEqualTo(nullable);
    assertThat(nonNull.hashCode()).isEqualTo(nullable.hashCode());

    Map<TacOperand.TacVar, String> facts = new HashMap<>();
    facts.put(nullable, "tainted");
    assertThat(facts.get(nonNull)).isEqualTo("tainted");
  }

  @Test
  void aTempAndALocalWithTheSameNameAreDifferentVariables() {
    TacOperand.TacVar local = new TacOperand.TacVar("x", TacType.STRING, false);
    TacOperand.TacVar temp = new TacOperand.TacVar("x", TacType.STRING, true);

    assertThat(temp).isNotEqualTo(local);
    assertThat(temp.hashCode()).isNotEqualTo(local.hashCode());
  }

  @Test
  void aLocalsIdentitySurvivesTheFrontendsTypeRefinement() {
    // End to end: the frontend resolves a local's type, and the variable it declares is
    // the same key in every map the DFA keeps.
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun makeName(): Int {",
                "    val local: String = \"x\"",
                "    return local.length",
                "}"));

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar declared =
        fn.locals().stream()
            .filter(v -> "local".equals(v.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("local was not declared: " + fn.locals()));

    // The declared local has a resolved, non-null type -- not UNKNOWN.
    assertThat(declared.type()).isNotEqualTo(TacType.UNKNOWN);
    assertThat(declared.type().isNullable()).isFalse();

    // A second TacVar with the same name and the refined type is the same key.
    TacOperand.TacVar sameKey = new TacOperand.TacVar("local", declared.type(), false);
    assertThat(sameKey).isEqualTo(declared);

    Map<TacOperand.TacVar, String> facts = new HashMap<>();
    facts.put(declared, "value");
    assertThat(facts.get(sameKey)).isEqualTo("value");
  }

  @Test
  void aVariablesUsedInInstructionsIsTheSameVariableAsTheOneDeclared() {
    TacFrontend.ParseResult result =
        support.parse(
            String.join(
                "\n",
                "package demo",
                "",
                "fun makeName(): Int {",
                "    val local: String = \"x\"",
                "    return local.length",
                "}"));

    TacFunction fn = result.functions().get(0);
    TacOperand.TacVar declared =
        fn.locals().stream()
            .filter(v -> "local".equals(v.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("local was not declared: " + fn.locals()));

    // The MOVE that initialises the local must target that same variable.
    boolean seenMove =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.MOVE)
            .anyMatch(
                i ->
                    i.dest instanceof TacOperand.TacVar v
                        && "local".equals(v.name())
                        && v.equals(declared));
    assertThat(seenMove)
        .as("the MOVE's destination must be the same variable the function declares")
        .isTrue();
  }
}
