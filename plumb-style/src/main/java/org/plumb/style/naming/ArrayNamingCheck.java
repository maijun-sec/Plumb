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
package org.plumb.style.naming;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array variable name should be plural or end with {@code s}.
 *
 * <p>M5 part-3 migration (2026-10-17): the previous implementation used {@code
 * visit(CompilationUnit) + findAll(VariableDeclarator.class)} (1 walk per file). This rewrite uses
 * the per-node-type callback directly; the dispatcher hands each {@code VariableDeclarator} to us
 * with no extra walking.
 */
public final class ArrayNamingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-arraynaming", "ARRAYNAMING"),
          "array name should be plural or end with 's'",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ArrayNamingCheck",
          true,
          List.of("ast-naming-naming-strict", "arraynamingcheck"));

  @Override
  public String name() {
    return "arraynaming";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    String type = v.getTypeAsString();
    if (!type.endsWith("[]") && !type.endsWith("...")) return;
    String name = v.getNameAsString();
    if (name.endsWith("s")
        || name.endsWith("List")
        || name.endsWith("Array")
        || name.endsWith("Set")) {
      return;
    }
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array name should be plural: " + name,
        RuleDef.Severity.LOW,
        name);
  }
}
