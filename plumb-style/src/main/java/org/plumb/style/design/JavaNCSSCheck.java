package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Unit has too many statements (NCSS).
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `methodMaximum` | INT | `60` | Maximum number of statements in a method | | `classMaximum` | INT
 * | `1500` | Maximum number of statements in a class | | `constructorMaximum` | INT | `80` |
 * Maximum number of statements in a constructor |
 */
public final class JavaNCSSCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "methodMaximum", "Maximum number of statements in a method", RuleParameter.Type.INT, "60"),
    new RuleParameter(
        "classMaximum", "Maximum number of statements in a class", RuleParameter.Type.INT, "1500"),
    new RuleParameter(
        "constructorMaximum",
        "Maximum number of statements in a constructor",
        RuleParameter.Type.INT,
        "80"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-java-ncss", "JAVA_NCSS"),
          "Unit has too many statements (NCSS)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JavaNCSSCheck",
          true,
          List.of("ast-design-java-ncss", "java-ncss"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "java-ncss";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    Integer _o = ctx.paramOverride("classMaximum", Integer.class);
    int max = _o != null ? _o : 1500;
    int count = n.findAll(com.github.javaparser.ast.stmt.Statement.class).size();
    if (count <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '" + n.getNameAsString() + "' has " + count + " statements (max " + max + ")",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    if (n.getBody().get().getStatements().isEmpty()) return;
    Integer _o = ctx.paramOverride("methodMaximum", Integer.class);
    int max = _o != null ? _o : 60;
    int count = n.getBody().get().findAll(com.github.javaparser.ast.stmt.Statement.class).size();
    if (count <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '" + n.getNameAsString() + "' has " + count + " statements (max " + max + ")",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    if (n.getBody().getStatements().isEmpty()) return;
    Integer _o = ctx.paramOverride("constructorMaximum", Integer.class);
    int max = _o != null ? _o : 80;
    int count = n.getBody().findAll(com.github.javaparser.ast.stmt.Statement.class).size();
    if (count <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Constructor '" + n.getNameAsString() + "' has " + count + " statements (max " + max + ")",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
