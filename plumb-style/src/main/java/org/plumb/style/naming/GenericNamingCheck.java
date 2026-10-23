package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.TypeParameter;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Type parameter name does not match the required pattern.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `format` |
 * STRING | `^[A-Z][0-9]?$` | Regex for type parameter names (default: single uppercase letter) | |
 * `allowMultiChar` | BOOLEAN | `false` | Allow multi-character type parameter names like Key, Value
 * |
 */
public final class GenericNamingCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "format",
        "Regex for type parameter names (default: single uppercase letter)",
        RuleParameter.Type.STRING,
        "^[A-Z][0-9]?$"),
    new RuleParameter(
        "allowMultiChar",
        "Allow multi-character type parameter names like Key, Value",
        RuleParameter.Type.BOOLEAN,
        "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-generic-naming", "GENERIC_NAMING"),
          "Type parameter name does not match the required pattern",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.GenericNamingCheck",
          true,List.of("ast-naming-generic-naming", "generic-naming"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "generic-naming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    checkTypeParams(n.getTypeParameters(), ctx);
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    checkTypeParams(n.getTypeParameters(), ctx);
  }

  private void checkTypeParams(
      com.github.javaparser.ast.NodeList<TypeParameter> tps, StyleContext ctx) {
    if (tps.isEmpty()) return;
    String fmt = paramString(ctx, "format", "^[A-Z][0-9]?$");
    boolean allowMulti = paramBool(ctx, "allowMultiChar", false);
    Pattern p = Pattern.compile(fmt);
    for (TypeParameter tp : tps) {
      String name = tp.getNameAsString();
      if (name.length() > 1 && !allowMulti) {
        int line = tp.getBegin().map(p2 -> p2.line).orElse(-1);
        int col = tp.getBegin().map(p2 -> p2.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Type parameter '" + name + "' should be a single uppercase letter",
            RuleDef.Severity.LOW,
            name);
        continue;
      }
      if (!p.matcher(name).matches()) {
        int line = tp.getBegin().map(p2 -> p2.line).orElse(-1);
        int col = tp.getBegin().map(p2 -> p2.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Type parameter '" + name + "' does not match " + fmt,
            RuleDef.Severity.LOW,
            name);
      }
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

  private static String paramString(StyleContext ctx, String name, String def) {
    String override = ctx.paramOverride(name, String.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.defaultValue();
    }
    return def;
  }
}
