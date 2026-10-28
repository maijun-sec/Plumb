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
package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Catching generic Exception or Throwable. */
public final class CatchGenericCheck implements AstChecker {

  private static final java.util.Set<String> GENERIC =
      java.util.Set.of("Exception", "Throwable", "RuntimeException", "Error");

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "catch-generic";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.STYLE_CATCH_GENERIC).orElse(null);
  }

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    String typeName = n.getParameter().getType().asString();
    // Only flag the bare "Exception" / "Throwable" / etc.,
    // not subtypes like "IllegalArgumentException" (which
    // are not generic and are usually intentional).
    int lastDot = typeName.lastIndexOf('.');
    String simple = lastDot < 0 ? typeName : typeName.substring(lastDot + 1);
    if (GENERIC.contains(simple)) {
      int line = n.getRange().map(r -> r.begin.line).orElse(-1);
      int col = n.getRange().map(r -> r.begin.column).orElse(-1);
      this.ctx.report(
          line,
          col,
          this.rule,
          "Catching overly-generic " + simple + "; consider a more specific type",
          RuleDef.Severity.LOW,
          "catch (" + typeName + ")");
    }
  }
}
