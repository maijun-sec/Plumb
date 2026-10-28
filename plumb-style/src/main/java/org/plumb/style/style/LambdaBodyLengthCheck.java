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

import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** lambda body has too many statements (, default 3). */
public final class LambdaBodyLengthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-lambda-body-length", "LAMBDA_BODY_LENGTH"),
          "lambda body has too many statements (, default 3)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.LambdaBodyLengthCheck",
          true,
          List.of("ast-style-lambda-body-length", "lambda-body-length"));

  private static final int DEFAULT_MAX = 3;

  @Override
  public String name() {
    return "lambda-body-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  public void visit(LambdaExpr lambda, StyleContext ctx) {
    Statement body = lambda.getBody();
    if (!(body instanceof BlockStmt)) return;
    BlockStmt b = (BlockStmt) body;
    int count = b.getStatements().size();
    if (count > DEFAULT_MAX) {
      int line = lambda.getBegin().map(p -> p.line).orElse(-1);
      int col = lambda.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Lambda body has "
              + count
              + " statements (limit "
              + DEFAULT_MAX
              + ") —extract to a method",
          RuleDef.Severity.LOW,
          "lambda");
    }
  }
}
