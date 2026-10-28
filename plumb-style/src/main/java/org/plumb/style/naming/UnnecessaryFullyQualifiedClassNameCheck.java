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

import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Use an {@code import} instead of writing a fully-qualified class name inline.
 *
 * <p>S6 audit (2026-10-17) fixed the false-positive flood: the previous implementation fired on
 * every {@code NameExpr} containing a dot, which meant every field access with a package-like
 * prefix ({@code this.foo.bar}, {@code outer.inner.field}, {@code MyClass.STATIC_FIELD}) was
 * flagged as "should use import". The corrected version resolves the {@code NameExpr} and only
 * fires when the resolution result is a class / interface type -- never on field accesses.
 */
public final class UnnecessaryFullyQualifiedClassNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-unnecessary-fqn", "UNNECESSARY_FQN"),
          "avoid fully qualified class name \u2014 use import",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.UnnecessaryFullyQualifiedClassNameCheck",
          true,
          List.of("ast-naming-unnecessary-fully-qualified-name", "unnecessary-fqn"));

  @Override
  public String name() {
    return "unnecessary-fqn";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(NameExpr n, StyleContext ctx) {
    if (!n.getNameAsString().contains(".")) return;
    // Skip the import declaration itself.
    if (n.getParentNode().isPresent()
        && n.getParentNode().get() instanceof com.github.javaparser.ast.ImportDeclaration) {
      return;
    }
    // S6: resolve the symbol. Only fire when it is a
    // class / interface declaration; field / variable
    // accesses (this.foo.bar, outer.inner.field) are
    // legitimate and must NOT be flagged.
    ResolvedDeclaration decl = ctx.resolveDecl(n);
    if (decl == null) {
      // Couldn't resolve — be conservative and skip rather
      // than mis-fire on field accesses.
      return;
    }
    if (!decl.isType()) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use a short class name and an import \u2014 not " + n.getNameAsString(),
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  @Override
  public void visit(com.github.javaparser.ast.type.ClassOrInterfaceType n, StyleContext ctx) {
    // A fully qualified type used as a type reference
    // (e.g. `java.util.List<String>` as a return type).
    String fullName = n.asString();
    if (!fullName.contains(".")) return;
    // Top-level FQN, not part of an import.
    if (n.getParentNode().isPresent()
        && n.getParentNode().get() instanceof com.github.javaparser.ast.ImportDeclaration) {
      return;
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use a short class name and an import \u2014 not " + fullName,
        RuleDef.Severity.LOW,
        fullName);
  }
}
