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

import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** incompatible exception types used (EC_INCOMPATIBLE_TYPES). */
public final class EcIncompatibleTypesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ec-incompatible", "EC_INCOMPATIBLE"),
          "incompatible exception types used (EC_INCOMPATIBLE_TYPES)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-703"),
          "org.plumb.style.EcIncompatibleTypesCheck",
          true,
          List.of("ast-style-incompatible-types", "ec-incompatible"));

  @Override
  public String name() {
    return "ec-incompatible";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ThrowStmt n, StyleContext ctx) {
    // Walk up to find an enclosing catch block.
    com.github.javaparser.ast.Node parent = n.getParentNode().orElse(null);
    while (parent != null
        && !(parent instanceof com.github.javaparser.ast.stmt.CatchClause)
        && !(parent instanceof com.github.javaparser.ast.body.MethodDeclaration)) {
      parent = parent.getParentNode().orElse(null);
    }
    if (!(parent instanceof com.github.javaparser.ast.stmt.CatchClause cc)) return;
    String caughtName = cc.getParameter().getType().asString();
    // The thrown expression must be an ObjectCreationExpr of
    // an exception type.
    if (!(n.getExpression() instanceof ObjectCreationExpr)) return;
    ObjectCreationExpr oce = (ObjectCreationExpr) n.getExpression();
    if (!(oce.getType() instanceof ClassOrInterfaceType)) return;
    ClassOrInterfaceType t = (ClassOrInterfaceType) oce.getType();
    String thrownName = t.getNameAsString();
    // Skip: thrown is the same as caught.
    if (thrownName.equals(caughtName)) return;
    // Skip: thrown is a subtype by name (FooException
    // caught throws FooRuntimeException).
    if (thrownName.contains(caughtName) || caughtName.contains(thrownName)) return;
    // Skip: throws RuntimeException / Error / Exception
    // (these are explicitly catch-all signals).
    if ("RuntimeException".equals(thrownName)
        || "Error".equals(thrownName)
        || "Exception".equals(thrownName)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Throwing '"
            + thrownName
            + "' from a '"
            + caughtName
            + "' catch block : incompatible types",
        RuleDef.Severity.MEDIUM,
        thrownName);
  }
}
