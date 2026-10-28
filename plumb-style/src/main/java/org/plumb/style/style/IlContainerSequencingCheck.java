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
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** collection modified during iteration (IL_CONTAINER_SEQUENCING). */
public final class IlContainerSequencingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-il-container-sequencing", "IL_CONTAINER_SEQUENCING"),
          "collection modified during iteration (IL_CONTAINER_SEQUENCING)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-672"),
          "org.plumb.style.IlContainerSequencingCheck",
          true,
          List.of("ast-style-container-sequencing", "il-container-sequencing"));

  private static final Set<String> MUTATORS =
      Set.of("add", "remove", "addAll", "removeAll", "retainAll", "clear");

  @Override
  public String name() {
    return "il-container-sequencing";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    // The iterable must be a method call or name
    // matching the collection.
    if (!(n.getIterable() instanceof MethodCallExpr)
        && !(n.getIterable() instanceof com.github.javaparser.ast.expr.NameExpr)) {
      return;
    }
    String collectionName = n.getIterable().toString();
    // Look for any mutating call in the body.
    for (ExpressionStmt es : n.getBody().findAll(ExpressionStmt.class)) {
      if (!(es.getExpression() instanceof MethodCallExpr)) continue;
      MethodCallExpr mc = (MethodCallExpr) es.getExpression();
      if (!MUTATORS.contains(mc.getNameAsString())) continue;
      if (mc.getScope().isEmpty()) continue;
      String scope = mc.getScope().get().toString();
      // The mutator scope must match the iterable.
      if (scope.contains(collectionName) || collectionName.contains(scope)) {
        int line = es.getBegin().map(p -> p.line).orElse(-1);
        int col = es.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Collection modified during iteration : use Iterator.remove()",
            RuleDef.Severity.MEDIUM,
            mc.getNameAsString());
        return;
      }
    }
  }
}
