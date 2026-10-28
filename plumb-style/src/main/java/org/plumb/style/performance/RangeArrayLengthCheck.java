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
package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array index uses {@code .length} directly -- should be {@code .length - 1} (RANGE_ARRAY_LENGTH).
 */
public final class RangeArrayLengthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-range-array-length", "RANGE_ARRAY_LENGTH"),
          "array index equals length — should be length-1 (RANGE_ARRAY_LENGTH)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-129"),
          "org.plumb.style.RangeArrayLengthCheck",
          true,
          List.of("ast-performance-array-length", "range-array-length"));

  @Override
  public String name() {
    return "range-array-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ArrayAccessExpr arr, StyleContext ctx) {
    if (!(arr.getIndex() instanceof FieldAccessExpr fa)) return;
    if (!"length".equals(fa.getNameAsString())) return;
    int line = arr.getBegin().map(p -> p.line).orElse(-1);
    int col = arr.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array accessed at .length \u2014 should be .length-1",
        RuleDef.Severity.MEDIUM,
        "length");
  }
}
