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

import com.github.javaparser.Position;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Anonymous inner class is too long.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
 * | `20` | Maximum allowed lines for an anonymous class |
 */
public final class AnonInnerLengthCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "max", "Maximum allowed lines for an anonymous class", RuleParameter.Type.INT, "20"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-anon-inner-length", "ANON_INNER_LENGTH"),
          "Anonymous inner class is too long",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AnonInnerLengthCheck",
          true,
          List.of("ast-style-inner-length", "anon-inner-length"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "anon-inner-length";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!n.getAnonymousClassBody().isPresent()) return;
    Integer _o = ctx.paramOverride("max", Integer.class);
    int max = _o != null ? _o : 20;
    // Measure between the begin/end of the whole
    // ObjectCreationExpr : that covers the `new T() {... }`
    // span which is what the developer sees.
    Position beg = n.getBegin().orElse(null);
    Position end = n.getEnd().orElse(null);
    if (beg == null || end == null) return;
    int lines = end.line - beg.line + 1;
    if (lines <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Anonymous inner class is " + lines + " lines (max " + max + ")",
        RuleDef.Severity.LOW,
        "anon-class");
  }
}
