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

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Overloaded methods should be declared together. */
public final class OverloadMethodsDeclarationOrderCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-overload-methods-order", "OVERLOAD_METHODS_ORDER"),
          "Overloaded methods should be declared together",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.OverloadMethodsDeclarationOrderCheck",
          true,
          List.of("ast-style-overload-methods-declaration-order", "overload-methods-order"));

  @Override
  public String name() {
    return "overload-methods-order";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isNestedType()) return;
    List<MethodDeclaration> methods = n.getMethods();
    if (methods.size() < 2) return;
    // The rule: a method declaration is OK if its name was
    // not seen earlier in the class. If its name WAS seen
    // earlier, the earlier occurrence's "group" must be
    // contiguous : i.e. no other method was declared between
    // them. If a different method name X appears between
    // two overloads of name Y, the second Y is the violation.
    Set<String> seenSoFar = new HashSet<>();
    // The set of "still open" group names: a name is open from
    // its first declaration until a different method name is
    // declared, at which point the group closes. The name
    // remains in `seenSoFar` permanently, so we can detect
    // when an "old" name reappears.
    Set<String> open = new HashSet<>();
    for (MethodDeclaration md : methods) {
      String name = md.getNameAsString();
      if (open.contains(name)) {
        // Same overload group : extend it.
        continue;
      }
      if (seenSoFar.contains(name)) {
        // Re-entered a closed group. Fire.
        int line = md.getBegin().map(p -> p.line).orElse(-1);
        int col = md.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Method '" + name + "' overload group was split " + "by an unrelated method",
            RuleDef.Severity.LOW,
            name);
      }
      // Start a new group: close current open, open `name`.
      open.clear();
      open.add(name);
      seenSoFar.add(name);
    }
  }
}
