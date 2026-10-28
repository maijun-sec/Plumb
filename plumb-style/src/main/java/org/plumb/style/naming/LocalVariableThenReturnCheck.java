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
package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** local variable declared and immediately returned. */
public final class LocalVariableThenReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-local-var-return", "LOCAL_VAR_RETURN"),
          "local variable declared and immediately returned",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LocalVariableThenReturnCheck",
          true,
          List.of("ast-naming-var-then-return", "local-var-return"));

  @Override
  public String name() {
    return "local-var-return";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    if (n.getVariables().size() != 1) return;
    VariableDeclarator v = n.getVariables().get(0);
    if (v.getInitializer().isEmpty()) return;
    // The VariableDeclarationExpr is wrapped in an
    // ExpressionStmt when used as a statement.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    if (!(parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt)) return;
    if (!(parent.getParentNode().orElse(null) instanceof BlockStmt)) return;
    BlockStmt block = (BlockStmt) parent.getParentNode().get();
    var stmts = block.getStatements();
    int idx = stmts.indexOf(parent);
    if (idx < 0 || idx + 1 >= stmts.size()) return;
    if (!(stmts.get(idx + 1) instanceof ReturnStmt)) return;
    ReturnStmt r = (ReturnStmt) stmts.get(idx + 1);
    if (r.getExpression().isEmpty()) return;
    if (!(r.getExpression().get() instanceof NameExpr)) return;
    NameExpr ne = (NameExpr) r.getExpression().get();
    if (!ne.getNameAsString().equals(v.getNameAsString())) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Local '" + v.getNameAsString() + "' declared and immediately returned : inline",
        RuleDef.Severity.LOW,
        v.getNameAsString());
  }
}
