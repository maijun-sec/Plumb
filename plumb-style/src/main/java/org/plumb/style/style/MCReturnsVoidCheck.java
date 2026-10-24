package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.VoidType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Method returns void but has getter-style name.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `prefix` |
 * STRING | `get` | Getter prefix to check | | `checkAllPublic` | BOOLEAN | `true` | Apply check to
 * all public methods with the prefix |
 */
public final class MCReturnsVoidCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter("prefix", "Getter prefix to check", RuleParameter.Type.STRING, "get"),
    new RuleParameter(
        "checkAllPublic",
        "Apply check to all public methods with the prefix",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mc-returns-void", "MC_RETURNS_VOID"),
          "Method returns void but has getter-style name",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of(),
          "org.plumb.style.MCReturnsVoidCheck",
          true,
          List.of("ast-style-mc-returns-void", "mc-returns-void"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "mc-returns-void";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    String prefix = paramString(ctx, "prefix", "get");
    boolean checkAllPublic = paramBool(ctx, "checkAllPublic", true);
    String name = n.getNameAsString();
    if (!name.startsWith(prefix)) return;
    if (checkAllPublic && !n.isPublic()) return;
    // The return type must be void.
    if (!(n.getType() instanceof VoidType)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '" + name + "' returns void but looks like a getter; did you mean a return type?",
        RuleDef.Severity.HIGH,
        name);
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
