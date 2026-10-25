/*
 * Suppression-marker comment suppresses a finding ? review and add a reason.
 */
package org.plumb.style.error;

import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class NosonarCommentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-suppression-marker-comment", "SUPPRESSION_MARKER_COMMENT"),
          "Suppression-marker comment suppresses a finding — review and add a reason",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.NosonarCommentCheck",
          true,
          List.of("ast-error-suppression-marker", "suppression-marker-comment"));

  /** Per-file short-circuit: stop scanning once a NOSONAR is found. */
  private boolean found = false;

  @Override
  public String name() {
    return "suppression-marker-comment";
  }

  @Override
  public void init(StyleContext ctx) {
    found = false;
  }

  public void visit(LineComment n, StyleContext ctx) {
    if (found) return;
    if (n.getContent().toUpperCase().contains("NOSONAR")) {
      report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx);
      found = true;
    }
  }

  public void visit(BlockComment n, StyleContext ctx) {
    if (found) return;
    if (n.getContent().toUpperCase().contains("NOSONAR")) {
      report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          ctx);
      found = true;
    }
  }

  @Override
  public void finish(StyleContext ctx) {
    if (found) return;
    String src = ctx.source().unit() == null ? "" : ctx.source().unit().toString();
    int idx = src.indexOf("NOSONAR");
    while (idx >= 0) {
      char prev = idx == 0 ? ' ' : src.charAt(idx - 1);
      char next = idx + 7 >= src.length() ? ' ' : src.charAt(idx + 7);
      boolean isStandalone = !Character.isLetterOrDigit(prev) && !Character.isLetterOrDigit(next);
      if (isStandalone) {
        int line = 1;
        for (int i = 0; i < idx; i++) {
          if (src.charAt(i) == '\n') line++;
        }
        report(line, 0, ctx);
        return;
      }
      idx = src.indexOf("NOSONAR", idx + 1);
    }
  }

  private void report(int line, int col, StyleContext ctx) {
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "NOSONAR comment found — add a justification or remove",
        RuleDef.Severity.LOW,
        "NOSONAR");
  }
}
