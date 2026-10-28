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
/*
 * constructor calls overridable method.
 *
 * <p><b>pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(ConstructorDeclaration)} (1 walk) + for each
 * ctor {@code ctor.findAll(MethodCallExpr)} (1 walk per ctor).
 * The new implementation uses {@code visit(MethodCallExpr)}
 * dispatched by {@code AstDispatcher} + a findAncestor check
 * to confirm the call is inside a constructor. Total: 1 walk
 * per file (was 1 + N_ctor walks).</p>
 */
package org.plumb.style.design;

import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ConstructorCallingOverridableCheck implements AstChecker {
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-ctor-calling-overridable", "CTOR_CALLING_OVERRIDABLE"),
          "constructor calls overridable method",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-688"),
          "org.plumb.style.ConstructorCallingOverridableCheck",
          true,
          List.of("ast-design-ctor-calling-overridable", "ctor-calling-overridable"));

  @Override
  public String name() {
    return "ctor-calling-overridable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    // Find the enclosing constructor. If the call is a
    // bare-name call inside a constructor body, the
    // constructor's name and the call's name should match.
    ConstructorDeclaration ctor = mc.findAncestor(ConstructorDeclaration.class).orElse(null);
    if (ctor == null) return;
    String ctorName = ctor.getNameAsString();
    if (!mc.getNameAsString().equals(ctorName)) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    int col = mc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Constructor calls "
            + ctorName
            + "() (explicit this/super ctor chain is fine, but bare name is suspicious)",
        RuleDef.Severity.MEDIUM,
        ctorName);
  }
}
