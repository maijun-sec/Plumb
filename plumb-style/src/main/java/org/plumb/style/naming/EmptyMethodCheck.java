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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Method has an empty body.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreAbstractMethods` | BOOLEAN | `true` | Skip abstract methods (no body by design) | |
 * `ignoreNativeMethods` | BOOLEAN | `true` | Skip native methods (no Java body) | |
 * `ignoreMethodsWithOverrideAnnotation` | BOOLEAN | `false` | Skip methods with @Override
 * annotation | | `ignorePublicMethods` | BOOLEAN | `false` | Skip public methods |
 */
public final class EmptyMethodCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreAbstractMethods",
        "Skip abstract methods (no body by design)",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "ignoreNativeMethods",
        "Skip native methods (no Java body)",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "ignoreMethodsWithOverrideAnnotation",
        "Skip methods with @Override annotation",
        RuleParameter.Type.BOOLEAN,
        "false"),
    new RuleParameter(
        "ignorePublicMethods", "Skip public methods", RuleParameter.Type.BOOLEAN, "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-empty-method", "EMPTY_METHOD"),
          "Method has an empty body",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyMethodCheck",
          true,
          List.of("ast-naming-empty-method", "empty-method"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "empty-method";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    if (isIgnored(n, ctx)) return;
    BlockStmt body = n.getBody().get();
    if (!body.getStatements().isEmpty()) return;
    // Skip if the only "statement" is a comment
    if (body.getAllContainedComments().size() > 0 && body.getStatements().isEmpty()) {
      // Has comments but no statements : that's documented empty
      // body, acceptable per.
      // For plain EmptyMethod, fire anyway.
    }
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '" + n.getNameAsString() + "' has an empty body",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  /**
   * Read parameter overrides from the per-file context, falling back to defaults defined on {@link
   * #PARAMS}.
   */
  private boolean isIgnored(MethodDeclaration n, StyleContext ctx) {
    boolean ignoreAbstract = paramBool(ctx, "ignoreAbstractMethods", true);
    boolean ignoreNative = paramBool(ctx, "ignoreNativeMethods", true);
    boolean ignoreOverride = paramBool(ctx, "ignoreMethodsWithOverrideAnnotation", false);
    boolean ignorePublic = paramBool(ctx, "ignorePublicMethods", false);
    if (ignoreAbstract && n.isAbstract()) return true;
    if (ignoreNative && n.isNative()) return true;
    if (ignoreOverride && hasOverrideAnnotation(n)) return true;
    if (ignorePublic && n.isPublic()) return true;
    return false;
  }

  private static boolean hasOverrideAnnotation(MethodDeclaration n) {
    return n.getAnnotations().stream().anyMatch(a -> a.getNameAsString().equals("Override"));
  }

  private static boolean paramBool(StyleContext ctx, String name, boolean defaultValue) {
    // Real lookup goes through RuleRegistry; for the plumb-style
    // single-pass checker we honor parameter overrides attached to
    // the context if present, otherwise fall back to the static
    // default declared on PARAMS.
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return defaultValue;
  }
}
