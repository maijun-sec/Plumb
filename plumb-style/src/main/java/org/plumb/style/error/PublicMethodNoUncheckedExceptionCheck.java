package org.plumb.style.error;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public method declares unchecked exception in throws. */
public final class PublicMethodNoUncheckedExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-unchecked-throws", "UNCHECKED_THROWS"),
          "public method declares unchecked exception in throws",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PublicMethodNoUncheckedExceptionCheck",
          true,
          List.of("ast-error-public-method-unchecked-throws", "unchecked-throws")
      );

  @Override
  public String name() {
    return "unchecked-throws";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    boolean isPublic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
    if (!isPublic) return;
    for (var t : n.getThrownExceptions()) {
      String typeName = t.toString();
      if (typeName.endsWith("RuntimeException")
          || typeName.endsWith("Error")
          || typeName.endsWith("NullPointerException")
          || typeName.endsWith("IllegalArgumentException")
          || typeName.endsWith("IllegalStateException")
          || typeName.endsWith("IndexOutOfBoundsException")) {
        int line = t.getBegin().map(p -> p.line).orElse(-1);
        int col = t.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Unchecked exception '" + typeName + "' in throws clause",
            RuleDef.Severity.LOW,
            typeName);
      }
    }
  }
}
