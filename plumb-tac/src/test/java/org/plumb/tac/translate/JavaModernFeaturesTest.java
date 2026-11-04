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
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaLanguageLevel;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The Java features that arrived after Java 8.
 *
 * <p>Plumb's Java target is Java 8 through Java 25, so every construct added between those two
 * versions has to lower to TAC the dataflow engine understands. These tests check the lowering
 * itself -- that the shape is right -- because a lowering that silently drops a value is a false
 * negative that no downstream test would notice.
 */
class JavaModernFeaturesTest {

  /**
   * Parses at the given language level.
   *
   * <p>The level matters: JavaParser refuses to parse a switch pattern at {@code JAVA_17}, so a
   * test that used the default level would silently exercise nothing.
   */
  private TacProgram translate(String src, String languageLevel) {
    JavaSourceParser parser = new JavaSourceParser(JavaLanguageLevel.resolve(languageLevel));
    ParsedSource ps = parser.parseFromString(Path.of("Test.java"), src);
    return new TacTranslator(ps).translate();
  }

  private TacProgram translate(String src) {
    return translate(src, "21");
  }

  /**
   * Finds a function by its signature prefix.
   *
   * <p>The signatures look like {@code m(int,int):int} for a method, {@code Point(int,int)} for a
   * constructor and {@code x():int} for a record's accessor. A prefix match is used rather than a
   * substring one so that {@code m(int)} does not accidentally match {@code m(int,int)}.
   */
  private static TacFunction functionNamed(TacProgram p, String signaturePrefix) {
    return p.functions().stream()
        .filter(f -> f.signature().startsWith(signaturePrefix))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no function starting with " + signaturePrefix + " in " + p.functions()));
  }

  // ─── switch expression ───────────────────────────────────────────────

  @Test
  void switchExpressionProducesABranchChainAndAssignsItsTemp() {
    TacProgram p =
        translate(
            "class T { int m(int x) { return switch (x) { case 1 -> 10; default -> 20; }; } }");
    TacFunction fn = functionNamed(p, "m(int)");

    // The switch expression is a branch chain, not a new opcode.
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.BRANCH))
        .isTrue();
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.BINOP))
        .isTrue();
    // The arms' values are MOVEd into the result temp.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.lhs1 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.INT))
        .isTrue();
  }

  @Test
  void yieldAssignsIntoTheSwitchExpressionResult() {
    // The shape that used to lose the value: a `yield` inside a switch expression.
    TacProgram p =
        translate(
            "class T { int m(int x) { return switch (x) { case 1 -> { yield 42; } default -> {"
                + " yield 7; } }; } }");
    TacFunction fn = functionNamed(p, "m(int)");

    // A yield is a MOVE into the switch expression's temp.
    long moves = fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.MOVE).count();
    assertThat(moves).isGreaterThanOrEqualTo(2);
    // The yielded constants reach the temp.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.lhs1 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.INT
                            && (Integer) c.value() == 42))
        .isTrue();
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.lhs1 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.INT
                            && (Integer) c.value() == 7))
        .isTrue();
  }

  @Test
  void switchExpressionWithoutYieldStillAssignsArmValues() {
    TacProgram p =
        translate(
            "class T { int m(int x) { return switch (x) { case 1 -> 10; default -> 20; }; } }");
    TacFunction fn = functionNamed(p, "m(int)");
    // Both arm values must reach the result.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.lhs1 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.INT
                            && (Integer) c.value() == 10))
        .isTrue();
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.lhs1 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.INT
                            && (Integer) c.value() == 20))
        .isTrue();
  }

  // ─── instanceof with a pattern ───────────────────────────────────────

  @Test
  void instanceofWithPatternEmitsATypeTestAndABinding() {
    TacProgram p =
        translate(
            "class T { int m(Object o) { if (o instanceof String s) { return s.length(); } return"
                + " 0; } }");
    TacFunction fn = functionNamed(p, "m(Object)");

    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.INSTANCEOF))
        .isTrue();
    // The binding is a MOVE into a variable named `s`.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.dest instanceof TacOperand.TacVar v
                            && "s".equals(v.name())))
        .isTrue();
    // And `s` is a declared local, so the DFA's per-variable maps recognise it.
    assertThat(fn.locals().stream().anyMatch(v -> "s".equals(v.name()))).isTrue();
  }

  @Test
  void instanceofWithoutPatternKeepsThePlainTypeTest() {
    TacProgram p = translate("class T { boolean m(Object o) { return o instanceof String; } }");
    TacFunction fn = functionNamed(p, "m(Object)");
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.INSTANCEOF))
        .isTrue();
    // No binding, because there is no pattern.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.dest instanceof TacOperand.TacVar v
                            && "s".equals(v.name())))
        .isFalse();
  }

  // ─── switch with pattern labels ──────────────────────────────────────

  @Test
  void switchWithPatternLabelEmitsATypeTest() {
    TacProgram p =
        translate(
            "class T { int m(Object o) { switch (o) { case String s -> { return s.length(); }"
                + " default -> { return 0; } } } }");
    TacFunction fn = functionNamed(p, "m(Object)");

    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.INSTANCEOF))
        .isTrue();
    // The pattern variable is bound inside the arm.
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.MOVE
                            && i.dest instanceof TacOperand.TacVar v
                            && "s".equals(v.name())))
        .isTrue();
  }

  @Test
  void switchWithNullLabelComparesAgainstNull() {
    TacProgram p =
        translate(
            "class T { int m(Object o) { switch (o) { case null -> { return 0; } default -> {"
                + " return 1; } } } }");
    TacFunction fn = functionNamed(p, "m(Object)");
    assertThat(
            fn.instructions().stream()
                .anyMatch(
                    i ->
                        i.kind == TacInstruction.Kind.BINOP
                            && i.lhs2 instanceof TacOperand.Const c
                            && c.kind() == TacOperand.Const.Kind.NULL))
        .isTrue();
  }

  // ─── records ─────────────────────────────────────────────────────────

  @Test
  void recordGetsACanonicalConstructorThatAssignsItsComponents() {
    TacProgram p = translate("record Point(int x, int y) {}");
    TacFunction ctor = functionNamed(p, "Point(int,int)");

    assertThat(ctor.params()).hasSize(2);
    // Each component is stored to its field.
    long stores =
        ctor.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.FIELDSTORE).count();
    assertThat(stores).isEqualTo(2);
    assertThat(
            ctor.instructions().stream()
                .anyMatch(i -> i.kind == TacInstruction.Kind.FIELDSTORE && "x".equals(i.fieldName)))
        .isTrue();
    assertThat(
            ctor.instructions().stream()
                .anyMatch(i -> i.kind == TacInstruction.Kind.FIELDSTORE && "y".equals(i.fieldName)))
        .isTrue();
  }

  @Test
  void recordGetsAnAccessorPerComponent() {
    TacProgram p = translate("record Point(int x, int y) {}");
    TacFunction x = functionNamed(p, "x():int");

    assertThat(x.returnType().kind()).isEqualTo(org.plumb.tac.ir.TacType.Kind.INT);
    assertThat(
            x.instructions().stream()
                .anyMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD && "x".equals(i.fieldName)))
        .isTrue();
  }

  @Test
  void recordMembersAreMarkedSynthetic() {
    // A record's accessors and canonical constructor are compiler-generated. A rule that
    // reports an unused method must not report them.
    TacProgram p = translate("record Point(int x, int y) {}");
    for (TacFunction fn : p.functions()) {
      assertThat(fn.isSynthetic())
          .as("record member %s should be synthetic", fn.signature())
          .isTrue();
    }
  }

  @Test
  void anExplicitCanonicalConstructorWins() {
    TacProgram p =
        translate("record Point(int x, int y) { Point(int x, int y) { this.x = x; this.y = y; } }");
    // Exactly one constructor, not two. The synthesized and the explicit form must land
    // on the same signature, or a call site resolves to only one of them.
    long ctors = p.functions().stream().filter(f -> f.signature().contains("(int,int)")).count();
    assertThat(ctors).isEqualTo(1);
  }

  @Test
  void anExplicitAccessorWins() {
    TacProgram p = translate("record Point(int x, int y) { int x() { return x + 1; } }");
    // The synthesized accessor must not be emitted alongside the explicit one.
    long accessors = p.functions().stream().filter(f -> f.signature().contains("x():int")).count();
    assertThat(accessors).isEqualTo(1);
  }

  @Test
  void aHandWrittenMethodIsNotSynthetic() {
    TacProgram p = translate("class T { int m() { return 1; } }");
    for (TacFunction fn : p.functions()) {
      assertThat(fn.isSynthetic()).as("%s should not be synthetic", fn.signature()).isFalse();
    }
  }

  // ─── the old behaviour must be untouched ─────────────────────────────

  @Test
  void java8CodeStillTranslatesTheSameWay() {
    TacProgram p = translate("class T { int m(int a, int b) { int c = a + b; return c; } }", "8");
    TacFunction fn = functionNamed(p, "m(int,int)");
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.BINOP))
        .isTrue();
    assertThat(fn.instructions().stream().anyMatch(i -> i.kind == TacInstruction.Kind.RETURN))
        .isTrue();
  }
}
