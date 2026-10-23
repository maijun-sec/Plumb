package org.plumb.style.design;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** empty method has no comment explaining why. */
public final class EmptyMethodInAbstractClass2Check implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-uncommented-empty", "UNCOMMENTED_EMPTY"),
          "empty method has no comment explaining why",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyMethodInAbstractClass2Check",
          true,
          List.of("ast-design-uncommented-empty-method", "uncommented-empty")
      );

  @Override
  public String name() {
    return "uncommented-empty";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    if (!body.getStatements().isEmpty()) return;
    // Only flag public methods.
    boolean isPublic =
        n.getModifiers().stream()
            .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.PUBLIC);
    if (!isPublic) return;
    // Check if there's a comment inside the body.
    boolean hasComment = body.getAllContainedComments().size() > 0;
    if (hasComment) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty public method has no comment",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }
}
