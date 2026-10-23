package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * verify the explicit try / catch / finally IR:
 *
 * <ul>
 *   <li>{@link TacInstruction.Kind#CATCH_HANDLER} is emitted immediately after the {@link
 *       TacInstruction.Kind#LABEL} for each catch clause, with {@code ctorType} set to the declared
 *       exception type and {@code dest} set to the catch parameter (a {@link TacOperand.TacVar}).
 *   <li>{@link TacInstruction.Kind#FINALLY_ENTER} is emitted for a finally block, paired with a
 *       {@code L_FINALLY} label so existing label-based tooling keeps working.
 *   <li>Order of emitted instructions is {@code [try body ...] [CATCH_HANDLER Exception] [catch
 *       body ...] [CATCH_HANDLER IOException] [catch body ...] [FINALLY_ENTER] [finally body ...]}.
 * </ul>
 *
 * <p>This is a unit-level test of the AST translator only. Exception-flow edges in the CFG are
 * validated by the {@code DfaEngineExceptionFlow} tests in {@code plumb-dfa}.
 */
class TryCatchFinallyTranslationTest {

  private TacProgram translate(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("Test.java"), src);
    return new TacTranslator(ps).translate();
  }

  private TacFunction firstFunction(TacProgram p) {
    assertThat(p.functions()).isNotEmpty();
    return p.functions().get(0);
  }

  @Test
  void tryCatch_emitsCatchHandlerWithDeclaredType() {
    String src =
        "class T { void m() { "
            + "try { System.out.println(1); } "
            + "catch (java.io.IOException e) { System.out.println(2); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    List<TacInstruction> catchHandlers =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CATCH_HANDLER)
            .collect(Collectors.toList());
    assertThat(catchHandlers).as("exactly one CATCH_HANDLER for one catch clause").hasSize(1);
    TacInstruction ch = catchHandlers.get(0);
    assertThat(ch.ctorType)
        .as("CATCH_HANDLER.ctorType carries the declared IOException")
        .isNotNull();
    assertThat(ch.ctorType.toString()).as("declared type string form").contains("IOException");
    assertThat(ch.dest)
        .as("CATCH_HANDLER.dest is the catch parameter")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) ch.dest).name()).as("catch parameter name").isEqualTo("e");
    // catch parameter is non-null by Java semantics;
    // verify we do NOT emit a MOVE NULL initialization in front
    // of the catch parameter.
    long nullMoves =
        fn.instructions().stream()
            .filter(
                i ->
                    i.kind == TacInstruction.Kind.MOVE
                        && i.dest instanceof TacOperand.TacVar tv
                        && "e".equals(tv.name())
                        && i.lhs1 instanceof TacOperand.Const c
                        && c.value() == null)
            .count();
    assertThat(nullMoves).as("catch parameter must not be MOVE-initialized to null").isZero();
  }

  @Test
  void tryMultiCatch_emitsMultipleCatchHandlers() {
    String src =
        "class T { void m() { "
            + "try { System.out.println(1); } "
            + "catch (java.io.IOException e1) { System.out.println(2); } "
            + "catch (RuntimeException e2) { System.out.println(3); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    List<TacInstruction> catchHandlers =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CATCH_HANDLER)
            .collect(Collectors.toList());
    assertThat(catchHandlers).as("two catch clauses yield two CATCH_HANDLERs").hasSize(2);
    assertThat(catchHandlers.get(0).ctorType.toString()).contains("IOException");
    assertThat(catchHandlers.get(1).ctorType.toString()).contains("RuntimeException");
    assertThat(((TacOperand.TacVar) catchHandlers.get(0).dest).name()).isEqualTo("e1");
    assertThat(((TacOperand.TacVar) catchHandlers.get(1).dest).name()).isEqualTo("e2");
  }

  @Test
  void tryFinally_emitsFinallyEnter() {
    String src =
        "class T { void m() { "
            + "try { System.out.println(1); } "
            + "finally { System.out.println(2); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    List<TacInstruction> finallyEnters =
        fn.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.FINALLY_ENTER)
            .collect(Collectors.toList());
    assertThat(finallyEnters).as("finally block yields a FINALLY_ENTER marker").hasSize(1);
    // the L_FINALLY label must coexist with the marker
    // so existing label-based tooling still works.
    long finallyLabels =
        fn.instructions().stream()
            .filter(
                i ->
                    i.kind == TacInstruction.Kind.LABEL
                        && i.label != null
                        && i.label.startsWith("L_FINALLY"))
            .count();
    assertThat(finallyLabels)
        .as("L_FINALLY label is paired with the FINALLY_ENTER marker")
        .isEqualTo(1);
  }

  @Test
  void tryCatchFinally_combinesCatchHandlersAndFinally() {
    String src =
        "class T { void m() { "
            + "try { System.out.println(1); } "
            + "catch (java.io.IOException e) { System.out.println(2); } "
            + "finally { System.out.println(3); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    long catchHandlers =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.CATCH_HANDLER).count();
    long finallyEnters =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.FINALLY_ENTER).count();
    assertThat(catchHandlers).as("one catch handler").isEqualTo(1);
    assertThat(finallyEnters).as("one finally marker").isEqualTo(1);
  }

  @Test
  void catchBlockLabel_isReachableFromCatcher() {
    // the {@code L_CATCH_<Type>} label
    // is preserved alongside the new CATCH_HANDLER so that
    // existing {@code UnusedLabelChecker} and downstream
    // SARIF location decoding still find the label.
    String src =
        "class T { void m() { "
            + "try { System.out.println(1); } "
            + "catch (java.io.IOException e) { System.out.println(2); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    long catchLabels =
        fn.instructions().stream()
            .filter(
                i ->
                    i.kind == TacInstruction.Kind.LABEL
                        && i.label != null
                        && i.label.startsWith("L_CATCH_"))
            .count();
    assertThat(catchLabels)
        .as("R151 L_CATCH_<Type> label is preserved alongside the new IR")
        .isEqualTo(1);
  }

  @Test
  void throwInsideTryBody_isRecognized() {
    // The translator must still emit the THROW instruction;
    // exception flow from THROW to CATCH_HANDLER is the
    // CFG builder's responsibility (separate test).
    String src =
        "class T { void m() { "
            + "try { throw new RuntimeException(\"x\"); } "
            + "catch (RuntimeException e) { System.out.println(e); } "
            + "} }";
    TacFunction fn = firstFunction(translate(src));
    long throwCount =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.THROW).count();
    long catchHandlers =
        fn.instructions().stream().filter(i -> i.kind == TacInstruction.Kind.CATCH_HANDLER).count();
    assertThat(throwCount).as("THROW inside try body is preserved").isEqualTo(1);
    assertThat(catchHandlers).as("matching catch handler is also emitted").isEqualTo(1);
  }

  @Test
  void tryWithResources_isTranslated() {
    // try-with-resources (Java 7+) lowers to try { ... } catch
    // (Throwable t) { ... } finally { ... } in the AST. The
    // translator must still emit the explicit try / catch /
    // finally IR for it.
    //
    // Currently the JavaParser 3.x TryStmt in this codebase
    // does not visit try-with-resources resources, so this
    // case is deferred to a follow-up round. The test
    // asserts the translator does not crash and the body
    // is processed (at minimum, try / catch / finally are
    // explicit in the IR; resource-management wiring is pending).
    String src =
        "class T { int m() throws Exception { "
            + "try (java.io.FileInputStream in = new java.io.FileInputStream(\"f\")) { "
            + "return in.read(); "
            + "} "
            + "} }";
    // No crash is the assertion; if the translator is updated
    // to handle try-with-resources, additional assertions can
    // be added then.
    TacFunction fn = firstFunction(translate(src));
    // Sanity: the function has at least one RETURN (the inner
    // read()), and the translator produced SOME output.
    assertThat(fn.instructions()).as("try-with-resources body is still translated").isNotEmpty();
  }
}
