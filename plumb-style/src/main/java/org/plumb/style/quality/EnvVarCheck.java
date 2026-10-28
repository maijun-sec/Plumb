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
 * AST-style CWE-526 (Info Exposure Env Vars) checker.
 *
 * <p>Fires on calls to {@code System.getenv(...)} which can leak sensitive environment data
 * (passwords, API keys, internal hostnames) into logs / responses / errors.
 *
 */
package org.plumb.style.quality;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class EnvVarCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-quality-env-var", "ENV_VAR"),
          "Use of System.getenv() leaks environment data (CWE-526)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-526"),
          "org.plumb.style.quality.EnvVarCheck",
          true,
          List.of("ast-quality-env-var", "env-var"));

  private final Set<String> fired = new HashSet<>();

  @Override
  public String name() {
    return "env-var";
  }

  @Override
  public void init(StyleContext ctx) {
    fired.clear();
  }

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (!"getenv".equals(m.getNameAsString())) return;
    if (!isSystemScope(m.getScope().orElse(null))) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    if (!fired.add(line + "|" + m.toString())) return;
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        RULE,
        "System.getenv() call — env vars can leak sensitive"
            + " configuration (passwords, API keys,"
            + " internal hostnames) into logs / responses"
            + " (CWE-526).",
        RuleDef.Severity.MEDIUM,
        "getenv");
  }

  private static boolean isSystemScope(com.github.javaparser.ast.expr.Expression scope) {
    if (scope instanceof FieldAccessExpr fa) {
      return "System".equals(fa.getScope().toString()) && "getenv".equals(fa.getNameAsString());
    }
    if (scope instanceof NameExpr ne) {
      return "System".equals(ne.getNameAsString());
    }
    return false;
  }
}
