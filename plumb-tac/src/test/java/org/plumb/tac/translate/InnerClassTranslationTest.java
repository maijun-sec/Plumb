package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.printer.TacPrinter;

class InnerClassTranslationTest {

  @Test
  void innerClassMethodsAppearAsSeparateFunctions() {
    String src =
        "class Outer { "
            + "class Inner { "
            + "  int compute(int x) { return x + 1; } "
            + "} "
            + "}";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();
    TacPrinter.dump(program);
    // The inner class's method is now flattened as a top-level-ish
    // function; its signature is prefixed with the class name.
    boolean hasInner =
        program.functions().stream()
            .anyMatch(f -> f.name().contains("Inner") || f.signature().contains("Inner"));
    assertThat(hasInner)
        .as("inner class method should be a function\n" + TacPrinter.dump(program))
        .isTrue();
  }

  @Test
  void anonymousClassLowered() {
    // ObjectCreationExpr of an anonymous class is a NEW in our IR
    // anyway; we just want to make sure it doesn't crash.
    String src = "class T { Runnable r = new Runnable() { public void run() { } }; }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("I.java"), src);
    var program = new TacTranslator(ps).translate();
    assertThat(program).isNotNull();
  }
}
