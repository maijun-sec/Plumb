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

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Import is out of canonical order.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `staticImportsSeparate` | BOOLEAN | `true` | Static imports must precede non-static | |
 * `sortWithinGroup` | BOOLEAN | `true` | Sort alphabetically within each group |
 */
public final class ImportControlCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "staticImportsSeparate",
        "Static imports must precede non-static",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "sortWithinGroup",
        "Sort alphabetically within each group",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-import-control", "IMPORT_CONTROL"),
          "Import is out of canonical order",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ImportControlCheck",
          false, // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)
          List.of(),
          List.of(PARAMS));

  @Override
  public String name() {
    return "import-control";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    boolean staticSeparate = paramBool(ctx, "staticImportsSeparate", true);
    boolean sortWithin = paramBool(ctx, "sortWithinGroup", true);
    List<ImportDeclaration> imports = n.getImports();
    // Track per-group last seen import for ordering.
    // Group order: 0=static, 1=java, 2=javax, 3=other.
    int lastGroup = -1;
    String lastName = null;
    for (ImportDeclaration id : imports) {
      String name = id.getNameAsString();
      int group = groupOf(name, id.isStatic());
      if (staticSeparate) {
        if (id.isStatic() && lastGroup > 0) {
          report(ctx, id, "static import after non-static import");
          continue;
        }
        if (!id.isStatic() && lastGroup == 0) {
          // not a problem — after the static block ended, normal
          // non-static imports may begin
        }
      }
      if (group < lastGroup) {
        report(
            ctx,
            id,
            "import group '"
                + groupName(group)
                + "' after group '"
                + groupName(lastGroup)
                + "' — expected canonical order");
      } else if (group == lastGroup
          && sortWithin
          && lastName != null
          && name.compareTo(lastName) < 0) {
        report(
            ctx,
            id,
            "import '" + name + "' is alphabetically before '" + lastName + "' within group");
      } else {
        lastGroup = group;
        lastName = name;
      }
    }
  }

  /** Group index: 0=static, 1=java, 2=javax, 3=other. */
  private static int groupOf(String fqn, boolean isStatic) {
    if (isStatic) return 0;
    if (fqn.startsWith("java.")) return 1;
    if (fqn.startsWith("javax.")) return 2;
    return 3;
  }

  private static String groupName(int g) {
    return switch (g) {
      case 0 -> "static";
      case 1 -> "java";
      case 2 -> "javax";
      default -> "other";
    };
  }

  private void report(StyleContext ctx, ImportDeclaration id, String msg) {
    int line = id.getBegin().map(p -> p.line).orElse(-1);
    int col = id.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, msg, RuleDef.Severity.LOW, id.getNameAsString());
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
