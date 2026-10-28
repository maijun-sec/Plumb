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
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Member of a @Deprecated class should also be @Deprecated.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignorePrivateMethods` | BOOLEAN | `true` | Skip private methods of @Deprecated classes | |
 * `ignoreOverriddenMethods` | BOOLEAN | `true` | Skip methods that are overrides |
 */
public final class MissingDeprecatedCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignorePrivateMethods",
        "Skip private methods of @Deprecated classes",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "ignoreOverriddenMethods",
        "Skip methods that are overrides",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-missing-deprecated", "MISSING_DEPRECATED"),
          "Member of a @Deprecated class should also be @Deprecated",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-477"),
          "org.plumb.style.MissingDeprecatedCheck",
          true,
          List.of("ast-style-missing-deprecated", "missing-deprecated"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "missing-deprecated";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (!hasDeprecatedAnnotation(n.getAnnotations())) return;
    if (n.isNestedType()) return; // only top-level
    boolean ignorePrivate = paramBool(ctx, "ignorePrivateMethods", true);
    boolean ignoreOverride = paramBool(ctx, "ignoreOverriddenMethods", true);
    for (MethodDeclaration md : n.getMethods()) {
      if (ignorePrivate
          && md.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.PRIVATE)) {
        continue;
      }
      if (ignoreOverride
          && md.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Override"))) {
        continue;
      }
      if (hasDeprecatedAnnotation(md.getAnnotations())) continue;
      int line = md.getBegin().map(p -> p.line).orElse(-1);
      int col = md.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '"
              + md.getNameAsString()
              + "' in @Deprecated class '"
              + n.getNameAsString()
              + "' should also be @Deprecated",
          RuleDef.Severity.MEDIUM,
          md.getNameAsString());
    }
  }

  private static boolean hasDeprecatedAnnotation(List<AnnotationExpr> anns) {
    for (AnnotationExpr a : anns) {
      if (a.getNameAsString().equals("Deprecated")) return true;
    }
    return false;
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
