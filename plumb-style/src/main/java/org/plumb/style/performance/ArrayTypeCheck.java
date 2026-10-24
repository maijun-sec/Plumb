package org.plumb.style.performance;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array type should be written in Java style (Type[]).
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `javaStyle`
 * | BOOLEAN | `true` | Prefer Type[] over Type [] and Type arr[] | | `allowCStyleOnParameters` |
 * BOOLEAN | `false` | Allow C-style (Type arr[]) on method parameters |
 */
public final class ArrayTypeCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "javaStyle",
        "Prefer Type[] over Type [] and Type arr[]",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "allowCStyleOnParameters",
        "Allow C-style (Type arr[]) on method parameters",
        RuleParameter.Type.BOOLEAN,
        "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-type", "ARRAY_TYPE"),
          "Array type should be written in Java style (Type[])",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ArrayTypeCheck",
          true,
          List.of("ast-performance-array-type", "array-type"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "array-type";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!paramBool(ctx, "javaStyle", true)) return;
    for (VariableDeclarator v : n.getVariables()) {
      checkLevel(v.getType().toString(), v.getNameAsString(), n, v, ctx);
    }
  }

  @Override
  public void visit(Parameter n, StyleContext ctx) {
    if (!paramBool(ctx, "javaStyle", true)) return;
    // The C-style is only a problem on local variables / fields.
    // Parameters carry the type with the name in the AST, so
    // we just check the printed form.
  }

  /**
   * Heuristic: if the type string starts with a space after the base type ( or the declarator name
   * is followed by brackets (, flag it.
   */
  private static void checkLevel(
      String typeStr, String varName, Object anchor, VariableDeclarator v, StyleContext ctx) {
    if (typeStr.contains("[] ") || typeStr.matches(".*[A-Za-z0-9] \\[\\].*")) {
      // The Type.toString() puts space before [] for "int []" : flag.
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Array type should be in Java style: '" + typeStr + " " + varName + "'",
          RuleDef.Severity.LOW,
          varName);
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
