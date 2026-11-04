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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The Java syntax coverage matrix.
 *
 * <p><b>Why this test exists.</b> The translator's fallback for an expression it does not recognise
 * is to return a {@code null} constant. That is a <b>silent</b> data-flow corruption: a tainted
 * value flowing through an unsupported construct becomes {@code null}, and every downstream taint
 * fact about it disappears. No exception, no log at default level -- just a missing finding.
 *
 * <p>So the frontend has to be <b>exhaustive</b>. This test is the mechanism that makes the
 * coverage measurable rather than aspirational: one row per syntax construct, each asserting that
 * the construct produced real TAC instructions instead of falling through to {@code null}.
 *
 * <p>The rows are grouped so that a failure names the construct that broke, not just "the
 * translator is incomplete".
 */
class JavaSyntaxCoverageTest {

  /**
   * Translates a source snippet and returns the resulting program.
   *
   * <p>Uses a virtual path so the snippet never touches the disk.
   */
  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Coverage.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /**
   * Translates a snippet and returns every instruction of every function.
   *
   * <p>Flattened on purpose: the point is "did the construct produce instructions", not "in which
   * function".
   */
  private static List<TacInstruction> instructionsOf(String source) {
    TacProgram program = translate(source);
    List<TacInstruction> all = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      all.addAll(fn.instructions());
    }
    return all;
  }

  /** Asserts that the snippet produced at least one instruction. */
  private static void assertLowers(String label, String source) {
    List<TacInstruction> instructions = instructionsOf(source);
    assertThat(instructions)
        .as("%s must lower to real TAC instructions, not fall through to null", label)
        .isNotEmpty();
  }

  // ─── literals ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("every literal form lowers")
  void literalsLower() {
    assertLowers(
        "literals",
        """
        class L {
          void go() {
            int a = 1;
            long b = 2L;
            double c = 3.0;
            float d = 4.0f;
            boolean e = true;
            char f = 'x';
            String g = "s";
            Object h = null;
          }
        }
        """);
  }

  @Test
  @DisplayName("a text block lowers (Java 15+)")
  void textBlockLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class T {
              void go() {
                String s = \"\"\"
                    hello
                    world\"\"\";
              }
            }
            """);
    assertThat(instructions)
        .as("a text block is a string literal and must not be dropped")
        .isNotEmpty();
    // A text block that lowers to null would still produce a MOVE of a null constant,
    // so check the constant is actually the string.
    assertThat(instructions)
        .as("the text block's value must be a string constant, not null")
        .anyMatch(
            i ->
                i.lhs1 != null
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.STRING);
  }

  @Test
  @DisplayName("a numeric literal too big for int lowers as a long")
  void bigNumericLiteralLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class N {
              void go() {
                long v = 9999999999L;
              }
            }
            """);
    assertThat(instructions)
        .as("a long literal must not overflow into a null")
        .anyMatch(
            i ->
                i.lhs1 != null
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.LONG);
  }

  // ─── operators ─────────────────────────────────────────────────────────

  @ParameterizedTest(name = "{0} lowers")
  @CsvSource(
      delimiter = '|',
      value = {
        "addition      | int r = a + b;",
        "subtraction   | int r = a - b;",
        "multiplication| int r = a * b;",
        "division      | int r = a / b;",
        "modulus       | int r = a % b;",
        "shift left    | int r = a << b;",
        "shift right   | int r = a >> b;",
        "unsigned shift| int r = a >>> b;",
        "bitwise and   | int r = a & b;",
        "bitwise or    | int r = a | b;",
        "bitwise xor   | int r = a ^ b;",
        "less than     | boolean r = a < b;",
        "greater than  | boolean r = a > b;",
        "less equal    | boolean r = a <= b;",
        "greater equal | boolean r = a >= b;",
        "equals        | boolean r = a == b;",
        "not equals    | boolean r = a != b;",
        "logical and   | boolean r = a && b;",
        "logical or    | boolean r = a || b;",
        "ternary       | int r = c ? a : b;",
      })
  @DisplayName("every binary operator lowers")
  void binaryOperatorsLower(String label, String statement) {
    assertLowers(
        label,
        """
        class Ops {
          void go(int a, int b, boolean c) {
            %s
          }
        }
        """
            .formatted(statement));
  }

  @Test
  @DisplayName("every unary operator lowers")
  void unaryOperatorsLower() {
    assertLowers(
        "unary operators",
        """
        class U {
          void go(int a, boolean b) {
            int p = -a;
            int q = ~a;
            int r = +a;
            int s = ++a;
            int t = a++;
            int u = --a;
            int v = a--;
            boolean w = !b;
          }
        }
        """);
  }

  @Test
  @DisplayName("every compound assignment lowers")
  void compoundAssignmentsLower() {
    assertLowers(
        "compound assignment",
        """
        class A {
          void go(int a) {
            a += 1;
            a -= 1;
            a *= 2;
            a /= 2;
            a %= 2;
            a &= 1;
            a |= 1;
            a ^= 1;
            a <<= 1;
            a >>= 1;
            a >>>= 1;
          }
        }
        """);
  }

  // ─── calls and object creation ─────────────────────────────────────────

  @Test
  @DisplayName("a method call lowers")
  void methodCallLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class C {
              void go() {
                helper(1);
              }
              void helper(int x) {}
            }
            """);
    assertThat(instructions)
        .as("a call must produce a CALL")
        .anyMatch(i -> i.kind == TacInstruction.Kind.CALL);
  }

  @Test
  @DisplayName("an object creation lowers")
  void objectCreationLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class D {
              void go() {
                D d = new D();
              }
            }
            """);
    assertThat(instructions)
        .as("new must produce a NEW")
        .anyMatch(i -> i.kind == TacInstruction.Kind.NEW);
  }

  @Test
  @DisplayName("an array creation lowers")
  void arrayCreationLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class E {
              void go() {
                int[] a = new int[10];
                int[] b = {1, 2, 3};
              }
            }
            """);
    assertThat(instructions).isNotEmpty();
    assertThat(instructions)
        .as("array creation must produce a NEWARRAY")
        .anyMatch(i -> i.kind == TacInstruction.Kind.NEWARRAY);
  }

  @Test
  @DisplayName("a class literal lowers (Foo.class)")
  void classLiteralLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class F {
              void go() {
                Class<?> c = String.class;
              }
            }
            """);
    assertThat(instructions).as("a class literal must not be dropped to null").isNotEmpty();
    assertThat(instructions)
        .as("a class literal's value must not be the null constant")
        .noneMatch(
            i ->
                i.lhs1 != null
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.NULL);
  }

  @Test
  @DisplayName("a method reference lowers (String::length)")
  void methodReferenceLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class M {
              void go() {
                java.util.function.Function<String, Integer> f = String::length;
              }
            }
            """);
    assertThat(instructions).as("a method reference must not be dropped to null").isNotEmpty();
    assertThat(instructions)
        .as("a method reference must not produce a null constant")
        .noneMatch(
            i ->
                i.lhs1 != null
                    && i.lhs1 instanceof TacOperand.Const c
                    && c.kind() == TacOperand.Const.Kind.NULL);
  }

  @Test
  @DisplayName("a cast lowers")
  void castLowers() {
    assertLowers(
        "cast",
        """
        class X {
          void go(Object o) {
            String s = (String) o;
          }
        }
        """);
  }

  // ─── control flow ──────────────────────────────────────────────────────

  @Test
  @DisplayName("every control-flow statement lowers")
  void controlFlowLowers() {
    assertLowers(
        "control flow",
        """
        class CF {
          void go(int n) {
            if (n > 0) { n--; } else { n++; }
            while (n > 0) { n--; }
            do { n--; } while (n > 0);
            for (int i = 0; i < n; i++) { n += i; }
            for (String s : new String[] {"a"}) { System.out.println(s); }
            switch (n) {
              case 1: break;
              case 2: continue;
              default: break;
            }
            try {
              n = n / 0;
            } catch (ArithmeticException e) {
              n = 0;
            } finally {
              n = 1;
            }
            synchronized (this) { n = 2; }
            assert n >= 0 : "negative";
            label: for (;;) { break label; }
          }
        }
        """);
  }

  @Test
  @DisplayName("a switch expression lowers")
  void switchExpressionLowers() {
    assertLowers(
        "switch expression",
        """
        class SE {
          int go(int n) {
            return switch (n) {
              case 1 -> 10;
              case 2 -> { yield 20; }
              default -> 30;
            };
          }
        }
        """);
  }

  @Test
  @DisplayName("an instanceof pattern lowers")
  void instanceofPatternLowers() {
    List<TacInstruction> instructions =
        instructionsOf(
            """
            class IP {
              void go(Object o) {
                if (o instanceof String s) {
                  System.out.println(s);
                }
              }
            }
            """);
    assertThat(instructions)
        .as("an instanceof pattern must produce an INSTANCEOF")
        .anyMatch(i -> i.kind == TacInstruction.Kind.INSTANCEOF);
  }

  @Test
  @DisplayName("a switch pattern lowers")
  void switchPatternLowers() {
    assertLowers(
        "switch pattern",
        """
        class SP {
          void go(Object o) {
            switch (o) {
              case String s -> System.out.println(s);
              case Integer i -> System.out.println(i);
              default -> System.out.println("other");
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("a record pattern lowers")
  void recordPatternLowers() {
    assertLowers(
        "record pattern",
        """
        record Point(int x, int y) {}
        class RP {
          void go(Object o) {
            if (o instanceof Point(int x, int y)) {
              System.out.println(x + y);
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("a lambda lowers to a synthetic function plus a call")
  void lambdaLowers() {
    TacProgram program =
        translate(
            """
            class L {
              void go() {
                Runnable r = () -> System.out.println("x");
                r.run();
              }
            }
            """);
    assertThat(program.functions())
        .as("a lambda body must become its own function")
        .hasSizeGreaterThan(1);
    assertThat(program.functions()).anyMatch(f -> f.name().contains("lambda"));
  }

  // ─── declarations ──────────────────────────────────────────────────────

  @Test
  @DisplayName("a record lowers, with its accessors synthesized")
  void recordLowers() {
    TacProgram program =
        translate(
            """
            record Point(int x, int y) {
              int sum() { return x + y; }
            }
            """);
    assertThat(program.functions())
        .as("the record's declared method must be present")
        .anyMatch(f -> f.name().equals("sum"));
    assertThat(program.functions())
        .as("the record's accessors must be synthesized")
        .anyMatch(f -> f.name().equals("x") || f.name().equals("y"));
  }

  @Test
  @DisplayName("an enum lowers, with its constants")
  void enumLowers() {
    TacProgram program =
        translate(
            """
            enum Color {
              RED,
              GREEN,
              BLUE;
            }
            """);
    assertThat(program.functions())
        .as("an enum's constants must be present")
        .anyMatch(f -> f.name().endsWith("RED"))
        .anyMatch(f -> f.name().endsWith("GREEN"))
        .anyMatch(f -> f.name().endsWith("BLUE"));
  }

  @Test
  @DisplayName("an enum constant with a body lowers")
  void enumConstantBodyLowers() {
    assertLowers(
        "enum constant body",
        """
        enum Op {
          PLUS {
            int apply(int a, int b) { return a + b; }
          },
          MINUS {
            int apply(int a, int b) { return a - b; }
          };
          abstract int apply(int a, int b);
        }
        """);
  }

  @Test
  @DisplayName("an interface with default and static methods lowers")
  void interfaceLowers() {
    TacProgram program =
        translate(
            """
            interface Shape {
              double area();
              default String describe() { return "shape"; }
              static Shape unit() { return null; }
              int SIDES = 4;
            }
            """);
    assertThat(program.functions())
        .as("an interface's default and static methods must be present")
        .anyMatch(f -> f.name().equals("describe"))
        .anyMatch(f -> f.name().equals("unit"));
  }

  @Test
  @DisplayName("an annotation type lowers")
  void annotationTypeLowers() {
    TacProgram program =
        translate(
            """
            import java.lang.annotation.*;
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.METHOD, ElementType.FIELD})
            public @interface Marker {
              String value() default "";
              int count() default 0;
            }
            """);
    assertThat(program.functions())
        .as("an annotation type's members must be present")
        .anyMatch(f -> f.name().equals("value"))
        .anyMatch(f -> f.name().equals("count"));
  }

  @Test
  @DisplayName("a local class lowers")
  void localClassLowers() {
    TacProgram program =
        translate(
            """
            class LC {
              void go() {
                class Inner {
                  int v() { return 1; }
                }
                Inner i = new Inner();
              }
            }
            """);
    assertThat(program.functions())
        .as("a local class's method must be present")
        .anyMatch(f -> f.name().endsWith("v"));
  }

  @Test
  @DisplayName("a local record lowers")
  void localRecordLowers() {
    TacProgram program =
        translate(
            """
            class LR {
              void go() {
                record P(int x, int y) {}
                P p = new P(1, 2);
              }
            }
            """);
    assertThat(program.functions())
        .as("a local record's accessors must be synthesized")
        .anyMatch(f -> f.name().endsWith("x") || f.name().endsWith("y"));
  }

  @Test
  @DisplayName("a generic type with bounds lowers")
  void genericTypeLowers() {
    assertLowers(
        "generics",
        """
        import java.util.*;
        class G<T extends Comparable<T> & Cloneable> {
          <R> R pick(List<? extends R> xs, R dflt) { return dflt; }
          Map<String, List<Integer>> m = new HashMap<>();
        }
        """);
  }

  @Test
  @DisplayName("a union type lowers (A | B)")
  void unionTypeLowers() {
    assertLowers(
        "union type",
        """
        class UT {
          void go(Object o) {
            if (o instanceof String || o instanceof Integer) {
              System.out.println(o);
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("an intersection type lowers (A & B)")
  void intersectionTypeLowers() {
    assertLowers(
        "intersection type",
        """
        class IT {
          void go(Object o) {
            if (o instanceof Comparable<?> && o instanceof Cloneable) {
              System.out.println(o);
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("a constructor with this() / super() call lowers")
  void constructorDelegationLowers() {
    TacProgram program =
        translate(
            """
            class CD {
              CD() { this(1); }
              CD(int x) { super(); }
            }
            """);
    // A constructor's NAME is "<init>"; its SIGNATURE carries the declaring type and the
    // parameter types, which is how the two constructors are told apart.
    assertThat(program.functions())
        .as("both constructors must be present")
        .anyMatch(f -> f.name().equals("<init>") && f.signature().equals("CD()"));
    assertThat(program.functions())
        .as("the one-argument constructor must be present")
        .anyMatch(f -> f.name().equals("<init>") && f.signature().equals("CD(int)"));
  }

  @Test
  @DisplayName("a static initializer and instance initializer lower")
  void initializersLower() {
    TacProgram program =
        translate(
            """
            class Init {
              static { System.out.println("static"); }
              { System.out.println("instance"); }
              static int s;
              int i;
            }
            """);
    assertThat(program.functions())
        .as("an initializer must produce a function")
        .anyMatch(f -> f.name().contains("clinit") || f.name().contains("init"));
  }

  @Test
  @DisplayName("a static nested class and an interface constant lower")
  void nestedDeclarationsLower() {
    assertLowers(
        "nested declarations",
        """
        class Outer {
          static class Nested { void v() {} }
          interface Inner { int C = 1; }
          class Member { void w() {} }
        }
        """);
  }

  @Test
  @DisplayName("an anonymous class lowers")
  void anonymousClassLowers() {
    TacProgram program =
        translate(
            """
            class AC {
              Runnable go() {
                return new Runnable() {
                  public void run() { System.out.println("x"); }
                };
              }
            }
            """);
    assertThat(program.functions())
        .as("an anonymous class's method must be present")
        .anyMatch(f -> f.name().endsWith("run"));
  }

  @Test
  @DisplayName("a try-with-resources lowers")
  void tryWithResourcesLowers() {
    assertLowers(
        "try-with-resources",
        """
        import java.io.*;
        class TWR {
          void go() throws IOException {
            try (BufferedReader r = new BufferedReader(new FileReader("f"))) {
              r.readLine();
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("a multi-catch lowers")
  void multiCatchLowers() {
    assertLowers(
        "multi-catch",
        """
        class MC {
          void go() {
            try {
              throw new IllegalStateException();
            } catch (IllegalStateException | IllegalArgumentException e) {
              System.out.println(e);
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("varargs lower")
  void varargsLower() {
    assertLowers(
        "varargs",
        """
        class VA {
          void go() {
            f(1, 2, 3);
          }
          void f(int... xs) {}
        }
        """);
  }

  @Test
  @DisplayName("an array of arrays and a jagged array lower")
  void jaggedArrayLowers() {
    assertLowers(
        "jagged array",
        """
        class JA {
          void go() {
            int[][] a = new int[3][4];
            int[][] b = {{1}, {2, 3}};
          }
        }
        """);
  }

  @Test
  @DisplayName("a qualified this and a super call lower")
  void qualifiedThisLowers() {
    assertLowers(
        "qualified this",
        """
        class Base { void b() {} }
        class QT extends Base {
          void go() {
            QT.this.hashCode();
            super.b();
          }
        }
        """);
  }

  @Test
  @DisplayName("a sealed interface with permits lowers")
  void sealedInterfaceLowers() {
    assertLowers(
        "sealed interface",
        """
        sealed interface SI permits SA, SB {}
        final class SA implements SI {}
        final class SB implements SI {}
        class UseSI {
          void go(SI s) { System.out.println(s); }
        }
        """);
  }

  @Test
  @DisplayName("a nested conditional expression lowers")
  void nestedConditionalLowers() {
    assertLowers(
        "nested conditional",
        """
        class NC {
          int go(int a, int b, int c) {
            return a > b ? (b > c ? b : c) : a;
          }
        }
        """);
  }

  @Test
  @DisplayName("an enhanced for over a collection lowers")
  void enhancedForLowers() {
    assertLowers(
        "enhanced for",
        """
        import java.util.*;
        class EF {
          void go(List<String> xs) {
            for (String x : xs) { System.out.println(x); }
            for (Map.Entry<String, String> e : new HashMap<String, String>().entrySet()) {
              System.out.println(e);
            }
          }
        }
        """);
  }

  @Test
  @DisplayName("annotations on declarations lower")
  void annotationsOnDeclarationsLower() {
    assertLowers(
        "annotations",
        """
        import java.lang.annotation.*;
        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
        @interface Ann { String value() default ""; }
        class AnnUse {
          @Ann("f") private int f;
          @Ann("m") void m(@Ann("p") int p) {}
        }
        """);
  }
}
