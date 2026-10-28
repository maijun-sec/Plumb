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
package org.plumb.style.design;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Too many distinct types instantiated in this class.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | | `max` | INT
 * | `20` | Maximum distinct instantiable types per class | | `excludeLocalVariables` | BOOLEAN |
 * `false` | Only count types stored in fields |
 */
public final class AbstractClassCouplingCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "max", "Maximum distinct instantiable types per class", RuleParameter.Type.INT, "20"),
    new RuleParameter(
        "excludeLocalVariables",
        "Only count types stored in fields",
        RuleParameter.Type.BOOLEAN,
        "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-abstract-coupling", "ABSTRACT_COUPLING"),
          "Too many distinct types instantiated in this class",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AbstractClassCouplingCheck",
          true,
          List.of("ast-design-abstract-coupling", "abstract-coupling"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "abstract-coupling";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isNestedType()) return; // don't fire on inner classes
    Integer _o = ctx.paramOverride("max", Integer.class);
    int max = _o != null ? _o : 20;
    Set<String> types = new HashSet<>();
    for (ObjectCreationExpr oc : n.findAll(ObjectCreationExpr.class)) {
      // Skip anonymous classes (they're covered by AnonInnerLengthCheck)
      if (oc.getAnonymousClassBody().isPresent()) continue;
      String typeName = oc.getType().getNameAsString();
      // Skip `new T[0]` (ArrayCreationExpr is a different node,
      // so this won't hit, but be defensive).
      if (typeName.isEmpty()) continue;
      types.add(typeName);
    }
    if (types.size() <= max) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '"
            + n.getNameAsString()
            + "' instantiates "
            + types.size()
            + " distinct types (max "
            + max
            + ")",
        RuleDef.Severity.LOW,
        "coupling");
  }
}
