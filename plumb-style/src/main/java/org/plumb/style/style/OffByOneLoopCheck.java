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
/*
 * C12: AST-style CWE-193 (Off-by-one Error) checker.
 *
 * <p>Fires on a comparison with {@code <= array.length}
 * or {@code <= array.length - 1} that is the loop
 * continuation condition of a {@code while},
 * {@code for}, {@code do-while} or {@code forEach}.
 * The typical pattern is
 * {@code while (i <= intArray.length)} which iterates
 * past the last valid index.</p>
 *
 * <p>The check inspects the {@code BinaryExpr} that is
 * the loop's continuation condition. If the operator is
 * {@code <=} and the right-hand side includes
 * {@code .length} or {@code .size()} (and the array /
 * collection is treated as a length, not a bound), we
 * fire.</p>
 *
 * <p><strong>Why AST not taint:</strong> structural
 * presence of {@code <=} on a {@code .length} / {@code
 * .size()} RHS in a loop continuation.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-193: Off-by-one Error.
 *
 * <p>Detects a {@code <=} comparison against a {@code .length} or {@code .size()} expression in a
 * loop continuation condition.
 */
public final class OffByOneLoopCheck implements AstChecker {

  private static final String RULE_ID = "dfa-style-off-by-one-loop";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "OFF_BY_ONE_LOOP"),
          "Loop continuation uses '<= length/size' instead of '<' (CWE-193 off-by-one)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-193"),
          "org.plumb.style.style.OffByOneLoopCheck",
          true);

  @Override
  public String name() {
    return "off-by-one-loop";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    checkExpr(n.getCondition(), ctx);
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    checkExpr(n.getCondition(), ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (n.getCompare().isPresent()) {
      checkExpr(n.getCompare().get(), ctx);
    }
  }

  private void checkExpr(Expression e, StyleContext ctx) {
    if (!(e instanceof BinaryExpr be)) return;
    if (be.getOperator() != BinaryExpr.Operator.LESS_EQUALS) return;
    String rhsStr = be.getRight().toString();
    if (rhsStr.contains(".length") || rhsStr.contains(".size()")) {
      int line = be.getBegin().map(p -> p.line).orElse(-1);
      int col = be.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Loop uses '<= "
              + rhsStr
              + "'; the last iteration may access one-past-end (CWE-193 off-by-one)",
          RuleDef.Severity.MEDIUM,
          "off-by-one-loop");
    }
  }
}
