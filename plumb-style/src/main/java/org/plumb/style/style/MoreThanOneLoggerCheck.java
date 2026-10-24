package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** class has more than one Logger field. */
public final class MoreThanOneLoggerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-more-than-one-logger", "MORE_THAN_ONE_LOGGER"),
          "class has more than one Logger field",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.MoreThanOneLoggerCheck",
          true,
          List.of("ast-style-than-one-logger", "more-than-one-logger"));

  @Override
  public String name() {
    return "more-than-one-logger";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    long count =
        n.getMembers().stream()
            .filter(m -> m instanceof FieldDeclaration)
            .map(m -> (FieldDeclaration) m)
            .filter(
                f ->
                    f.getElementType() instanceof ClassOrInterfaceType t
                        && ("Logger".equals(t.getNameAsString())
                            || "org.slf4j.Logger".equals(t.getNameAsString())
                            || "java.util.logging.Logger".equals(t.getNameAsString())))
            .count();
    if (count <= 1) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class has " + count + " Logger fields",
        RuleDef.Severity.MEDIUM,
        n.getNameAsString());
  }
}
