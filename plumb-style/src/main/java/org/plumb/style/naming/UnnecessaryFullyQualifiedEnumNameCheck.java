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

import com.github.javaparser.ast.expr.FieldAccessExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Unnecessary fully-qualified enum name (should be a simple reference with import).
 *
 * <p>findAll(FieldAccessExpr.class)} before. Note this checker fires on ANY {@code FieldAccessExpr}
 * with a dot -- same caveat as {@code UnnecessaryFullyQualifiedClassNameCheck} — it's a heuristic.
 * The M4 fix in plumb-ai only checks for type references; here we keep the simple-name heuristic
 * since this is purely AST-level.
 */
public final class UnnecessaryFullyQualifiedEnumNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-unnecessary-fqn-enum", "UNNECESSARY_FQN_ENUM"),
          "Unnecessary fully qualified enum name",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryFullyQualifiedEnumNameCheck",
          true,
          List.of("ast-naming-unnecessary-fully-qualified-enum", "unnecessary-fqn-enum"));

  @Override
  public String name() {
    return "unnecessary-fqn-enum";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldAccessExpr fa, StyleContext ctx) {
    String s = fa.toString();
    if (!s.contains(".")) return;
    int line = fa.getBegin().map(p -> p.line).orElse(-1);
    int col = fa.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "Unnecessary fully qualified enum name", RuleDef.Severity.LOW, s);
  }
}
