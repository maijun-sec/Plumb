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
 * Flags an API key hardcoded in a static field (CWE-798 / CWE-259).
 *
 * <p>Flags {@code static final} fields whose name
 * contains a credential-like token and whose value is a
 * string literal of at least 4 chars. Heuristic identical
 * to the DFA half (same credential regex, same minimum
 * length, same placeholder set).</p>
 *
 * <p>The AST version walks each {@link
 * com.github.javaparser.ast.body.FieldDeclaration} and
 * inspects every {@link
 * com.github.javaparser.ast.body.VariableDeclarator} —
 * strictly simpler than the DFA half (which had to
 * recover static initializers from a synthetic
 * {@code <clinit>} function). Both report the same set
 * of cases.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class StaticApiKeyCheck implements AstChecker {

  /**
   * Credential-name regex (case-insensitive). Matches the DFA half's pattern so findings are 1:1.
   */
  private static final Pattern SENSITIVE_NAME =
      Pattern.compile(
          "(?i).*(api[_-]?key|apikey|secret|token|credential|"
              + "private[_-]?key|access[_-]?key|session[_-]?key|"
              + "password|passwd|pwd).*");

  /**
   * Placeholder values that look like a real secret but are documentation / "fill me in" markers.
   * Skipped because they would flood the report with FP.
   */
  private static final Set<String> PLACEHOLDERS =
      Set.of(
          "",
          "todo",
          "fixme",
          "xxx",
          "changeme",
          "your-api-key",
          "your_api_key",
          "your-secret",
          "...",
          "null",
          "<your-key>",
          "<api-key>");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-static-api-key", "STATIC_API_KEY"),
          "Static final field assigned a literal secret/API key " + "(CWE-798 / CWE-259)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-798", "CWE-259"),
          "org.plumb.style.security.StaticApiKeyCheck",
          true,
          List.of("dfa-security-static-api-key", "static-api-key"));

  @Override
  public String name() {
    return "static-api-key";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // Only `static final` fields. The DFA half
    // restricted itself to <clinit>; the AST half
    // is the same set of fields because Java only
    // assigns static-final fields in <clinit>.
    if (!n.isStatic() || !n.isFinal()) return;
    for (VariableDeclarator v : n.getVariables()) {
      String name = v.getNameAsString();
      if (!SENSITIVE_NAME.matcher(name).matches()) continue;
      Expression init = v.getInitializer().orElse(null);
      if (init == null) continue;
      if (!(init instanceof StringLiteralExpr sl)) continue;
      String value = sl.getValue();
      if (value == null || value.length() < 4) continue;
      if (PLACEHOLDERS.contains(value.toLowerCase(Locale.ROOT))) continue;
      int line = v.getBegin().map(p -> p.line).orElse(n.getBegin().map(p -> p.line).orElse(-1));
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Static final '"
              + name
              + "' is assigned a literal "
              + "secret/API key; load from a vault or env var "
              + "(CWE-798 / CWE-259)",
          RuleDef.Severity.HIGH,
          "static-api-key: " + name + " = \"" + truncate(value, 32) + "\"");
    }
  }

  private static String truncate(String s, int n) {
    return s.length() <= n ? s : s.substring(0, n) + "...";
  }
}
