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
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.stmt.ForStmt;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Replace this magic number with a named constant.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreNumbers` | STRING | `-1,0,1,2` | Comma-separated literal values that are never reported |
 * | `ignoreInForLoop` | BOOLEAN | `true` | Skip literals in for-loop header / update |
 */
public final class MagicNumberCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreNumbers",
        "Comma-separated literal values that are never reported",
        RuleParameter.Type.STRING,
        "-1,0,1,2"),
    new RuleParameter(
        "ignoreInForLoop",
        "Skip literals in for-loop header / update",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-magic-number", "MAGIC_NUMBER"),
          "Replace this magic number with a named constant",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.MagicNumberCheck",
          true,
          List.of("ast-style-magic-number", "magic-number"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "magic-number";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IntegerLiteralExpr n, StyleContext ctx) {
    check(n, n.getValue(), ctx, true);
  }

  @Override
  public void visit(LongLiteralExpr n, StyleContext ctx) {
    check(n, n.getValue(), ctx, true);
  }

  @Override
  public void visit(DoubleLiteralExpr n, StyleContext ctx) {
    // Strip trailing D/F/d/f.
    String v = n.getValue().replaceAll("[dDfFlL]", "");
    check(n, v, ctx, false);
  }

  private void check(
      com.github.javaparser.ast.expr.LiteralExpr n,
      String rawValue,
      StyleContext ctx,
      boolean integer) {
    // Strip L suffix.
    String v = rawValue.replaceAll("[lL]", "");
    // Parse the value.
    java.math.BigDecimal bd;
    try {
      bd = new java.math.BigDecimal(v);
    } catch (NumberFormatException ex) {
      return; // not a parseable number
    }
    Set<String> ignore = parseIgnoreList(paramString(ctx, "ignoreNumbers", "-1,0,1,2"));
    // Compare via canonical string form to avoid 1.0 vs 1 issues.
    String canonical;
    if (integer) {
      canonical = bd.toBigInteger().toString();
    } else {
      canonical = bd.stripTrailingZeros().toPlainString();
    }
    if (ignore.contains(canonical)) return;
    if (paramBool(ctx, "ignoreInForLoop", true) && inForLoop(n)) return;
    if (isInStaticFinalField(n)) return;
    if (isInAnnotation(n)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Magic number '" + v + "' : declare a named constant",
        RuleDef.Severity.LOW,
        v);
  }

  private static java.util.Set<String> parseIgnoreList(String csv) {
    java.util.Set<String> s = new java.util.HashSet<>();
    for (String tok : csv.split(",")) {
      String t = tok.trim();
      if (!t.isEmpty()) s.add(t);
    }
    return s;
  }

  private static boolean inForLoop(com.github.javaparser.ast.Node n) {
    var cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof ForStmt) return true;
      // Don't walk up past the enclosing statement.
      if (cur instanceof com.github.javaparser.ast.stmt.Statement) return false;
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }

  private static boolean isInStaticFinalField(com.github.javaparser.ast.Node n) {
    var cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof VariableDeclarator vd) {
        var fd = cur.getParentNode().orElse(null);
        if (fd instanceof FieldDeclaration f) {
          boolean isStatic =
              f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.STATIC);
          boolean isFinal =
              f.getModifiers().stream().anyMatch(m -> m.getKeyword() == Modifier.Keyword.FINAL);
          return isStatic && isFinal;
        }
        return false;
      }
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }

  private static boolean isInAnnotation(com.github.javaparser.ast.Node n) {
    var cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof com.github.javaparser.ast.expr.AnnotationExpr) return true;
      cur = cur.getParentNode().orElse(null);
    }
    return false;
  }

  private static String paramString(StyleContext ctx, String name, String def) {
    String override = ctx.paramOverride(name, String.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.defaultValue();
    }
    return def;
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
