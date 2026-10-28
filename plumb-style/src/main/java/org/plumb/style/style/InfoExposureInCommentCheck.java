/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * A3: AST check for CWE-615 (Information
 * Exposure by Comment). Fires when a
 * `StringLiteralExpr` contains BOTH `<!--` and a
 * `password = ` / `username = ` / `secret = ` /
 * `key = ` pattern (case-insensitive). The pattern
 * matches HTML comments carrying credentials, which
 * Juliet's CWE-615 emits via
 * `response.getWriter().println("<!--DB username = ..."
 * ...)`.
 *
 * <p>Generic -- any HTML-comment-looking literal with a
 * credential-looking assignment is suspicious.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InfoExposureInCommentCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-info-exposure-in-comment", "INFO_EXPOSURE_IN_COMMENT"),
          "Credentials exposed in HTML comment (CWE-615)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-615"),
          "org.plumb.style.InfoExposureInCommentCheck",
          true,
          List.of("ast-style-info-exposure-in-comment", "info-exposure-in-comment"));

  // JavaParser StringLiteralExpr.getValue() returns
  // raw text WITH \" escapes. So the needles must be
  // written with literal \" + pwd form.
  // (Lesson 997 — see CWE-549 check.)
  private static final Pattern HTML_COMMENT_START = Pattern.compile("<!--|\\\\?<!--");
  private static final Pattern CRED_ASSIGN =
      Pattern.compile(
          "(?i)(password|passwd|secret|credential|token|api[_-]?key|username|apikey)\\s*=\\s*[^\\s<>&]+");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "info-exposure-in-comment";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(StringLiteralExpr n, StyleContext ctx) {
    String v = n.getValue();
    if (v == null || v.length() < 16) return;
    if (!HTML_COMMENT_START.matcher(v).find()) return;
    if (!CRED_ASSIGN.matcher(v).find()) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|inCmt";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Credential-looking value in HTML comment — "
              + "remove the comment or mask the value (CWE-615)",
          RuleDef.Severity.MEDIUM,
          "<!--...credential...-->");
    }
  }
}
