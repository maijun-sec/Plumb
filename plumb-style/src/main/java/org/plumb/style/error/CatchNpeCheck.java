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
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Avoid catching NullPointerException (likely swallows a real bug). */
public final class CatchNpeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-catch-nullpointer-exception", "CATCH_NULLPOINTER_EXCEPTION"),
          "Avoid catching NullPointerException (likely swallows a real bug)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-396"),
          "org.plumb.style.CatchNpeCheck",
          true,
          List.of("ast-error-catch-npe", "error-catch-npe"));

  @Override
  public String name() {
    return "catch-npe-swallow";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    final Type t = n.getParameter().getType();
    if (isNpe(t)) {
      ctx.report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Avoid catching NullPointerException (it usually swallows a real bug)",
          RuleDef.Severity.MEDIUM,
          "NullPointerException");
    }
  }

  private static boolean isNpe(Type t) {
    if (t instanceof ClassOrInterfaceType coi) {
      String name = coi.getNameAsString();
      if ("NullPointerException".equals(name)) return true;
      // Check java.lang.NullPointerException
      if (coi.getNameWithScope().equals("java.lang.NullPointerException")) {
        return true;
      }
    }
    return false;
  }
}
