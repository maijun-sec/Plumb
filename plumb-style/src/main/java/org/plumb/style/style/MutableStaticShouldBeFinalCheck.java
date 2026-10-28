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

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Mutable static field should be final (mutable global state). */
public final class MutableStaticShouldBeFinalCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-mutable-static-should-be-final", "MUTABLE_STATIC_SHOULD_BE_FINAL"),
          "Mutable static field should be final (mutable global state)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.MALICIOUS_CODE,
          List.of("CWE-500"),
          "org.plumb.style.MutableStaticShouldBeFinalCheck",
          false,
          List.of(
              "ast-style-mutable-static-should-be-final",
              "mutable-static-should-be-final")); // disabled by default: the rule is opt-in

  @Override
  public String name() {
    return "mutable-static-should-be-final";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // Must be static.
    if (!hasModifier(n, Modifier.Keyword.STATIC)) return;
    // Must NOT be final.
    if (hasModifier(n, Modifier.Keyword.FINAL)) return;
    // Skip enums and interfaces (their fields are
    // implicitly final/static).
    // Fire for each variable in the declaration.
    for (VariableDeclarator v : n.getVariables()) {
      ctx.report(
          n.getBegin().map(p -> p.line).orElse(-1),
          n.getBegin().map(p -> p.column).orElse(-1),
          DEFAULT_RULE,
          "Static field '" + v.getNameAsString() + "' should be final (mutable global state)",
          RuleDef.Severity.MEDIUM,
          v.getNameAsString());
    }
  }

  private static boolean hasModifier(FieldDeclaration n, Modifier.Keyword kw) {
    for (Modifier m : n.getModifiers()) {
      if (m.getKeyword() == kw) return true;
    }
    return false;
  }
}
