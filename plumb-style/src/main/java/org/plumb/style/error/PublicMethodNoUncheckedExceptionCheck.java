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

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** public method declares unchecked exception in throws. */
public final class PublicMethodNoUncheckedExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-unchecked-throws", "UNCHECKED_THROWS"),
          "public method declares unchecked exception in throws",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PublicMethodNoUncheckedExceptionCheck",
          true,
          List.of("ast-error-public-method-unchecked-throws", "unchecked-throws"));

  @Override
  public String name() {
    return "unchecked-throws";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    boolean isPublic =
        n.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PUBLIC);
    if (!isPublic) return;
    for (var t : n.getThrownExceptions()) {
      String typeName = t.toString();
      if (typeName.endsWith("RuntimeException")
          || typeName.endsWith("Error")
          || typeName.endsWith("NullPointerException")
          || typeName.endsWith("IllegalArgumentException")
          || typeName.endsWith("IllegalStateException")
          || typeName.endsWith("IndexOutOfBoundsException")) {
        int line = t.getBegin().map(p -> p.line).orElse(-1);
        int col = t.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Unchecked exception '" + typeName + "' in throws clause",
            RuleDef.Severity.LOW,
            typeName);
      }
    }
  }
}
