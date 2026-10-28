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

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant field initializer (already default value). */
public final class RedundantFieldInitializerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-redundant-init", "REDUNDANT_INIT"),
          "redundant field initializer (already default value)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantFieldInitializerCheck",
          false,
          List.of(
              "ast-style-redundant-field-initializer",
              "redundant-init")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // Disabled by default: this rule reports a style preference rather than a defect.

  @Override
  public String name() {
    return "redundant-init";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    for (VariableDeclarator v : n.getVariables()) {
      if (v.getInitializer().isEmpty()) continue;
      Expression init = v.getInitializer().get();
      String typeName = n.getElementType().toString();
      if (isDefaultValue(init, typeName)) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field '" + v.getNameAsString() + "' is initialized to its default value",
            RuleDef.Severity.LOW,
            v.getNameAsString());
      }
    }
  }

  private static boolean isDefaultValue(Expression e, String type) {
    if (e instanceof NullLiteralExpr) {
      return !"int".equals(type)
          && !"boolean".equals(type)
          && !"char".equals(type)
          && !"byte".equals(type)
          && !"short".equals(type)
          && !"long".equals(type)
          && !"float".equals(type)
          && !"double".equals(type);
    }
    if (e instanceof BooleanLiteralExpr b) {
      return "boolean".equals(type) && !b.getValue();
    }
    if (e instanceof IntegerLiteralExpr i) {
      return ("int".equals(type) || "byte".equals(type) || "short".equals(type))
          && "0".equals(i.getValue());
    }
    if (e instanceof LongLiteralExpr l) {
      return "long".equals(type)
          && ("0".equals(l.getValue().replaceAll("[lL]$", "")) || "0L".equals(l.getValue()));
    }
    if (e instanceof DoubleLiteralExpr d) {
      return ("double".equals(type) || "float".equals(type)) && (d.asDouble() == 0.0);
    }
    if (e instanceof CharLiteralExpr c) {
      return "char".equals(type) && c.getValue().length() == 1 && c.getValue().charAt(0) == '\0';
    }
    return false;
  }
}
