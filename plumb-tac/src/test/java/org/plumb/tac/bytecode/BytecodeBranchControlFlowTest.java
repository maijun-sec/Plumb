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
package org.plumb.tac.bytecode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacBasicBlock;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

/**
 * The bytecode front end must keep a branch's control flow.
 *
 * <p>Two things used to go missing, and both of them are invisible in the output: the branch's
 * condition and the branch's targets.
 *
 * <p><b>The condition.</b> A Jimple branch tests a comparison INLINE, and the operand converter has
 * no shape for it, so the branch used to carry the sentinel {@code "<unhandled:JLtExpr>"} as its
 * condition. A branch whose condition is a sentinel proves nothing, so the engine had no path
 * conditions at all in bytecode mode.
 *
 * <p><b>The targets.</b> A branch's target is a label, and the CFG builder resolves a label by
 * finding the LABEL instruction that carries the name. The bytecode front end wrote {@code
 * "block_<identityHashCode>"} as the target and never emitted a LABEL with that name, so every
 * branch and every jump ended its block with NO successor: the measured control-flow graph of a
 * whole jar had no edges at all (commons-text 1.15.0: 1539 branches, 0 resolved, 4016 blocks, 0
 * edges). With no edges there are no loops either, so the loop-aware checks never fired in bytecode
 * mode.
 *
 * <p>And the polarity has to be the one the engine can use: the engine continues into the
 * fall-through after a branch, so the branch's condition is the comparison the jump tests FLIPPED,
 * and the fall-through is the true edge. For the demo below -- where javac inverts the source's
 * comparison -- that makes the true edge the THEN path. It is not always so: for {@code x > 0 || y
 * > 0} javac keeps the comparison and jumps to the then path, and then the fall-through is the
 * condition-FALSE path.
 */
class BytecodeBranchControlFlowTest {

  @Test
  void aBranchKeepsItsComparison(@TempDir Path tmp) throws IOException {
    TacFunction fn = pickFunction(tmp);

    TacInstruction branch = branchOf(fn);

    // The condition is a variable the comparison wrote, not a sentinel.
    assertThat(branch.lhs1)
        .as("the condition of a bytecode branch")
        .isInstanceOf(TacOperand.TacVar.class);
    TacOperand.TacVar cond = (TacOperand.TacVar) branch.lhs1;
    assertThat(cond.name())
        .as("the condition of a bytecode branch")
        .doesNotStartWith("<unhandled:");

    // No instruction of the function may reference a sentinel: the operand converter's
    // fallback is a var named "<unhandled:<class>>", and its presence means a shape was
    // dropped.
    for (TacInstruction ins : fn.instructions()) {
      for (TacOperand operand : operandsOf(ins)) {
        if (operand instanceof TacOperand.TacVar var) {
          assertThat(var.name())
              .as("operand of " + ins.kind + " in " + fn.signature())
              .doesNotStartWith("<unhandled:");
        }
      }
    }

    // The comparison is the SOURCE's comparison: `if (x > 0)`, not the negation the
    // bytecode carries (`if (x <= 0) goto else`).
    TacInstruction compare = null;
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.BINOP) continue;
      if (ins.dest instanceof TacOperand.TacVar dest && dest.name().equals(cond.name())) {
        compare = ins;
        break;
      }
    }
    assertThat(compare).as("the comparison the branch tests").isNotNull();
    assertThat(compare.binop).as("the operator of `x > 0`").isEqualTo(BinaryOperator.GT);
  }

  @Test
  void aBranchHasTwoSuccessorsInTheControlFlowGraph(@TempDir Path tmp) throws IOException {
    TacFunction fn = pickFunction(tmp);
    TacInstruction branch = branchOf(fn);

    TacCfg cfg = TacCfgBuilder.build(fn);

    // The labels resolve: a label names the block whose FIRST instruction carries it.
    Map<String, Integer> labelToBlock = new HashMap<>();
    for (TacBasicBlock block : cfg.blocks()) {
      TacInstruction first = fn.instructions().get(block.firstIndex());
      if (first.kind == TacInstruction.Kind.LABEL && first.label != null) {
        labelToBlock.put(first.label, block.id());
      }
    }
    assertThat(labelToBlock)
        .as("the labels the branch names")
        .containsKeys(branch.trueLabel, branch.falseLabel);

    // The branch's block has two successors -- the edge that was missing.
    Integer branchBlock = null;
    for (TacBasicBlock block : cfg.blocks()) {
      if (block.lastIndex() == fn.instructions().indexOf(branch)) {
        branchBlock = block.id();
        break;
      }
    }
    assertThat(branchBlock).as("the block the branch ends").isNotNull();
    assertThat(cfg.succs(branchBlock)).as("the successors of the branch's block").hasSize(2);
  }

  @Test
  void theTrueTargetIsTheThenPath(@TempDir Path tmp) throws IOException {
    TacFunction fn = pickFunction(tmp);
    TacInstruction branch = branchOf(fn);

    TacCfg cfg = TacCfgBuilder.build(fn);
    Map<String, Integer> labelToBlock = new HashMap<>();
    for (TacBasicBlock block : cfg.blocks()) {
      TacInstruction first = fn.instructions().get(block.firstIndex());
      if (first.kind == TacInstruction.Kind.LABEL && first.label != null) {
        labelToBlock.put(first.label, block.id());
      }
    }

    Integer trueBlock = labelToBlock.get(branch.trueLabel);
    Integer falseBlock = labelToBlock.get(branch.falseLabel);
    assertThat(trueBlock).as("the block the true target names").isNotNull();
    assertThat(falseBlock).as("the block the false target names").isNotNull();

    assertThat(returnedConstantOf(fn, cfg, trueBlock))
        .as("the true target is the THEN path (`return 11`)")
        .isEqualTo(11);
    assertThat(returnedConstantOf(fn, cfg, falseBlock))
        .as("the false target is the ELSE path (`return 22`)")
        .isEqualTo(22);
  }

  /** The int a block returns, or null when the block does not return one. */
  private static Integer returnedConstantOf(TacFunction fn, TacCfg cfg, int blockId) {
    TacBasicBlock block = cfg.block(blockId);
    if (block == null) return null;
    for (int index : block.indices()) {
      TacInstruction ins = fn.instructions().get(index);
      if (ins.kind != TacInstruction.Kind.RETURN) continue;
      if (ins.lhs1 instanceof TacOperand.Const c && c.equals(TacOperand.Const.ofInt(11))) {
        return 11;
      }
      if (ins.lhs1 instanceof TacOperand.Const c && c.equals(TacOperand.Const.ofInt(22))) {
        return 22;
      }
    }
    return null;
  }

  private static TacInstruction branchOf(TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.BRANCH) return ins;
    }
    throw new AssertionError("the demo has a branch, but none was found in " + fn.signature());
  }

  private static List<TacOperand> operandsOf(TacInstruction ins) {
    List<TacOperand> out = new java.util.ArrayList<>();
    if (ins.dest != null) out.add(ins.dest);
    if (ins.lhs1 != null) out.add(ins.lhs1);
    if (ins.lhs2 != null) out.add(ins.lhs2);
    if (ins.args != null) out.addAll(ins.args);
    return out;
  }

  /**
   * The true edge is the path the engine's walk continues into -- the fall-through -- and the
   * comparison it carries is the comparison the JUMP tests, flipped.
   *
   * <p>This is the invariant that does not depend on the compiler's layout. javac usually inverts
   * the source's comparison and jumps to the else path, but for {@code x > 0 || y > 0} it keeps the
   * comparison and jumps to the then path. In both cases the comparison that arrives holds on the
   * jump target's path, so the flipped one holds on the fall-through.
   */
  @Test
  void theTrueEdgeIsTheFallThroughAndTheComparisonIsFlipped(@TempDir Path tmp) throws IOException {
    // `if (x > 0)`: javac emits `if (x <= 0) goto else`, so the comparison that arrives is LE
    // and the flipped one is GT.
    assertThat(comparisonsOf(pickFunction(tmp))).containsExactly(BinaryOperator.GT);

    // `if (x > 0 || y > 0)`: the first jump keeps the comparison (GT) and jumps to the THEN
    // path, the second inverts it (LE). Flipped, they are LE and GT.
    TacFunction or = functionNamed(tmp, "pickOr");
    assertThat(comparisonsOf(or)).containsExactly(BinaryOperator.LE, BinaryOperator.GT);

    // In both cases the true edge names the block that comes right after the branch in the
    // instruction order: the fall-through.
    for (TacFunction fn : List.of(pickFunction(tmp), or)) {
      TacCfg cfg = TacCfgBuilder.build(fn);
      Map<String, Integer> labelToBlock = labelToBlock(fn, cfg);
      for (TacInstruction branch : branchesOf(fn)) {
        int index = fn.instructions().indexOf(branch);
        int fallThroughBlock = blockStartingAt(cfg, fn, index + 1);
        assertThat(fallThroughBlock).as("the fall-through of a branch").isNotEqualTo(-1);
        assertThat(labelToBlock.get(branch.trueLabel))
            .as("the true edge of a branch is the fall-through")
            .isEqualTo(fallThroughBlock);
      }
    }
  }

  private static Map<String, Integer> labelToBlock(TacFunction fn, TacCfg cfg) {
    Map<String, Integer> labelToBlock = new HashMap<>();
    for (TacBasicBlock block : cfg.blocks()) {
      TacInstruction first = fn.instructions().get(block.firstIndex());
      if (first.kind == TacInstruction.Kind.LABEL && first.label != null) {
        labelToBlock.put(first.label, block.id());
      }
    }
    return labelToBlock;
  }

  /** The id of the block whose first instruction is at the given index, or -1. */
  private static int blockStartingAt(TacCfg cfg, TacFunction fn, int index) {
    for (TacBasicBlock block : cfg.blocks()) {
      if (block.firstIndex() == index) return block.id();
    }
    return -1;
  }

  /** The comparison operators the branches of a function test, in order. */
  private static List<BinaryOperator> comparisonsOf(TacFunction fn) {
    List<BinaryOperator> operators = new java.util.ArrayList<>();
    for (TacInstruction branch : branchesOf(fn)) {
      if (!(branch.lhs1 instanceof TacOperand.TacVar cond)) continue;
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.BINOP) continue;
        if (ins.dest instanceof TacOperand.TacVar dest && dest.name().equals(cond.name())) {
          operators.add(ins.binop);
          break;
        }
      }
    }
    return operators;
  }

  private static List<TacInstruction> branchesOf(TacFunction fn) {
    List<TacInstruction> branches = new java.util.ArrayList<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind == TacInstruction.Kind.BRANCH) branches.add(ins);
    }
    return branches;
  }

  private static TacFunction pickFunction(Path tmp) throws IOException {
    return functionNamed(tmp, "pick");
  }

  private static TacFunction functionNamed(Path tmp, String name) throws IOException {
    TacProgram program = analyzeBranchDemo(tmp);
    for (TacFunction fn : program.functions()) {
      if (fn.name().equals(name)) return fn;
    }
    throw new AssertionError("BranchDemo produced no function named " + name);
  }

  private static TacProgram analyzeBranchDemo(Path tmp) throws IOException {
    Path src = locateBranchDemo();
    assertThat(src).exists();

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            tmp.toString(),
            "-encoding",
            "UTF-8",
            src.toString());
    assertThat(rc).isEqualTo(0);

    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    return programs.stream()
        .filter(p -> "bytecode.BranchDemo".equals(p.typeName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("BranchDemo produced no TAC program"));
  }

  private static Path locateBranchDemo() {
    Path fromCwd = Path.of("src", "test", "resources", "bytecode", "BranchDemo.java");
    if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
    return Path.of("plumb-tac", "src", "test", "resources", "bytecode", "BranchDemo.java")
        .toAbsolutePath();
  }
}
