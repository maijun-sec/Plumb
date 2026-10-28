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
import com.github.javaparser.ast.body.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Class member is out of canonical order.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreConstructors` | BOOLEAN | `false` | Skip constructor ordering check | | `ignoreModifiers`
 * | BOOLEAN | `false` | Treat static and instance fields as one group |
 */
public final class DeclarationOrderCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreConstructors",
        "Skip constructor ordering check",
        RuleParameter.Type.BOOLEAN,
        "false"),
    new RuleParameter(
        "ignoreModifiers",
        "Treat static and instance fields as one group",
        RuleParameter.Type.BOOLEAN,
        "false"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-declaration-order", "DECLARATION_ORDER"),
          "Class member is out of canonical order",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DeclarationOrderCheck",
          false, // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)
          List.of(),
          List.of(PARAMS));

  @Override
  public String name() {
    return "declaration-order";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    if (n.isNestedType()) return;
    boolean ignoreCtors = paramBool(ctx, "ignoreConstructors", false);
    boolean ignoreMods = paramBool(ctx, "ignoreModifiers", false);
    int highestKind = -1;
    for (BodyDeclaration<?> member : n.getMembers()) {
      int kind = kindOf(member, ignoreCtors, ignoreMods);
      if (kind < 0) continue; // not a relevant member
      if (kind < highestKind) {
        int line = member.getBegin().map(p -> p.line).orElse(-1);
        int col = member.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Member kind '"
                + kindName(kind)
                + "' appears after a member of kind '"
                + kindName(highestKind)
                + "' (canonical order: static, instance, ctor, method)",
            RuleDef.Severity.LOW,
            kindName(kind));
      } else {
        highestKind = kind;
      }
    }
  }

  private static int kindOf(BodyDeclaration<?> m, boolean ignoreCtors, boolean ignoreMods) {
    if (m instanceof FieldDeclaration f) {
      if (ignoreMods) return 0;
      boolean isStatic =
          f.getModifiers().stream().anyMatch(mod -> mod.getKeyword() == Modifier.Keyword.STATIC);
      return isStatic ? 0 : 1; // 0 = static, 1 = instance
    }
    if (m instanceof ConstructorDeclaration) {
      return ignoreCtors ? -1 : 2;
    }
    if (m instanceof MethodDeclaration) {
      return 3;
    }
    if (m instanceof InitializerDeclaration) {
      // Static or instance initializer — treat as 0 or 1.
      InitializerDeclaration id = (InitializerDeclaration) m;
      return id.isStatic() ? 0 : 1;
    }
    if (m instanceof TypeDeclaration<?>) {
      return -1; // nested type — ignore
    }
    return -1;
  }

  private static String kindName(int kind) {
    return switch (kind) {
      case 0 -> "static-field";
      case 1 -> "instance-field";
      case 2 -> "constructor";
      case 3 -> "method";
      default -> "other";
    };
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
