package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Class is missing an explicit constructor.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreInterfaceDeclarations` | BOOLEAN | `true` | Skip interface declarations | |
 * `ignoreEnumDeclarations` | BOOLEAN | `true` | Skip enum declarations |
 */
public final class AtLeastOneConstructorCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreInterfaceDeclarations",
        "Skip interface declarations",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "ignoreEnumDeclarations", "Skip enum declarations", RuleParameter.Type.BOOLEAN, "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-at-least-one-constructor", "AT_LEAST_ONE_CONSTRUCTOR"),
          "Class is missing an explicit constructor",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AtLeastOneConstructorCheck",
          true,List.of("ast-naming-at-least-one-constructor", "at-least-one-constructor"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "at-least-one-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (paramBool(ctx, "ignoreInterfaceDeclarations", true) && n.isInterface()) return;
    if (paramBool(ctx, "ignoreEnumDeclarations", true)) {
      if (n.findAncestor(EnumDeclaration.class).isPresent()) return;
    }
    java.util.List<ConstructorDeclaration> ctors = new java.util.ArrayList<>();
    for (com.github.javaparser.ast.body.BodyDeclaration<?> m : n.getMembers()) {
      if (m instanceof ConstructorDeclaration c) ctors.add(c);
    }
    if (n.isAbstract() && ctors.isEmpty()) {
      // Abstract class with no declared ctor : subclasses cannot
      // chain to a common initialization.
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Abstract class '" + n.getNameAsString() + "' has no constructor",
          RuleDef.Severity.LOW,
          n.getNameAsString());
      return;
    }
    if (ctors.isEmpty()) {
      // Non-abstract class with no declared constructors : implicit
      // no-arg ctor exists. Don't fire.
      return;
    }
    boolean hasNoArg = ctors.stream().anyMatch(c -> c.getParameters().isEmpty());
    if (hasNoArg) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '" + n.getNameAsString() + "' has constructors but no no-arg constructor",
        RuleDef.Severity.LOW,
        n.getNameAsString());
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
