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

import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** duplicated string literal ? extract a constant. */
public final class DuplicatedStringLiteralCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-dup-string", "DUP_STRING"),
          "duplicated string literal — extract a constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DuplicatedStringLiteralCheck",
          true,
          List.of("ast-performance-duplicated-string-literal", "dup-string"));

  private static final int MIN_DUPLICATES = 3;
  private static final int MIN_LENGTH = 5;

  /** Per-file map: literal value → list of (line, col) positions. */
  private final Map<String, List<int[]>> positions = new HashMap<>();

  @Override
  public String name() {
    return "dup-string";
  }

  @Override
  public void init(StyleContext ctx) {
    positions.clear();
  }

  @Override
  public void visit(StringLiteralExpr s, StyleContext ctx) {
    String value = s.getValue();
    if (value.length() < MIN_LENGTH) return;
    int line = s.getBegin().map(p -> p.line).orElse(-1);
    int col = s.getBegin().map(p -> p.column).orElse(-1);
    positions.computeIfAbsent(value, k -> new ArrayList<>()).add(new int[] {line, col});
  }

  @Override
  public void finish(StyleContext ctx) {
    for (Map.Entry<String, List<int[]>> e : positions.entrySet()) {
      List<int[]> locs = e.getValue();
      if (locs.size() < MIN_DUPLICATES) continue;
      for (int i = 1; i < locs.size(); i++) {
        int[] pos = locs.get(i);
        ctx.report(
            pos[0],
            pos[1],
            DEFAULT_RULE,
            "String '" + e.getKey() + "' is duplicated " + locs.size() + " times",
            RuleDef.Severity.LOW,
            e.getKey());
      }
    }
    positions.clear();
  }
}
