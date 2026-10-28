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
package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Cache {@code Collection.size()} / array length outside the loop body. Calling {@code .size()}
 * inside the loop condition re-evaluates on every iteration; for {@code ArrayList} and similar
 * implementations that's O(1) but it's still wasted work and a smell.
 *
 * <p>S5 audit (2026-10-17) broadened coverage: the previous version only checked the right operand
 * of {@code <} in {@code ForStmt}, missing {@code <=} / {@code >} / {@code >=}, the {@code for (int
 * i = list.size(); ...)} form (size on the left), and the {@code while} counterpart entirely.
 */
public final class ForLoopWithSizeMethodCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-for-size", "FOR_SIZE"),
          "cache list.size() / array.length outside the loop",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.ForLoopWithSizeMethodCheck",
          true,
          List.of("ast-performance-loop-with-size-method", "for-size"));

  @Override
  public String name() {
    return "for-size";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (n.getCompare().isEmpty()) return;
    Expression cmp = n.getCompare().get();
    if (!(cmp instanceof BinaryExpr)) return;
    checkBinaryExpr(
        (BinaryExpr) cmp,
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        ctx);
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    Expression cond = n.getCondition();
    if (!(cond instanceof BinaryExpr)) return;
    checkBinaryExpr(
        (BinaryExpr) cond,
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        ctx);
  }

  /**
   * Check both operands of any ordering comparison. The four ordering operators are the ones where
   * a {@code .size()} call (or {@code .length()} field access -- that one we leave to other checks)
   * is the loop's termination bound.
   */
  private static void checkBinaryExpr(BinaryExpr b, int line, int col, StyleContext ctx) {
    switch (b.getOperator()) {
      case LESS:
      case LESS_EQUALS:
      case GREATER:
      case GREATER_EQUALS:
        break;
      default:
        return;
    }
    if (containsSizeCall(b.getLeft()) || containsSizeCall(b.getRight())) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Cache collection.size() outside the loop — calling .size() in the loop "
              + "condition re-evaluates on every iteration and is a readability smell",
          RuleDef.Severity.LOW,
          "size");
    }
  }

  /** True when {@code e} is (or contains) a {@code .size()} call. */
  private static boolean containsSizeCall(Expression e) {
    if (e instanceof MethodCallExpr m
        && "size".equals(m.getNameAsString())
        && m.getArguments().isEmpty()) {
      return true;
    }
    // Nested cases — e.g. (a + b).size() or map.get(k).size().
    for (MethodCallExpr m : e.findAll(MethodCallExpr.class)) {
      if ("size".equals(m.getNameAsString()) && m.getArguments().isEmpty()) return true;
    }
    return false;
  }
}
