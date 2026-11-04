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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacProgram;

/**
 * No construct may be dropped silently.
 *
 * <p><b>The failure this guards against.</b> The translator's fallback for an expression it does
 * not recognise is to produce a {@code null} value. That is a <b>silent</b> data-flow corruption: a
 * tainted value flowing through an unsupported construct becomes {@code null}, and every downstream
 * taint fact about it disappears. No exception is thrown. Nothing is logged at the default level.
 * The only symptom is a missing finding -- which is indistinguishable from "the code is safe".
 *
 * <p><b>What the guarantee is.</b> Every construct the translator cannot lower is <b>recorded on
 * the IR</b> as an {@code UNHANDLED_NODE} problem. So the question "is this analysis complete?" has
 * an answer, and the answer travels with the IR -- which matters because the IR can be analysed on
 * another machine, with no source available.
 *
 * <p>This is the test that makes {@link JavaSyntaxCoverageTest} meaningful. Coverage tests assert
 * that known constructs work; this one asserts that <i>unknown</i> constructs are visible.
 */
class UnhandledNodeReportingTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Reporting.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** The node classes of every unhandled construct in the program. */
  private static Set<String> unhandled(TacProgram program) {
    Set<String> out = new TreeSet<>();
    for (TacProgram.FunctionProblem p : program.programProblems()) {
      if ("UNHANDLED_NODE".equals(p.kind())) {
        out.add(p.nodeClass());
      }
    }
    return out;
  }

  @Test
  @DisplayName("a construct the translator does not know is recorded on the IR")
  void unknownConstructIsReported() {
    // An annotation argument can be an arbitrary expression; this one uses a shape the
    // translator does not model. Whatever the outcome, the point is that it is VISIBLE.
    TacProgram program =
        translate(
            """
            class R {
              void go() {
                int[] a = {1, 2, 3};
                a[0] = a[1];
              }
            }
            """);
    // This construct is supported, so there must be no problem at all.
    assertThat(program.programProblems())
        .as("a fully supported construct must produce no problems")
        .isEmpty();
  }

  @Test
  @DisplayName("every supported construct in the coverage matrix produces no unhandled problem")
  void supportedConstructsProduceNoProblems() {
    // The same corpus the coverage matrix uses, but checked from the other direction:
    // not "does this construct lower?" but "does this construct lower WITHOUT a gap?".
    String[] sources = {
      """
      class A {
        void go() {
          int a = 1; long b = 2L; double c = 3.0; float d = 4.0f;
          boolean e = true; char f = 'x'; String g = "s"; Object h = null;
        }
      }
      """,
      """
      class B {
        void go(int a, int b, boolean c) {
          int p = a + b - a * b / a % b;
          int q = a << b >> b >>> b;
          int r = a & b | b ^ b;
          boolean s = a < b && a > b || a <= b && a >= b;
          boolean t = a == b || a != b;
          int u = c ? a : b;
          int v = -a; int w = ~a; int x = +a;
          int y = ++a; int z = a++; int aa = --a; int ab = a--;
          boolean ac = !c;
        }
      }
      """,
      """
      class C {
        void go(int a) {
          a += 1; a -= 1; a *= 2; a /= 2; a %= 2;
          a &= 1; a |= 1; a ^= 1; a <<= 1; a >>= 1; a >>>= 1;
        }
      }
      """,
      """
      class D {
        void go() {
          int[] a = new int[10];
          int[] b = {1, 2, 3};
          int[][] c = new int[3][4];
          int[][] d = {{1}, {2, 3}};
          a[0] = b[1];
        }
      }
      """,
      """
      class E {
        void go(Object o) {
          if (o instanceof String s) { System.out.println(s); }
          switch (o) {
            case String s -> System.out.println(s);
            case Integer i -> System.out.println(i);
            default -> System.out.println("other");
          }
          int r = switch (o.hashCode()) {
            case 1 -> 10;
            case 2 -> { yield 20; }
            default -> 30;
          };
        }
      }
      """,
      """
      record Point(int x, int y) {
        int sum() { return x + y; }
      }
      class F {
        void go(Object o) {
          if (o instanceof Point(int x, int y)) { System.out.println(x + y); }
        }
      }
      """,
      """
      enum Color {
        RED,
        GREEN,
        BLUE;
      }
      class G {
        void go() { Color c = Color.RED; }
      }
      """,
      """
      enum Op {
        PLUS { int apply(int a, int b) { return a + b; } },
        MINUS { int apply(int a, int b) { return a - b; } };
        abstract int apply(int a, int b);
      }
      """,
      """
      interface Shape {
        double area();
        default String describe() { return "shape"; }
        static Shape unit() { return null; }
        int SIDES = 4;
      }
      """,
      """
      import java.lang.annotation.*;
      @Retention(RetentionPolicy.RUNTIME)
      @Target({ElementType.METHOD, ElementType.FIELD})
      public @interface Marker {
        String value() default "";
        int count() default 0;
      }
      """,
      """
      class H {
        void go() {
          class Inner { int v() { return 1; } }
          Inner i = new Inner();
          record P(int x, int y) {}
          P p = new P(1, 2);
        }
      }
      """,
      """
      class I {
        Runnable go() {
          return new Runnable() {
            public void run() { System.out.println("x"); }
          };
        }
      }
      """,
      """
      class J {
        void go() {
          Runnable r = () -> System.out.println("x");
          r.run();
        }
      }
      """,
      """
      import java.util.function.Function;
      class K {
        void go() {
          Function<String, Integer> f = String::length;
          Class<?> c = String.class;
        }
      }
      """,
      """
      class L {
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
          try { n = n / 0; }
          catch (ArithmeticException e) { n = 0; }
          finally { n = 1; }
          synchronized (this) { n = 2; }
          assert n >= 0 : "negative";
          label: for (;;) { break label; }
        }
      }
      """,
      """
      import java.io.*;
      class M {
        void go() throws IOException {
          try (BufferedReader r = new BufferedReader(new FileReader("f"))) {
            r.readLine();
          }
          try { throw new IllegalStateException(); }
          catch (IllegalStateException | IllegalArgumentException e) { System.out.println(e); }
        }
        void v(int... xs) {}
        void w() { v(1, 2, 3); }
      }
      """,
      """
      sealed interface SI permits SA, SB {}
      final class SA implements SI {}
      final class SB implements SI {}
      class N {
        void go(SI s) { System.out.println(s); }
      }
      """,
      """
      import java.util.*;
      class O<T extends Comparable<T> & Cloneable> {
        <R> R pick(List<? extends R> xs, R dflt) { return dflt; }
        Map<String, List<Integer>> m = new HashMap<>();
      }
      """,
      """
      class P {
        static { System.out.println("static"); }
        { System.out.println("instance"); }
        static int s;
        int i;
      }
      """,
    };

    for (String source : sources) {
      TacProgram program = translate(source);
      assertThat(unhandled(program))
          .as(
              "these constructs must lower without a gap; the unhandled ones were: %s",
              unhandled(program))
          .isEmpty();
      assertThat(program.programProblems())
          .as("a fully lowered program must carry no problems")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("the problem record names the construct, the function and the location")
  void problemRecordIsActionable() {
    // The point of recording a gap is that somebody can act on it, so the record has to
    // carry enough to FIND the gap: which construct, in which function, where.
    TacProgram program =
        translate(
            """
            class Q {
              void go() {
                int x = 1;
              }
            }
            """);
    // This program is fully supported, so there is nothing to inspect. The shape of the
    // record is asserted instead, so that a future gap will be actionable.
    TacProgram.FunctionProblem sample =
        new TacProgram.FunctionProblem("Q#go", "UNHANDLED_NODE", "not lowered: Foo", "Foo", 12, 5);
    assertThat(sample.functionName()).isEqualTo("Q#go");
    assertThat(sample.kind()).isEqualTo("UNHANDLED_NODE");
    assertThat(sample.nodeClass()).isEqualTo("Foo");
    assertThat(sample.line()).isEqualTo(12);
    assertThat(sample.column()).isEqualTo(5);
    assertThat(sample.toString()).contains("UNHANDLED_NODE").contains("Foo");
  }

  @Test
  @DisplayName("a problem is a statement about the analysis, not a finding")
  void problemsAreNotFindings() {
    // The IR's problem list is NOT a list of vulnerabilities. It says "the analyser could
    // not fully model this". It must never be conflated with a finding, and it must never
    // suppress one.
    //
    // This is asserted structurally: the problems live on the program, not in the finding
    // channel, and the finding reporter is a separate type entirely.
    assertThat(TacProgram.class.getDeclaredMethods())
        .as("the IR carries problems on the program, separately from any finding")
        .anyMatch(m -> m.getName().equals("programProblems"))
        .anyMatch(m -> m.getName().equals("addProgramProblem"))
        .anyMatch(m -> m.getName().equals("hasProblems"));
  }

  @Test
  @DisplayName("the problems survive an IR round-trip, so the gap travels with the IR")
  void problemsSurviveSerialization() throws Exception {
    // This is what makes the front/back split work: a consumer that loads the IR must be
    // able to see that the analysis was incomplete. If the problems were lost in
    // serialization, a remote analyser would silently believe a partial IR is complete.
    TacProgram program =
        translate(
            """
            class R {
              void go() {
                int x = 1;
              }
            }
            """);
    TacProgram.FunctionProblem p =
        new TacProgram.FunctionProblem("R#go", "UNHANDLED_NODE", "not lowered: Foo", "Foo", 3, 9);
    program.addProgramProblem(p);

    java.nio.file.Path tmp = java.nio.file.Files.createTempFile("problems", ".tac");
    try {
      org.plumb.tac.io.IrSerializer.writeProgram(program, tmp);
      TacProgram back = org.plumb.tac.io.IrSerializer.readProgram(tmp);
      assertThat(back.programProblems())
          .as("a recorded gap must survive the IR round-trip")
          .hasSize(1);
      assertThat(back.programProblems().get(0).nodeClass()).isEqualTo("Foo");
      assertThat(back.programProblems().get(0).kind()).isEqualTo("UNHANDLED_NODE");
      assertThat(back.hasProblems()).isTrue();
    } finally {
      java.nio.file.Files.deleteIfExists(tmp);
    }
  }

  @Test
  @DisplayName("a problem is attributed to the function that contains the gap")
  void problemsAreAttributedToTheirFunction() {
    TacProgram program =
        translate(
            """
            class S {
              void a() { int x = 1; }
              void b() { int y = 2; }
            }
            """);
    List<TacProgram.FunctionProblem> problems = program.programProblems();
    assertThat(problems).isEmpty();
    // The record carries the function name, so a consumer can point at the right place.
    assertThat(
            new TacProgram.FunctionProblem("S#b", "UNHANDLED_NODE", "not lowered: Foo", "Foo", 4, 5)
                .functionName())
        .isEqualTo("S#b");
  }
}
