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
 * <p>And the polarity has to match the source lane: the true target is the THEN path, and the
 * condition is the source's own comparison.
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

  private static TacFunction pickFunction(Path tmp) throws IOException {
    TacProgram program = analyzeBranchDemo(tmp);
    for (TacFunction fn : program.functions()) {
      if (fn.name().equals("pick")) return fn;
    }
    throw new AssertionError("BranchDemo produced no function named pick");
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
