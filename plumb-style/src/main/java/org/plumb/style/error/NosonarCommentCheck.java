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
 * Suppression-marker comment suppresses a finding -- review and add a reason.
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
