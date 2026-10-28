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
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-759 (Use of a One-Way Hash with no Salt).
 *
 * <p>Detects {@code MessageDigest.getInstance("SHA-...")} followed by {@code .digest(...)} with no
 * intervening {@code .update(...)} call. Without a salt (mixed in via {@code .update(salt)}), the
 * digest is vulnerable to rainbow-table attack.
 *
 * <p>Detection: linear AST walk over every {@code Statement}. For each top-level (or any-depth)
 * {@code ExpressionStmt}:
 *
 * <ol>
 *   <li>If the statement contains a {@code MessageDigest.getInstance(...)} call, set a "needs salt"
 *       flag pointing at the statement's line.
 *   <li>If the statement contains a {@code .digest(...)} call (with or without explicit receiver),
 *       and the "needs salt" flag is set from a preceding line, fire CWE-759.
 *   <li>If the statement contains a {@code .update(...)} call, clear the flag.
 * </ol>
 *
 * <p>The per-file linear walk is structurally equivalent to the dfa-side regex (which also did
 * file-line-window matching) but without the "no line-wrap" constraint of regex.
 */
public final class UnsaltedOneWayHashCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-unsalted-one-way-hash", "UNSALTED_ONE_WAY_HASH"),
          "MessageDigest.getInstance(...) followed by .digest(...) with no .update(salt)"
              + " (CWE-759).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-759"),
          "org.plumb.style.UnsaltedOneWayHashCheck",
          true,
          List.of("ast-style-unsalted-one-way-hash-style", "unsalted-one-way-hash"));

  private final Set<String> reported = new HashSet<>();

  /** Per-file: line of the most recent MessageDigest.getInstance call. -1 = none pending. */
  private int getInstanceLine = -1;

  @Override
  public String name() {
    return "unsalted-one-way-hash";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
    getInstanceLine = -1;
  }

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    String name = mc.getNameAsString();
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    if (line < 0) return;
    if ("getInstance".equals(name)) {
      String scopeText = mc.getScope().map(Object::toString).orElse("");
      if (scopeText.contains("MessageDigest") || mc.getScope().isEmpty()) {
        getInstanceLine = line;
      }
      return;
    }
    if ("update".equals(name)) {
      getInstanceLine = -1;
      return;
    }
    if ("digest".equals(name)) {
      int gi = getInstanceLine;
      if (gi < 0) return;
      String key = "digest|" + line + "|" + gi;
      if (reported.add(key)) {
        ctx.report(
            line,
            -1,
            DEFAULT_RULE,
            "MessageDigest.getInstance(...) at line "
                + gi
                + " followed by .digest(...) at line "
                + line
                + " in this file with no .update(salt) call"
                + " between them. The hash is unsalted and"
                + " vulnerable to rainbow-table attack (CWE-759).",
            RuleDef.Severity.MEDIUM,
            "getInstance@line " + gi + " + digest@line " + line);
      }
      getInstanceLine = -1;
    }
  }
}
