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

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Logger field should be named LOG/LOGGER/log. */
public final class ProperLoggerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-proper-logger", "PROPER_LOGGER"),
          "Logger field should be named LOG/LOGGER/log",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.ProperLoggerCheck",
          true,
          List.of("ast-style-proper-logger", "proper-logger"));

  @Override
  public String name() {
    return "proper-logger";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!(n.getElementType() instanceof ClassOrInterfaceType t)) return;
    String typeName = t.getNameAsString();
    if (!"Logger".equals(typeName)
        && !"org.slf4j.Logger".equals(typeName)
        && !"java.util.logging.Logger".equals(typeName)) {
      return;
    }
    n.getVariables()
        .forEach(
            v -> {
              String name = v.getNameAsString();
              if ("LOG".equals(name) || "LOGGER".equals(name) || "log".equals(name)) return;
              int line = v.getBegin().map(p -> p.line).orElse(-1);
              int col = v.getBegin().map(p -> p.column).orElse(-1);
              ctx.report(
                  line,
                  col,
                  DEFAULT_RULE,
                  "Logger should be named LOG / LOGGER / log, got '" + name + "'",
                  RuleDef.Severity.LOW,
                  name);
            });
  }
}
