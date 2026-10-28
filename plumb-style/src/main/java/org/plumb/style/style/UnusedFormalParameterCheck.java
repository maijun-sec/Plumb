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
package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** unused private method parameter : remove it. */
public final class UnusedFormalParameterCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unused-formal-parameter", "UNUSED_FORMAL_PARAMETER"),
          "unused private method parameter : remove it",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-1164"),
          "org.plumb.style.UnusedFormalParameterCheck",
          true,
          List.of("ast-style-unused-formal-parameter", "unused-formal-parameter"));

  @Override
  public String name() {
    return "unused-formal-parameter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Only flag private methods.
    boolean isPrivate =
        n.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PRIVATE);
    if (!isPrivate) return;
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    for (Parameter p : n.getParameters()) {
      String name = p.getNameAsString();
      if (body.findAll(NameExpr.class).stream().anyMatch(ne -> ne.getNameAsString().equals(name))) {
        continue;
      }
      int line = p.getBegin().map(b -> b.line).orElse(-1);
      int col = p.getBegin().map(b -> b.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unused private method parameter '" + name + "'",
          RuleDef.Severity.LOW,
          name);
    }
  }
}
