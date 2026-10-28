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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** throw a specific exception, not Exception/Throwable. */
public final class GenericExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-generic-exception", "GENERIC_EXCEPTION"),
          "throw a specific exception, not Exception/Throwable",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.GenericExceptionCheck",
          true,
          List.of("ast-error-generic-exception", "generic-exception"));

  @Override
  public String name() {
    return "generic-exception";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ThrowStmt n, StyleContext ctx) {
    // We can't easily inspect the exception type at the
    // throw site without resolving the expression type;
    // narrow to a method-level scan: a method whose
    // declared `throws` list contains Exception or Throwable.
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    for (Type t : n.getThrownExceptions()) {
      if (isGeneric(t)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method '"
                + n.getNameAsString()
                + "' throws generic '"
                + t.asString()
                + "' : prefer a specific exception type",
            RuleDef.Severity.LOW,
            t.asString());
      }
    }
  }

  private static boolean isGeneric(Type t) {
    if (!(t instanceof ClassOrInterfaceType coi)) return false;
    String name = coi.getNameAsString();
    return "Exception".equals(name)
        || "Throwable".equals(name)
        || "RuntimeException".equals(name)
        || "Error".equals(name)
        || "java.lang.Exception".equals(coi.getNameWithScope())
        || "java.lang.Throwable".equals(coi.getNameWithScope());
  }
}
