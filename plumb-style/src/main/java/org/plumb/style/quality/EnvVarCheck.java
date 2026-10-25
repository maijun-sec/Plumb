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
