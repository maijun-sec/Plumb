package org.plumb.style.style;

import com.github.javaparser.ast.comments.Comment;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** TODO/FIXME/XXX comment should be resolved. */
public final class TodoCommentCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-todo-comment", "TODO_COMMENT"),
          "TODO/FIXME/XXX comment should be resolved",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.TodoCommentCheck",
          true,
          List.of("ast-style-comment", "todo-comment"));
  private static final Pattern TODO = Pattern.compile("(?i)\\b(TODO|FIXME|XXX)\\b");

  @Override
  public String name() {
    return "todo-comment";
  }

  @Override
  public void init(StyleContext ctx) {}

  public void visit(Comment c, StyleContext ctx) {
    if (TODO.matcher(c.getContent()).find()) {
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      int col = c.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Unresolved TODO / FIXME / XXX comment",
          RuleDef.Severity.LOW,
          c.getContent().trim());
    }
  }
}
