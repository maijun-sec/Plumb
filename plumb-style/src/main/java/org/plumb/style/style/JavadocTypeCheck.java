package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public class missing Javadoc. */
public final class JavadocTypeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-javadoctype", "JAVADOCTYPE"),
          "public class missing Javadoc",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.JavadocTypeCheck",
          false,
          List.of(
              "ast-style-javadoc-type-strict",
              "javadoctypecheck")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832

  // baseline)

  @Override
  public String name() {
    return "javadoctype";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (!c.isPublic()) return;
    if (c.getJavadoc().isPresent()) return;
    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Public class " + c.getNameAsString() + " missing Javadoc",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
