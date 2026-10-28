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

import com.github.javaparser.ast.expr.MarkerAnnotationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code @Deprecated} should declare {@code since} / {@code forRemoval}.
 *
 * <p>Only marker annotations (no arguments) fire; single-member or normal-annotation forms are
 * assumed to carry the required metadata.
 */
public final class DeprecatedAnnotationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-deprecated-annotation", "DEPRECATED_ANNOTATION"),
          "@Deprecated should declare since / forRemoval",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DeprecatedAnnotationCheck",
          true,
          List.of("ast-style-deprecated-annotation", "deprecated-annotation"));

  @Override
  public String name() {
    return "deprecated-annotation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MarkerAnnotationExpr ann, StyleContext ctx) {
    if (!"Deprecated".equals(ann.getNameAsString())) return;
    int line = ann.getBegin().map(p -> p.line).orElse(-1);
    int col = ann.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "@Deprecated should include since=\"...\" or forRemoval=true",
        RuleDef.Severity.LOW,
        "Deprecated");
  }
}
