package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.printer.TacPrinter;

class LambdaTranslationTest {

  @Test
  void lambdaBodyBecomesSyntheticFunction() {
    String src =
        "import java.util.function.Function; class T { int m() { Function<String,Integer> f ="
            + " (String s) -> s.length(); return 0; } }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("L.java"), src);
    var program = new TacTranslator(ps).translate();
    // The program should now have both `m` and `lambda$0`.
    boolean hasLambda = program.functions().stream().anyMatch(f -> f.name().startsWith("lambda$"));
    assertThat(hasLambda)
        .as("lambda body should be lowered to a synthetic function\n" + TacPrinter.dump(program))
        .isTrue();
  }

  @Test
  void lambdaExpressionBody() {
    String src =
        "import java.util.function.*; "
            + "class T { void m() { Runnable r = () -> System.out.println(\"x\"); r.run(); } }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("L.java"), src);
    var program = new TacTranslator(ps).translate();
    assertThat(program.functions()).isNotEmpty();
  }

  @Test
  void lambdaWithCaptureRunsCallSite() {
    String src =
        "import java.util.function.*; class T { void m() { Function<String,String> f = s ->"
            + " s.trim(); f.apply(\"hi\"); } }";
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("L.java"), src);
    var program = new TacTranslator(ps).translate();
    // The m() function should have a CALL instruction for the lambda invocation.
    TacFunction m = program.function("m():void");
    assertThat(m).as("function m should exist").isNotNull();
    boolean hasLambdaCall =
        m.instructions().stream()
            .anyMatch(
                i ->
                    i.kind == TacInstruction.Kind.CALL
                        && i.calleeSig != null
                        && i.calleeSig.contains("lambda$"));
    assertThat(hasLambdaCall)
        .as("call site to lambda should appear in m()\n" + TacPrinter.dump(program))
        .isTrue();
  }
}
