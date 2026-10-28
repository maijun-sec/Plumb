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

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import java.util.List;
import java.util.regex.Pattern;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Package name should be all lowercase.
 *
 * <p>: per-node-type callback already; this checker was already in modern form. Kept {@code
 * visit(CompilationUnit)} because {@code PackageDeclaration} is visited once per file at the top
 * level (the dispatcher hands the {@code CompilationUnit} directly; we don't need {@code findAll}
 * here).
 */
public final class PackageCaseCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-package-case", "PACKAGE_CASE"),
          "package name should be all lowercase",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.PackageCaseCheck",
          false,
          List.of(
              "ast-naming-package-case",
              "package-case")); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832
  // Disabled by default: this rule reports a style preference rather than a defect.

  private static final Pattern LOWERCASE =
      Pattern.compile("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*$");

  @Override
  public String name() {
    return "package-case";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    if (n.getPackageDeclaration().isEmpty()) return;
    PackageDeclaration pkg = n.getPackageDeclaration().get();
    String name = pkg.getNameAsString();
    if (LOWERCASE.matcher(name).matches()) return;
    int line = pkg.getBegin().map(p -> p.line).orElse(-1);
    int col = pkg.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Package name '" + name + "' should be all lowercase",
        RuleDef.Severity.LOW,
        name);
  }
}
