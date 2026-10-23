package org.plumb.style.naming;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class name is too long (limit 30). */
public final class ClassNameTooLongCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-class-name-long", "CLASS_NAME_LONG"),
          "class name is too long (limit 30)",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ClassNameTooLongCheck",
          false,
          List.of("ast-naming-name-too-long", "class-name-long")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  private static final int MAX_LENGTH = 30;

  @Override
  public String name() {
    return "class-name-long";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (name.length() <= MAX_LENGTH) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class name '" + name + "' is " + name.length() + " characters (limit " + MAX_LENGTH + ")",
        RuleDef.Severity.LOW,
        name);
  }
}
