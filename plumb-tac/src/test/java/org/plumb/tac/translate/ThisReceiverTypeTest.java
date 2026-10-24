package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The implicit {@code this} must carry the type of the class that DECLARES the method.
 *
 * <p>The translator used to type every {@code this} as {@code source.primaryTypeName()} — the
 * FIRST top-level class of the compilation unit. In a file with more than one class, every class
 * after the first was therefore typed as the first class:
 *
 * <pre>
 *   class Repository { }
 *   class Controller { void m() { this.helper(); } }
 *   // -> this was typed "Repository", and the call became "Repository.helper/0"
 * </pre>
 *
 * <p>That single wrong type propagated everywhere the receiver's type is read: FQN-anchored sink
 * patterns, the cross-function field taint fold, the CHA resolver's receiver-type lookup, and the
 * points-to receiver identity. It is a silent defect — nothing errors, the IR is just wrong for
 * every class after the first.
 *
 * <p>These tests pin the corrected behaviour: {@code this} carries the declaring class, the
 * enclosing class of the function agrees with it, and the callee signature names the right class.
 */
class ThisReceiverTypeTest {

  @Test
  void eachClassGetsItsOwnThisType() {
    String src =
        "class A { public void a1() { this.a2(); } public void a2() { } }\n"
            + "class B { public void b1() { this.b2(); } public void b2() { } }\n"
            + "class C { public void c1() { this.c2(); } public void c2() { } }\n";

    List<String> mismatches = new ArrayList<>();
    int checked = 0;
    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        TacOperand receiver = thisReceiverOf(ins);
        if (receiver == null) {
          continue;
        }
        checked++;
        String recorded = classNameOf(receiver);
        String enclosing = f.enclosingClass();
        if (enclosing == null || enclosing.isEmpty() || !enclosing.equals(recorded)) {
          mismatches.add(
              "fn=" + f.signature() + " enclosing=" + enclosing + " thisTypedAs=" + recorded);
        }
      }
    }

    assertThat(checked).as("expected one `this` receiver per class").isEqualTo(3);
    assertThat(mismatches).as("`this` must carry its declaring class").isEmpty();
  }

  @Test
  void thisCallNamesTheDeclaringClassInTheCalleeSignature() {
    String src =
        "class Repository { public void helper(String a) { } }\n"
            + "class Controller { public void go(String a) { this.helper(a); } }\n";

    List<String> calleeSigs = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL && thisReceiverOf(ins) != null) {
          calleeSigs.add(ins.calleeSig);
        }
      }
    }

    assertThat(calleeSigs)
        .as("the callee signature must name the declaring class, not the first class in the file")
        .containsExactly("Controller.helper/1");
  }

  @Test
  void nestedClassGetsTheSameQualifiedNameTheEngineUsesForItsFunctions() {
    String src =
        "class Outer { class Inner { public void go(String a) { this.helper(a); } "
            + "public void helper(String a) { } } }\n";

    List<String> recorded = new ArrayList<>();
    List<String> functionNames = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      functionNames.add(f.name());
      for (TacInstruction ins : f.instructions()) {
        TacOperand receiver = thisReceiverOf(ins);
        if (receiver != null) {
          recorded.add(classNameOf(receiver));
        }
      }
    }

    // The engine names an inner class's functions "Inner.go" (the first
    // nesting level uses the bare inner name; deeper levels use the
    // JVM-style "Mid$Leaf"). `this` must carry the same qualifier, so
    // the receiver type and the function name agree.
    assertThat(functionNames).contains("Inner.go");
    assertThat(recorded)
        .as("a nested class's `this` must carry the same qualifier as its function name")
        .containsExactly("Inner");
  }

  @Test
  void deeplyNestedClassCarriesTheJvmStyleQualifiedName() {
    String src =
        "class Double { class Mid { class Leaf { public void go(String a) { this.helper(a); } "
            + "public void helper(String a) { } } } }\n";

    List<String> recorded = new ArrayList<>();
    List<String> functionNames = new ArrayList<>();
    for (TacFunction f : translate(src).functions()) {
      functionNames.add(f.name());
      for (TacInstruction ins : f.instructions()) {
        TacOperand receiver = thisReceiverOf(ins);
        if (receiver != null) {
          recorded.add(classNameOf(receiver));
        }
      }
    }

    assertThat(functionNames).contains("Mid$Leaf.go");
    assertThat(recorded).containsExactly("Mid$Leaf");
  }

  @Test
  void staticMethodHasNoThisReceiver() {
    String src = "class A { public static void s(String a) { } }\nclass B { public void b() { } }\n";

    for (TacFunction f : translate(src).functions()) {
      for (TacInstruction ins : f.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL) {
          assertThat(thisReceiverOf(ins))
              .as("a static method must not record a `this` receiver")
              .isNull();
        }
      }
    }
  }

  private static TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("T.java"), src);
    return new TacTranslator(ps).translate();
  }

  /** The receiver operand of a call, when it is the implicit {@code this}. */
  private static TacOperand thisReceiverOf(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.CALL) {
      return null;
    }
    if (ins.args == null || ins.args.isEmpty()) {
      return null;
    }
    TacOperand first = ins.args.get(0);
    if (first instanceof TacOperand.TacVar v && "this".equals(v.name())) {
      return v;
    }
    return null;
  }

  private static String classNameOf(TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar v)) {
      return null;
    }
    var type = v.type();
    if (type == null || type.kind() != org.plumb.tac.ir.TacType.Kind.CLASS) {
      return null;
    }
    return type.className();
  }
}
