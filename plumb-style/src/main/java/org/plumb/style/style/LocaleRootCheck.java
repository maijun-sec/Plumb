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
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** locale-sensitive API without explicit Locale. */
public final class LocaleRootCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-locale-root", "LOCALE_ROOT"),
          "locale-sensitive API without explicit Locale",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-758"),
          "org.plumb.style.LocaleRootCheck",
          true,
          List.of("ast-style-locale-root", "locale-root"));

  private static final Set<String> METHODS =
      Set.of(
          "toLowerCase",
          "toUpperCase",
          "format",
          "getDisplayName",
          "getDisplayLanguage",
          "getDisplayCountry",
          "getDisplayVariant");

  @Override
  public String name() {
    return "locale-root";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!METHODS.contains(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    // Narrow: only String.toLowerCase / toUpperCase
    // with no argument. The format methods are
    // overloaded; we'd need a heuristic for the
    // call site.
    String name = n.getNameAsString();
    if (!"toLowerCase".equals(name) && !"toUpperCase".equals(name)) return;
    if (n.getArguments().size() != 0) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "String." + name + "() without Locale : pass Locale.ROOT for portability",
        RuleDef.Severity.LOW,
        name);
  }
}
