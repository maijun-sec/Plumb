package org.plumb.style.naming;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Unnecessary fully-qualified enum name (should be a simple reference with import).
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(FieldAccessExpr.class)} before. Note this checker fires on
 * ANY {@code FieldAccessExpr} with a dot ? same caveat as {@code UnnecessaryFullyQualifiedClassNameCheck}
 * — it's a heuristic. The M4 fix in plumb-ai only checks for type references; here we keep
 * the simple-name heuristic since this is purely AST-level.
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
          List.of("ast-naming-unnecessary-fully-qualified-enum", "unnecessary-fqn-enum")
      );

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
        line,
        col,
        DEFAULT_RULE,
        "Unnecessary fully qualified enum name",
        RuleDef.Severity.LOW,
        s);
  }
}