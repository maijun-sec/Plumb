package org.plumb.style.style;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** explicit empty constructor is unnecessary. */
public final class EmptyConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-constructor", "EMPTY_CONSTRUCTOR"),
          "explicit empty constructor is unnecessary",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyConstructorCheck",
          true,
          List.of("ast-style-constructor-empty", "empty-constructor"));

  @Override
  public String name() {
    return "empty-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    BlockStmt body = n.getBody();
    if (body == null) return;
    if (body.getStatements().isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unnecessary empty constructor: " + n.getNameAsString(),
          RuleDef.Severity.LOW,
          n.getNameAsString());
    }
  }
}
