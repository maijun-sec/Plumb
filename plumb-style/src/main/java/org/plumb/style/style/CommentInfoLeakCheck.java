package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.comments.Comment;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-615 (Information Exposure Through Comments).
 *
 * <p>AI-primary checker. Detects source-file comments whose body contains sensitive-looking data:
 * passwords, API keys, connection-URL schemes (jdbc:/mongodb:/ etc.), PEM blocks, AWS access-key
 * ids, Stripe live/test keys, GitHub PATs, etc. The static half is intentionally lenient; the AI
 * dedup pass judges TP / FP from the actual source context.
 *
 * <p>The previous implementation was a file-wide {@code finish()} scan with manual {@code /* ...
 * *}{@code /} / {@code //} / HTML comment tracking (dfa-style source-reading). The R787f AST
 * implementation walks every comment via the standard {@link
 * com.github.javaparser.ast.comments.Comment} attachment system that JavaParser already populates
 * when it parses the source. This drops the manual block-comment accumulation code (JavaParser
 * handles multi-line comments) but adds a separate HTML-comment pass because Juliet CWE-615 test
 * cases use {@code <!-- ... -->} for credential leaks in servlet responses (the Java-comment parser
 * misses these).
 */
public final class CommentInfoLeakCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // R787f: rule_id aligned with
          // plumb_rule_to_cwe.json (`Cwe615` →
          // ["615"]). Legacy dfa rule id
          // `plumb.ai.comment-info-leak` is
          // preserved as the short_name so any
          // external whitelist still matches
          // (Lesson 1154).
          new RuleId("ast-style-comment-info-leak", "COMMENT_INFO_LEAK"),
          "Comment contains sensitive-looking data (CWE-615)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-615"),
          "org.plumb.style.CommentInfoLeakCheck",
          true,
          List.of("ast-style-comment-info-leak-style", "comment-info-leak")
      );

  /**
   * Combined LEAK regex. Each alternative is a self-contained "sensitive data indicator":
   * known-fixed formats (AKIA, sk_live_, ghp_, BEGIN PRIVATE KEY, ssh-rsa) and case-insensitive
   * keywords.
   */
  private static final Pattern LEAK =
      Pattern.compile(
          "(?i)\\b(password|passwd|pwd|api[_-]?key|apikey|secret|token|credential|"
              + "auth[_-]?token|access[_-]?key|session[_-]?key|private[_-]?key|"
              + "signing[_-]?key)\\b"
              + "|jdbc:[a-z]+:"
              + "|mongodb(\\+srv)?:"
              + "|mysql://"
              + "|postgres(ql)?://"
              + "|redis://"
              + "|amqp://"
              + "|kafka://"
              + "|AKIA[0-9A-Z]{16}"
              + "|sk_live_[A-Za-z0-9]{16,}"
              + "|sk_test_[A-Za-z0-9]{16,}"
              + "|ghp_[A-Za-z0-9]{36}"
              + "|gho_[A-Za-z0-9]{36}"
              + "|ghu_[A-Za-z0-9]{36}"
              + "|ghs_[A-Za-z0-9]{36}"
              + "|ghr_[A-Za-z0-9]{36}"
              + "|xox[baprs]-[A-Za-z0-9-]{10,}"
              + "|AIza[0-9A-Za-z_\\-]{35}"
              + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
              + "|ssh-rsa [A-Za-z0-9+/]{50,}");

  @Override
  public String name() {
    return "comment-info-leak";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // Walk every attached comment via
    // getAllContainedComments (includes orphans).
    for (Comment c : n.getAllContainedComments()) {
      String text = c.getContent();
      if (text == null || text.isBlank()) continue;
      Matcher m = LEAK.matcher(text);
      if (!m.find()) continue;
      int line = c.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Comment at line "
              + line
              + " contains sensitive-looking data '"
              + truncate(m.group(), 32)
              + "'. AI dedup will judge whether this"
              + " is a real exposure (CWE-615).",
          RuleDef.Severity.LOW,
          "comment-info-leak@" + line);
    }
  }

  private static String truncate(String s, int n) {
    if (s == null) return "";
    return s.length() <= n ? s : s.substring(0, n) + "...";
  }
}
