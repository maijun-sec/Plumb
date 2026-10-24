/*
 * R725-C1: AST-style CWE-546 (Suspicious Comment) checker.
 */
package org.plumb.style.style;

// R-AST-FIX-2026-10-18 (Pass A): migrated from walk-inside-CU-visit to per-type visit.
// Note: this checker needs cross-cutting enumeration (getAllContainedComments
// + getOrphanComments). The Comment / LineComment / BlockComment types are
// not in the AstChecker interface, so per-type visit wouldn't be dispatched.
// We keep visit(CompilationUnit) as a legitimate cross-file scan, justified
// by the orphan-comment requirement. Add a TODO for future dispatcher
// extension to support per-Comment dispatch.

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-546: Suspicious Comment. */
public final class SuspiciousCommentCheck implements AstChecker {

  private static final List<String> SUSPICIOUS_KEYWORDS =
      List.of("BUG", "FIXME", "HACK", "LATER", "TODO");

  private static final Pattern SUSPICIOUS_RE;

  static {
    StringBuilder sb = new StringBuilder("^\\s*(?:");
    for (int i = 0; i < SUSPICIOUS_KEYWORDS.size(); i++) {
      if (i > 0) sb.append('|');
      sb.append(Matcher.quoteReplacement(SUSPICIOUS_KEYWORDS.get(i)));
    }
    sb.append(")\\b");
    SUSPICIOUS_RE = Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE);
  }

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-suspicious-comment", "SUSPICIOUS_COMMENT"),
          "Suspicious comment indicating unfinished or buggy code (CWE-546)",
          RuleDef.Severity.INFO,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-546"),
          "org.plumb.style.style.SuspiciousCommentCheck",
          true,
          List.of("ast-style-suspicious-comment-style", "suspicious-comment"));

  @Override
  public String name() {
    return "suspicious-comment";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    for (Comment c : n.getAllContainedComments()) {
      if (c instanceof LineComment lc) {
        checkComment(lc.getContent(), lc.getBegin().map(p -> p.line).orElse(-1), "line", ctx);
      } else if (c instanceof BlockComment bc) {
        checkComment(bc.getContent(), bc.getBegin().map(p -> p.line).orElse(-1), "block", ctx);
      }
    }
    for (Comment c : n.getOrphanComments()) {
      if (c instanceof LineComment lc) {
        checkComment(lc.getContent(), lc.getBegin().map(p -> p.line).orElse(-1), "line", ctx);
      } else if (c instanceof BlockComment bc) {
        checkComment(bc.getContent(), bc.getBegin().map(p -> p.line).orElse(-1), "block", ctx);
      }
    }
  }

  private void checkComment(String content, int line, String kind, StyleContext ctx) {
    if (content == null) return;
    Matcher m = SUSPICIOUS_RE.matcher(content);
    if (!m.find()) return;
    String kw = m.group().trim().toUpperCase();
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "Suspicious `" + kw + "` " + kind + " comment indicates unfinished or buggy code (CWE-546)",
        RuleDef.Severity.LOW,
        kw);
  }
}
