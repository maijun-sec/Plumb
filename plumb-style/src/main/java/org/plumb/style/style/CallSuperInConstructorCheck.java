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

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** subclass constructor should call super(...) explicitly. */
public final class CallSuperInConstructorCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-call-super-in-constructor", "CALL_SUPER_IN_CONSTRUCTOR"),
          "subclass constructor should call super(...) explicitly",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of(),
          "org.plumb.style.CallSuperInConstructorCheck",
          true,
          List.of("ast-style-super-in-constructor", "call-super-in-constructor"));

  @Override
  public String name() {
    return "call-super-in-constructor";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    // If the class has no extends, skip : there's no
    // super to call.
    if (n.getParentNode().isEmpty()) return;
    var parent = n.getParentNode().get();
    if (parent instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) {
      com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cls =
          (com.github.javaparser.ast.body.ClassOrInterfaceDeclaration) parent;
      if (cls.getExtendedTypes().isEmpty()) return;
    }
    // getBody() returns BlockStmt directly (not Optional).
    var stmts = n.getBody().getStatements();
    if (stmts.isEmpty()) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Constructor should call super(...) or this(...) explicitly",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
      return;
    }
    Statement first = stmts.get(0);
    if (!(first instanceof ExplicitConstructorInvocationStmt)) {
      int line = first.getBegin().map(p -> p.line).orElse(-1);
      int col = first.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "First statement of constructor should be super(...) or this(...)",
          RuleDef.Severity.MEDIUM,
          n.getNameAsString());
    }
  }
}
