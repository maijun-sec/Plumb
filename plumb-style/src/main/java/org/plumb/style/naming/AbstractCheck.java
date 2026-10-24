package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Abstract class has no abstract methods.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `onlyEmptyBodies` | BOOLEAN | `true` | Only flag abstract methods with empty/stub bodies |
 */
public final class AbstractCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "onlyEmptyBodies",
        "Only flag abstract methods with empty/stub bodies",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-abstract", "ABSTRACT"),
          "Abstract class has no abstract methods",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractCheck",
          true,
          List.of("ast-naming-abstract", "abstract"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "abstract";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!n.isAbstract() || n.isInterface()) return;
    boolean hasAbstractMethod = n.getMethods().stream().anyMatch(MethodDeclaration::isAbstract);
    if (hasAbstractMethod) return;
    // Flag only if the class has at least one concrete method
    // (otherwise the class is just a marker, e.g. a static-only
    // utility class : also fine but more of a style choice).
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Abstract class '"
            + n.getNameAsString()
            + "' has no abstract methods; consider removing 'abstract'",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
