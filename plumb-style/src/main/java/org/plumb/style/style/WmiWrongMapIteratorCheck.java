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
import com.github.javaparser.ast.stmt.ForEachStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** "iterate keySetget(key) : use entrySet (WMI_WRONG_MAP_ITERATOR)". */
public final class WmiWrongMapIteratorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-wmi-wrong-map-iter", "WMI_WRONG_MAP_ITER"),
          "iterate keySet + get(key) : use entrySet (WMI_WRONG_MAP_ITERATOR)",
          RuleDef.Severity.LOW,
          RuleDef.Type.PERFORMANCE,
          List.of(),
          "org.plumb.style.WmiWrongMapIteratorCheck",
          true,
          List.of("ast-style-wrong-map-iterator", "wmi-wrong-map-iter"));

  @Override
  public String name() {
    return "wmi-wrong-map-iter";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    // The iterable expression must be a method call
    // on a `keySet()`.
    if (!(n.getIterable() instanceof MethodCallExpr mc)) return;
    if (!"keySet".equals(mc.getNameAsString())) return;
    if (mc.getScope().isEmpty()) return;
    String mapName = mc.getScope().get().toString();
    // The body must contain a call to `get(...)` on
    // the same map.
    for (MethodCallExpr inner : n.getBody().findAll(MethodCallExpr.class)) {
      if (!"get".equals(inner.getNameAsString())) continue;
      if (inner.getScope().isEmpty()) continue;
      if (mapName.equals(inner.getScope().get().toString())) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Iterating keySet + get : use entrySet",
            RuleDef.Severity.LOW,
            "keySet");
        return;
      }
    }
  }
}
