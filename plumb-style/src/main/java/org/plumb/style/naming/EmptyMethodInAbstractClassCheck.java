package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Empty public method has no comment explaining why.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `true` | Skip methods with @Override annotation
 * |
 */
public final class EmptyMethodInAbstractClassCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreMethodsWithOverrideAnnotation",
        "Skip methods with @Override annotation",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-uncommented-empty-method", "UNCOMMENTED_EMPTY_METHOD"),
          "Empty public method has no comment explaining why",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyMethodInAbstractClassCheck",
          true,List.of("ast-naming-uncommented-empty-method-in-abstract-class", "uncommented-empty-method"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "uncommented-empty-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    boolean ignoreOverride = paramBool(ctx, "ignoreMethodsWithOverrideAnnotation", true);
    for (MethodDeclaration method : n.getMethods()) {
      if (method.isAbstract() || method.isNative()) continue;
      if (ignoreOverride && hasOverride(method)) continue;
      if (method.getBody().isEmpty()) continue;
      if (!method.getBody().get().getStatements().isEmpty()) continue;
      int line = method.getBegin().map(p -> p.line).orElse(-1);
      int col = method.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Empty method '" + method.getNameAsString() + "' has no comment explaining why",
          RuleDef.Severity.LOW,
          method.getNameAsString());
    }
  }

  private static boolean hasOverride(MethodDeclaration m) {
    return m.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Override"));
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
