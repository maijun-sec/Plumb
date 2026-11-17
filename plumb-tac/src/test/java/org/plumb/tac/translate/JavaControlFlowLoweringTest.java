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
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.java.JavaTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The Java lowering of unary operators, assignment targets, and the jump targets of {@code break}
 * and {@code continue}.
 *
 * <p><b>What each test pins, and what used to be wrong.</b>
 *
 * <ul>
 *   <li>A unary plus used to fall through the operator dispatch and produce a NULL constant, so
 *       every tainted value that passed through a {@code +x} was silently dropped. It is the
 *       identity of its operand.
 *   <li>{@code i++} used to emit an operator instruction and nothing else: the target was never
 *       written back, so every later read of {@code i} saw the pre-increment value.
 *   <li>A {@code break} used to target the innermost SWITCH whenever one was open, so a {@code
 *       break} inside a loop inside a switch case left the wrong construct.
 *   <li>A labelled {@code break} / {@code continue} used to be ignored: the label was dropped and
 *       the jump went to the innermost loop.
 * </ul>
 */
class JavaControlFlowLoweringTest {

  private static TacProgram translate(String source) {
    ParsedSource parsed =
        new JavaSourceParser().parseFromString(Path.of("ControlFlow.java"), source);
    return new TacTranslator(parsed).translate();
  }

  /** Every instruction of every function of the program, in order. */
  private static List<TacInstruction> instructions(TacProgram program) {
    List<TacInstruction> out = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      out.addAll(fn.instructions());
    }
    return out;
  }

  /** The names of the LABEL instructions whose name starts with the given prefix, in order. */
  private static List<String> labelsStartingWith(TacProgram program, String prefix) {
    List<String> out = new ArrayList<>();
    for (TacInstruction ins : instructions(program)) {
      if (ins.kind == TacInstruction.Kind.LABEL
          && ins.label != null
          && ins.label.startsWith(prefix)) {
        out.add(ins.label);
      }
    }
    return out;
  }

  /** The target of every JUMP instruction, in order. */
  private static List<String> jumpTargets(TacProgram program) {
    List<String> out = new ArrayList<>();
    for (TacInstruction ins : instructions(program)) {
      if (ins.kind == TacInstruction.Kind.JUMP && ins.label != null) {
        out.add(ins.label);
      }
    }
    return out;
  }

  /** The construct id of every drop recorded anywhere in the program. */
  private static List<String> dropConstructs(TacProgram program) {
    List<String> out = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      for (TacDrop drop : fn.drops()) {
        out.add(drop.construct());
      }
    }
    return out;
  }

  /** The name of an instruction's destination variable, or null when it has none. */
  private static String destName(TacInstruction ins) {
    return ins.dest instanceof TacOperand.TacVar var ? var.name() : null;
  }

  /** The name of an instruction's first source operand when it is a variable, else null. */
  private static String srcName(TacInstruction ins) {
    return ins.lhs1 instanceof TacOperand.TacVar var ? var.name() : null;
  }

  /** The single instruction of the given kind; fails when there is not exactly one. */
  private static TacInstruction only(TacProgram program, TacInstruction.Kind kind) {
    List<TacInstruction> found = new ArrayList<>();
    for (TacInstruction ins : instructions(program)) {
      if (ins.kind == kind) found.add(ins);
    }
    assertThat(found).as("the source must lower to exactly one %s instruction", kind).hasSize(1);
    return found.get(0);
  }

  @Test
  @DisplayName("a unary plus is the identity of its operand")
  void unaryPlusIsTheIdentity() {
    // `+x` is not an operation in Java: the operand's value is unchanged. It used to
    // produce a NULL constant instead, which asserted a fact about the program that
    // was never true and dropped every taint fact about the value on the way.
    TacProgram program =
        translate(
            """
            class C {
              void go(int x) {
                int y = +x;
              }
            }
            """);
    assertThat(instructions(program))
        .as("a unary plus must not lower to an operator instruction")
        .noneMatch(ins -> ins.kind == TacInstruction.Kind.UNARYOP);
    assertThat(instructions(program))
        .as("a unary plus must lower to a move of its operand")
        .anyMatch(
            ins ->
                ins.kind == TacInstruction.Kind.MOVE
                    && "x".equals(srcName(ins))
                    && !"x".equals(destName(ins)));
  }

  @Test
  @DisplayName("a postfix increment writes the new value back into the local")
  void postfixIncrementWritesBackIntoTheLocal() {
    // The engine's operator instruction writes the new value into a fresh temporary and
    // leaves the operand alone, so the write-back is the translator's job. Without it
    // the counter never moved.
    TacProgram program =
        translate(
            """
            class C {
              void go() {
                int i = 0;
                i++;
              }
            }
            """);
    assertThat(instructions(program))
        .as("the incremented value must be stored back into i")
        .anyMatch(
            ins ->
                ins.kind == TacInstruction.Kind.MOVE
                    && "i".equals(destName(ins))
                    && !"i".equals(srcName(ins)));
  }

  @Test
  @DisplayName("a postfix increment evaluates to the value from before the increment")
  void postfixIncrementEvaluatesToTheOldValue() {
    // `int y = i++;` must give y the OLD i. The write-back overwrites i, so the old
    // value has to be snapshotted before the write-back -- otherwise y would hold the
    // new value.
    TacProgram program =
        translate(
            """
            class C {
              void go() {
                int i = 0;
                int y = i++;
              }
            }
            """);
    List<TacInstruction> insns = instructions(program);
    TacInstruction toY =
        insns.stream()
            .filter(ins -> ins.kind == TacInstruction.Kind.MOVE && "y".equals(destName(ins)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the assignment to y did not lower to a move"));
    String snapshot = srcName(toY);
    assertThat(snapshot)
        .as("the postfix expression must read the snapshot, not the variable that is overwritten")
        .isNotNull()
        .isNotEqualTo("i");

    int snapshotIndex = indexOfMove(insns, snapshot, "i");
    assertThat(snapshotIndex)
        .as("the snapshot must be a move of i into a temporary")
        .isNotNegative();

    TacInstruction operator = only(program, TacInstruction.Kind.UNARYOP);
    String newValue = destName(operator);
    int writeBackIndex = indexOfMove(insns, "i", newValue);
    assertThat(writeBackIndex)
        .as("the incremented value must be written back into i")
        .isNotNegative();
    assertThat(snapshotIndex)
        .as("the snapshot must be taken before the variable is overwritten")
        .isLessThan(writeBackIndex);
  }

  /** The index of the move from {@code src} into {@code dest}, or -1. */
  private static int indexOfMove(List<TacInstruction> insns, String dest, String src) {
    for (int i = 0; i < insns.size(); i++) {
      TacInstruction ins = insns.get(i);
      if (ins.kind == TacInstruction.Kind.MOVE
          && dest.equals(destName(ins))
          && src.equals(srcName(ins))) {
        return i;
      }
    }
    return -1;
  }

  @Test
  @DisplayName("a prefix increment evaluates to the value from after the increment")
  void prefixIncrementEvaluatesToTheNewValue() {
    // `int y = ++i;` must give y the NEW i -- the same value that was stored back.
    TacProgram program =
        translate(
            """
            class C {
              void go() {
                int i = 0;
                int y = ++i;
              }
            }
            """);
    TacInstruction operator = only(program, TacInstruction.Kind.UNARYOP);
    TacInstruction toY =
        instructions(program).stream()
            .filter(ins -> ins.kind == TacInstruction.Kind.MOVE && "y".equals(destName(ins)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the assignment to y did not lower to a move"));
    assertThat(srcName(toY))
        .as("the prefix expression must evaluate to the value from after the increment")
        .isEqualTo(destName(operator));
  }

  @Test
  @DisplayName("a field increment writes the new value back into the field")
  void fieldIncrementWritesBackIntoTheField() {
    TacProgram program =
        translate(
            """
            class Holder { int f; }
            class C {
              void go(Holder h) {
                h.f++;
              }
            }
            """);
    assertThat(instructions(program))
        .as("the incremented field must be stored back with a field store")
        .anyMatch(ins -> ins.kind == TacInstruction.Kind.FIELDSTORE && "f".equals(ins.fieldName));
  }

  @Test
  @DisplayName("a labelled break leaves the loop the label names, not the innermost one")
  void labelledBreakLeavesTheNamedLoop() {
    TacProgram program =
        translate(
            """
            class C {
              void go() {
                outer: for (int i = 0; i < 3; i++) {
                  for (int j = 0; j < 3; j++) {
                    break outer;
                  }
                }
              }
            }
            """);
    List<String> forEnds = labelsStartingWith(program, "L_FOR_END_");
    assertThat(forEnds).as("the source has two for loops").hasSize(2);
    // The inner loop's end label is emitted first (the inner loop finishes first), so
    // index 0 is the inner one and index 1 is the one the label names.
    assertThat(jumpTargets(program))
        .as("a labelled break must leave the loop the label names")
        .contains(forEnds.get(1))
        .doesNotContain(forEnds.get(0));
  }

  @Test
  @DisplayName("a labelled continue jumps to the update step of the loop the label names")
  void labelledContinueJumpsToTheNamedLoop() {
    TacProgram program =
        translate(
            """
            class C {
              void go() {
                outer: for (int i = 0; i < 3; i++) {
                  for (int j = 0; j < 3; j++) {
                    continue outer;
                  }
                }
              }
            }
            """);
    List<String> updates = labelsStartingWith(program, "L_FOR_UPDATE_");
    assertThat(updates).as("the source has two for loops").hasSize(2);
    assertThat(jumpTargets(program))
        .as("a labelled continue must jump to the update step of the named loop")
        .contains(updates.get(1))
        .doesNotContain(updates.get(0));
  }

  @Test
  @DisplayName("an unlabelled break inside a loop inside a switch case leaves the loop")
  void unlabelledBreakInsideALoopLeavesTheLoop() {
    // Java's rule is "the innermost enclosing switch, while, do or for statement" --
    // and the innermost is decided by NESTING. The switch is further out than the
    // loop here, so the break leaves the loop.
    TacProgram program =
        translate(
            """
            class C {
              void go(int n) {
                switch (n) {
                  case 1:
                    for (int i = 0; i < 3; i++) { break; }
                    break;
                  default:
                    break;
                }
              }
            }
            """);
    List<String> forEnds = labelsStartingWith(program, "L_FOR_END_");
    assertThat(forEnds).as("the source has one for loop").hasSize(1);
    assertThat(jumpTargets(program))
        .as("the break inside the loop must leave the loop, not the switch")
        .contains(forEnds.get(0));
  }

  @Test
  @DisplayName("an unlabelled break directly in a switch case leaves the switch")
  void unlabelledBreakInsideASwitchLeavesTheSwitch() {
    TacProgram program =
        translate(
            """
            class C {
              void go(int n) {
                for (int i = 0; i < 3; i++) {
                  switch (n) { case 1: break; default: break; }
                }
              }
            }
            """);
    List<String> switchEnds = labelsStartingWith(program, "L_SW_END_");
    List<String> forEnds = labelsStartingWith(program, "L_FOR_END_");
    assertThat(switchEnds).as("the source has one switch").hasSize(1);
    assertThat(jumpTargets(program))
        .as("the break inside the case must leave the switch")
        .contains(switchEnds.get(0))
        .doesNotContain(forEnds.get(0));
  }

  @Test
  @DisplayName("a continue inside a switch case jumps to the enclosing loop")
  void continueInsideASwitchJumpsToTheLoop() {
    // A continue never targets a switch: it always goes to the innermost loop, even
    // when a switch sits between the continue and the loop.
    TacProgram program =
        translate(
            """
            class C {
              void go(int n) {
                for (int i = 0; i < 3; i++) {
                  switch (n) { case 1: continue; default: break; }
                }
              }
            }
            """);
    List<String> updates = labelsStartingWith(program, "L_FOR_UPDATE_");
    assertThat(updates).as("the source has one for loop").hasSize(1);
    assertThat(jumpTargets(program))
        .as("the continue must jump to the loop's update step")
        .contains(updates.get(0));
  }

  @Test
  @DisplayName("a continue with no enclosing loop records a drop and raises no problem")
  void continueWithNoLoopIsRecordedAsADrop() {
    // `continue` outside a loop is not valid Java, but the translator must not drop it
    // silently: the jump is simply not emitted, so the statements that should have
    // been skipped keep running. The design records this as a drop -- a machine
    // readable receipt -- and not as a problem, because a problem is a message about
    // a construct the analyser does not model, and here it DID model the construct.
    TacProgram program =
        translate(
            """
            class C {
              void go(int n) {
                switch (n) { case 1: continue; default: break; }
              }
            }
            """);
    assertThat(dropConstructs(program))
        .as("the lost jump must be recorded as a drop")
        .contains("java.continue.no_target");
    assertThat(program.programProblems())
        .as("a construct recorded as a drop must not also raise a problem")
        .isEmpty();
  }

  @Test
  @DisplayName("a file is reported with the worst precision any of its functions carries")
  void filePrecisionIsTheWorstFunctionPrecision() {
    JavaTacFrontend frontend = new JavaTacFrontend(new JavaSourceParser());
    String degraded =
        """
        class C {
          void go(int n) {
            switch (n) { case 1: continue; default: break; }
          }
        }
        """;
    TacFrontend.StructuredParseResult degradedResult =
        frontend.parseStructured(degraded, Path.of("C.java"), null);
    assertThat(degradedResult.precision())
        .as("a function that lost semantics makes the whole file degraded")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);

    TacFrontend.StructuredParseResult cleanResult =
        frontend.parseStructured(
            """
            class C {
              void go(int n) {
                for (int i = 0; i < n; i++) { System.out.println(i); }
              }
            }
            """,
            Path.of("C.java"),
            null);
    assertThat(cleanResult.precision())
        .as("a fully lowered file keeps the frontend's stated precision")
        .isEqualTo(IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the plain parse result carries the translator's own problems")
  void plainParseCarriesTheTranslatorsProblems() {
    // A consumer of the plain result must be able to tell that the file was not fully
    // understood. Before this, the plain result only carried the parser's own
    // messages, so a construct the translator could not lower looked like a clean file.
    JavaTacFrontend frontend = new JavaTacFrontend(new JavaSourceParser());
    String broken =
        """
        class C {
          void go() {
            int x = ;
          }
        }
        """;
    TacFrontend.ParseResult plain = frontend.parse(broken, Path.of("C.java"), null);
    TacFrontend.StructuredParseResult structured =
        frontend.parseStructured(broken, Path.of("C.java"), null);
    assertThat(structured.problems())
        .as("the file must have a problem for this test to mean anything")
        .isNotEmpty();
    assertThat(plain.problems())
        .as("the plain result must carry every structured problem")
        .containsAll(
            structured.problems().stream().map(TacProgram.FunctionProblem::toString).toList());
  }
}
