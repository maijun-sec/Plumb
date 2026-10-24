package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Avoid star imports.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowStatic` | BOOLEAN | `true` | Allow star imports on static members |
 */
public final class AvoidStarImportCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "allowStatic", "Allow star imports on static members", RuleParameter.Type.BOOLEAN, "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-star-import", "AVOID_STAR_IMPORT"),
          "Avoid star imports",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidStarImportCheck",
          false, // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)
          List.of(),
          List.of(PARAMS));

  @Override
  public String name() {
    return "avoid-star-import";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    boolean allowStatic = paramBool(ctx, "allowStatic", true);
    for (ImportDeclaration id : n.getImports()) {
      if (!id.isAsterisk()) continue;
      if (allowStatic && id.isStatic()) continue;
      String name = id.getNameAsString();
      int line = id.getBegin().map(p -> p.line).orElse(-1);
      int col = id.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Star import '" + name + ".*' — use explicit single-type imports",
          RuleDef.Severity.LOW,
          name + ".*");
    }
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
