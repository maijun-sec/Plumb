package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Comments should be properly indented. */
public final class CommentsIndentationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-commentsindentation", "COMMENTSINDENTATION"),
          "Comments should be properly indented",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CommentsIndentationCheck",
          true,
          List.of("ast-style-comments-indentation", "commentsindentation")
      );

  @Override
  public String name() {
    return "commentsindentation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (Comment c : n.getAllContainedComments()) {
      String text = c.getContent();
      if (text.startsWith(" ")) {
        int line = c.getBegin().map(p -> p.line).orElse(-1);
        int col = c.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Comments should be properly indented",
            RuleDef.Severity.LOW,
            text);
      }
    }
  }
}
