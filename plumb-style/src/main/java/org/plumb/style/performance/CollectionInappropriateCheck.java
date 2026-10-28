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

import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Using {@code Hashtable} / {@code Vector} / {@code Stack} -- prefer {@code java.util.concurrent}
 * alternatives or {@code ArrayList} / {@code HashMap}.
 */
public final class CollectionInappropriateCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-collection-inappropriate", "COLLECTION_INAPPROPRIATE"),
          "using Hashtable/Vector",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.CollectionInappropriateCheck",
          true,
          List.of("ast-performance-collection-inappropriate", "collection-inappropriate"));

  @Override
  public String name() {
    return "collection-inappropriate";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceType t, StyleContext ctx) {
    String name = t.getNameAsString();
    if (!"Hashtable".equals(name) && !"Vector".equals(name) && !"Stack".equals(name)) return;
    int line = t.getBegin().map(p -> p.line).orElse(-1);
    int col = t.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use "
            + name
            + " from java.util.concurrent or ArrayList/HashMap; "
            + name
            + " is legacy/synchronized",
        RuleDef.Severity.LOW,
        name);
  }
}
