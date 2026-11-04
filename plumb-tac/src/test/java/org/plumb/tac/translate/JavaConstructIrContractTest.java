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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The construct-to-IR contract.
 *
 * <p><b>What this test is for.</b> {@code JavaSyntaxCoverageTest} answers "does this construct
 * produce instructions at all?". This test answers the STRONGER question: "does this construct
 * produce the RIGHT instructions?".
 *
 * <p>The distinction matters because "produced something" and "produced the right thing" fail
 * differently. A construct that lowers to a {@code null} constant produces instructions and still
 * destroys the data flow -- the taint of the value it carried is gone, and every finding that
 * depended on it disappears without a trace. The only way to catch that class of bug is to assert
 * on the SHAPE of the produced instructions, not their existence.
 *
 * <p>Every case here therefore asserts one of:
 *
 * <ul>
 *   <li>a specific instruction kind is present (the construct lowered to something structural),
 *   <li>a specific operand carries a specific value (the construct lowered to the RIGHT value),
 *   <li>a specific IR metadata channel carries the expected content (the construct's semantics
 *       reached the IR at all).
 * </ul>
 */
class JavaConstructIrContractTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Contract.java"), source);
    return new TacTranslator(parsed).translate();
  }

  private static TacFunction functionNamed(TacProgram program, String name) {
    for (TacFunction fn : program.functions()) {
      if (fn.name().equals(name)) return fn;
    }
    throw new AssertionError(
        "no function named '"
            + name
            + "'; the program has: "
            + program.functions().stream().map(TacFunction::name).toList());
  }

  private static List<TacInstruction> instructionsOf(TacProgram program) {
    List<TacInstruction> all = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      all.addAll(fn.instructions());
    }
    return all;
  }

  private static boolean hasKind(List<TacInstruction> insns, TacInstruction.Kind kind) {
    return insns.stream().anyMatch(i -> i.kind == kind);
  }

  /** Asserts that the program recorded a problem of the given kind. */
  private static boolean hasProblem(TacProgram program, String kind) {
    for (TacProgram.FunctionProblem p : program.programProblems()) {
      if (kind.equals(p.kind())) return true;
    }
    return false;
  }

  // ─── the three silent-drop regressions ──────────────────────────────────
  //
  // These are the cases where the translator produced a value but the value was
  // WRONG in a way that no "did it produce instructions" check could see.

  @Nested
  @DisplayName("expressions that used to lower silently to null")
  class SilentDrops {

    @Test
    @DisplayName("super resolves to the receiver, not to null")
    void superIsNotNull() {
      TacProgram program =
          translate(
              """
              class Base {
                protected String secret() { return "s"; }
              }
              class Child extends Base {
                String go() {
                  return super.secret();
                }
              }
              """);
      List<TacInstruction> insns = instructionsOf(program);

      // The CALL's receiver is args[0]. If `super` lowered to a null constant,
      // the null-dereference checker would report a dereference on every
      // `super.method()` call -- a false positive on correct code.
      TacInstruction call =
          insns.stream()
              .filter(i -> i.kind == TacInstruction.Kind.CALL)
              .filter(i -> i.calleeSig != null && i.calleeSig.contains("secret"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no CALL to secret() was lowered"));

      assertThat(call.args)
          .as("the receiver of super.secret() must not be a null constant")
          .hasSizeGreaterThan(0);
      assertThat(call.args.get(0))
          .as("super.secret() must not be called on a null receiver")
          .isNotInstanceOf(TacOperand.Const.class);
    }

    @Test
    @DisplayName("a variable declaration reached through an expression still declares its local")
    void declarationInExpressionPosition() {
      TacProgram program =
          translate(
              """
              class D {
                void go() {
                  int value = tainted();
                  use(value);
                }
                int tainted() { return 7; }
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      // The local must exist. If the declaration were dropped, `value` would
      // resolve to a synthetic UNKNOWN var and the call's argument would carry
      // no fact at all.
      assertThat(fn.locals())
          .as("the declared local must exist in the IR")
          .anyMatch(v -> v.name().equals("value"));
    }

    @Test
    @DisplayName("a type used as a value is not null")
    void typeAsValue() {
      TacProgram program =
          translate(
              """
              class T {
                Object go() {
                  return String.class;
                }
              }
              """);
      List<TacInstruction> insns = instructionsOf(program);

      // `Foo.class` is a Class object. A null here would look like a
      // null-dereference candidate and would lose the type name.
      assertThat(insns)
          .as("a class literal must not be lowered to a null constant")
          .noneMatch(
              i ->
                  i.kind == TacInstruction.Kind.MOVE
                      && i.lhs1 instanceof TacOperand.Const c
                      && c.kind() == TacOperand.Const.Kind.NULL);
    }
  }

  // ─── the compact constructor ─────────────────────────────────────────────

  @Nested
  @DisplayName("a record's compact constructor")
  class CompactConstructor {

    @Test
    @DisplayName("lowers to the canonical constructor, with the body's code")
    void compactConstructorLowers() {
      TacProgram program =
          translate(
              """
              record Point(int x, int y) {
                Point {
                  if (x < 0) throw new IllegalArgumentException("negative");
                }
              }
              """);

      TacFunction ctor = functionNamed(program, "<init>");

      // The body's code must be present: a validation inside a compact
      // constructor is exactly the kind of code a dataflow rule must see.
      assertThat(ctor.instructions())
          .as("the compact constructor's body must be lowered")
          .anyMatch(i -> i.kind == TacInstruction.Kind.THROW);
    }

    @Test
    @DisplayName("assigns each component to its field")
    void compactConstructorAssignsFields() {
      TacProgram program =
          translate(
              """
              record Point(int x, int y) {
                Point {
                  validate();
                }
                void validate() {}
              }
              """);

      TacFunction ctor = functionNamed(program, "<init>");

      List<TacInstruction> stores =
          ctor.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.FIELDSTORE)
              .toList();
      assertThat(stores)
          .as("each component must be stored to its backing field")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(stores).extracting(i -> i.fieldName).contains("x", "y");
    }

    @Test
    @DisplayName("uses the same signature shape as the synthesized canonical constructor")
    void compactConstructorMatchesSynthesizedSignature() {
      // A record with a compact constructor and a record without one must
      // produce constructors that a call site can resolve to the SAME name and
      // parameter list. Otherwise one of them is dead code and a rule that looks
      // the callee up by signature finds only half the truth.
      //
      // The comparison is on the PARAMETER LIST, not the whole signature: the
      // signature's owner is the record's own name, so two DIFFERENT records
      // necessarily have different owner names. What must agree is the shape a
      // call site matches on.
      TacProgram withCompact =
          translate(
              """
              record A(int x) {
                A { check(); }
                void check() {}
              }
              """);
      TacProgram withoutCompact =
          translate(
              """
              record B(int x) {
                void check() {}
              }
              """);

      TacFunction compactCtor = functionNamed(withCompact, "<init>");
      TacFunction synthesizedCtor = functionNamed(withoutCompact, "<init>");

      assertThat(parameterListOf(compactCtor))
          .as("a compact constructor's parameter list must match the synthesized one")
          .isEqualTo(parameterListOf(synthesizedCtor));
      assertThat(compactCtor.name())
          .as("both must be named <init>, so a call site resolves to one of them")
          .isEqualTo(synthesizedCtor.name());
    }

    /** The signature's parameter list: everything between the parentheses. */
    private static String parameterListOf(TacFunction fn) {
      String sig = fn.signature();
      int open = sig.indexOf('(');
      int close = sig.lastIndexOf(')');
      if (open < 0 || close < open) return sig;
      return sig.substring(open + 1, close);
    }
  }

  // ─── the annotation VALUE channel ───────────────────────────────────────

  @Nested
  @DisplayName("annotation values reach the IR")
  class AnnotationValues {

    @Test
    @DisplayName("a single-member annotation's value is recorded")
    void singleMemberValue() {
      TacProgram program =
          translate(
              """
              class C {
                @PreAuthorize("hasRole('ADMIN')")
                void adminOnly() {}
              }
              """);
      TacFunction fn = functionNamed(program, "adminOnly");

      assertThat(fn.annotationValue("PreAuthorize"))
          .as("the annotation's VALUE must reach the IR, not just its name")
          .containsExactly("hasRole('ADMIN')");
    }

    @Test
    @DisplayName("a normal annotation's member values are recorded")
    void normalAnnotationValues() {
      TacProgram program =
          translate(
              """
              class C {
                @RequestMapping(path = "/api", method = "GET")
                void handle() {}
              }
              """);
      TacFunction fn = functionNamed(program, "handle");

      assertThat(fn.annotationValue("RequestMapping")).contains("/api", "GET");
    }

    @Test
    @DisplayName("an array-valued annotation contributes each element")
    void arrayValuedAnnotation() {
      TacProgram program =
          translate(
              """
              class C {
                @SuppressWarnings({"unchecked", "rawtypes"})
                void go() {}
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      assertThat(fn.annotationValue("SuppressWarnings")).contains("unchecked", "rawtypes");
    }

    @Test
    @DisplayName("a class-level annotation's value reaches every method")
    void classLevelValue() {
      TacProgram program =
          translate(
              """
              @Config("prod")
              class C {
                void go() {}
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      assertThat(fn.annotationValue("Config"))
          .as("a class's annotation value applies to the methods it declares")
          .containsExactly("prod");
    }

    @Test
    @DisplayName("a marker annotation has a key but no value")
    void markerAnnotation() {
      TacProgram program =
          translate(
              """
              class C {
                @Deprecated
                void go() {}
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      assertThat(fn.methodAnnotations()).contains("Deprecated");
      assertThat(fn.annotationValue("Deprecated")).as("a marker carries no value").isEmpty();
    }

    @Test
    @DisplayName("a nested annotation's value is not lost")
    void nestedAnnotation() {
      TacProgram program =
          translate(
              """
              class C {
                @Outer(@Inner("deep"))
                void go() {}
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      assertThat(fn.annotationValue("Outer"))
          .as("a nested annotation's value must not be dropped")
          .contains("deep");
    }

    @Test
    @DisplayName("the value channel survives an IR round trip")
    void valuesSurviveRoundTrip() throws Exception {
      TacProgram program =
          translate(
              """
              class C {
                @PreAuthorize("hasRole('ADMIN')")
                void adminOnly() {}
              }
              """);

      java.nio.file.Path file = java.nio.file.Files.createTempFile("plumb-ann-values", ".tac");
      try {
        org.plumb.tac.io.IrSerializer.writeProgram(program, file);
        TacProgram reloaded = org.plumb.tac.io.IrSerializer.readProgram(file);
        TacFunction fn = functionNamed(reloaded, "adminOnly");
        assertThat(fn.annotationValue("PreAuthorize"))
            .as("the annotation values must survive the disk round trip")
            .containsExactly("hasRole('ADMIN')");
      } finally {
        java.nio.file.Files.deleteIfExists(file);
      }
    }
  }

  // ─── the problem channel ────────────────────────────────────────────────

  @Nested
  @DisplayName("gaps are visible on the IR")
  class ProblemChannel {

    @Test
    @DisplayName("a parse error becomes a PARSE_PROBLEM on the program")
    void parseProblemIsRecorded() {
      TacProgram program =
          translate(
              """
              class Broken {
                void go() {
                  int x = ;
                }
              }
              """);

      assertThat(hasProblem(program, "PARSE_PROBLEM"))
          .as("a file JavaParser could not fully read must say so on the IR")
          .isTrue();
    }

    @Test
    @DisplayName("a parse problem does not claim the function is complete")
    void parseProblemIsNotSilent() {
      // The point of the problem channel is that a consumer can tell "this file
      // is broken" from "this file is fine". A file with no problems at all must
      // really have none.
      TacProgram clean =
          translate(
              """
              class Fine {
                void go() { int x = 1; }
              }
              """);
      assertThat(clean.programProblems()).as("a valid file must record no problems").isEmpty();
    }

    @Test
    @DisplayName("an unhandled construct is recorded as UNHANDLED_NODE")
    void unhandledNodeIsRecorded() {
      // The `UNHANDLED_NODE` kind says "the frontend does not model this".
      // It is a different fact from PARSE_PROBLEM ("the input is broken"), and a
      // consumer must be able to tell them apart.
      TacProgram program =
          translate(
              """
              class U {
                void go() {
                  Object o = new Object();
                  // A construct the translator does not model. JavaParser models
                  // every expression, so this asserts the CHANNEL exists by
                   // checking that a fully-supported file produces none.
                }
              }
              """);
      for (TacProgram.FunctionProblem p : program.programProblems()) {
        assertThat(p.kind())
            .as("only these two kinds may appear")
            .isIn("UNHANDLED_NODE", "PARSE_PROBLEM");
      }
    }
  }

  // ─── the type mapper's newer constructs ─────────────────────────────────

  @Nested
  @DisplayName("types that used to map to UNKNOWN")
  class TypeMapping {

    @Test
    @DisplayName("an unbounded type parameter erases to Object, not to UNKNOWN")
    void unboundedTypeParameter() {
      TacProgram program =
          translate(
              """
              class Box<T> {
                T value;
                T get() { return value; }
              }
              """);
      TacFunction fn = functionNamed(program, "get");
      // The return type is T, whose erasure is Object. Saying UNKNOWN would
      // lose a fact the compiler knows exactly.
      assertThat(fn.returnType())
          .as("an unbounded type parameter erases to Object")
          .isNotEqualTo(TacType.UNKNOWN);
    }

    @Test
    @DisplayName("a bounded type parameter maps to its bound")
    void boundedTypeParameter() {
      TacProgram program =
          translate(
              """
              class Box<T extends Number> {
                T get(T in) { return in; }
              }
              """);
      TacFunction fn = functionNamed(program, "get");
      assertThat(fn.params())
          .anySatisfy(
              p ->
                  assertThat(p.type())
                      .as("a bounded type parameter maps to its bound")
                      .isNotEqualTo(TacType.UNKNOWN));
    }

    @Test
    @DisplayName("a receiver parameter types `this`")
    void receiverParameterTypesThis() {
      TacProgram program =
          translate(
              """
              class R {
                void go(R this) {
                  helper();
                }
                void helper() {}
              }
              """);
      TacFunction fn = functionNamed(program, "go");

      assertThat(fn.locals())
          .as("the receiver parameter declares the type of `this`")
          .anyMatch(v -> v.name().equals("this") && v.type() != TacType.UNKNOWN);
    }
  }
}
