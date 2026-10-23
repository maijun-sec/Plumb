package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Replace System.out.println with a logger.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `allowInMain` | BOOLEAN | `true` | Skip the main() method | | `allowInTest` | BOOLEAN | `true` |
 * Skip methods whose name contains 'test' | | `includeErr` | BOOLEAN | `false` | Also check
 * System.err.println/print |
 */
public final class SystemOutPrintlnCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter("allowInMain", "Skip the main() method", RuleParameter.Type.BOOLEAN, "true"),
    new RuleParameter(
        "allowInTest",
        "Skip methods whose name contains 'test'",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "includeErr", "Also check System.err.println/print", RuleParameter.Type.BOOLEAN, "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-system-out-println", "SYSTEM_OUT_PRINTLN"),
          "Replace System.out.println with a logger",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SystemOutPrintlnCheck",
          false, // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)
          List.of(),
          List.of(PARAMS));

  @Override
  public String name() {
    return "system-out-println";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (!name.equals("println") && !name.equals("print")) return;
    if (!isSystemOutOrErr(n, includeErr)) return;
    if (isInExemptMethod(n, ctx)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use a logger instead of System.out." + name,
        RuleDef.Severity.LOW,
        "System.out." + name);
  }

  private boolean includeErr;

  private boolean isSystemOutOrErr(MethodCallExpr n, boolean includeErr) {
    if (!n.getScope().isPresent()) return false;
    if (!(n.getScope().get() instanceof FieldAccessExpr fa)) return false;
    if (!fa.getNameAsString().equals("out")) return false;
    if (!(fa.getScope() instanceof NameExpr sys)) return false;
    if (!sys.getNameAsString().equals("System")) return false;
    return true;
  }

  private boolean isInExemptMethod(MethodCallExpr n, StyleContext ctx) {
    // Walk up to enclosing MethodDeclaration.
    var cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof MethodDeclaration md) {
        String mname = md.getNameAsString();
        if (paramBool(ctx, "allowInMain", true) && mname.equals("main")) {
          return true;
        }
        if (paramBool(ctx, "allowInTest", true) && (mname.toLowerCase().contains("test"))) {
          return true;
        }
        return false;
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
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
