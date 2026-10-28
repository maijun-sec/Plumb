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
/*
 * CORS misconfiguration (CWE-942).
 */
package org.plumb.style.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import java.util.List;
import java.util.Locale;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-942: permissive CORS configuration (allowedOrigins = "*"). */
public final class CorsMisconfigurationCheck implements AstChecker {

  private static final List<String> CORS_METHODS =
      List.of(
          "setAllowedOrigins", "addAllowedOrigin",
          "addAllowedOrigins", "setAllowedOrigin");

  private static final List<String> CORS_ANNOTATIONS = List.of("CrossOrigin");

  private static final List<String> ORIGIN_MEMBER_NAMES = List.of("origins", "allowedorigins");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-cors-misconfig", "CORS_MISCONFIG"),
          "CORS allowedOrigins='*' disables same-origin policy; "
              + "list specific trusted origins instead (CWE-942)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-942"),
          "org.plumb.style.security.CorsMisconfigurationCheck",
          true,
          List.of("dfa-security-cors-misconfig", "cors-misconfig"));

  @Override
  public String name() {
    return "cors-misconfig";
  }

  @Override
  public void init(StyleContext ctx) {}

  // ─── Imperative form ───

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String name = n.getNameAsString();
    boolean matched = CORS_METHODS.contains(name);
    if (!matched) {
      if (name.endsWith("allowedOrigins") || name.endsWith("allowedOrigin")) {
        matched = true;
      }
    }
    if (!matched) return;
    if (!containsWildcardOrigin(n.getArguments())) return;
    emit(n, ctx, name + "(*)");
  }

  private static boolean containsWildcardOrigin(List<Expression> args) {
    for (Expression a : args) {
      if (isWildcardLiteral(a)) return true;
      if (a instanceof MethodCallExpr mc
          && mc.getArguments().size() == 1
          && isWildcardLiteral(mc.getArguments().get(0))) {
        return true;
      }
      if (a instanceof ArrayInitializerExpr ave
          && ave.getValues().size() == 1
          && isWildcardLiteral(ave.getValues().get(0))) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWildcardLiteral(Expression e) {
    if (e == null) return false;
    String s = e.toString().trim();
    if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
      s = s.substring(1, s.length() - 1);
    }
    return "*".equals(s);
  }

  // ─── Annotation form ───

  public void visit(NormalAnnotationExpr ann, StyleContext ctx) {
    if (!isCorsAnnotation(ann.getName())) return;
    if (!hasWildcardMember(ann.getPairs())) return;
    emit(ann, ctx, "@CrossOrigin(allowedOrigins=*)");
  }

  public void visit(SingleMemberAnnotationExpr ann, StyleContext ctx) {
    if (!isCorsAnnotation(ann.getName())) return;
    if (!isWildcardLiteral(ann.getMemberValue())) return;
    emit(ann, ctx, "@CrossOrigin(\"*\")");
  }

  private static boolean isCorsAnnotation(com.github.javaparser.ast.expr.Name name) {
    if (name == null) return false;
    String id = name.getIdentifier();
    if (CORS_ANNOTATIONS.contains(id)) return true;
    for (String part : name.toString().split("\\.")) {
      if (CORS_ANNOTATIONS.contains(part)) return true;
    }
    return false;
  }

  private static boolean hasWildcardMember(
      java.util.List<com.github.javaparser.ast.expr.MemberValuePair> pairs) {
    for (var p : pairs) {
      String memberName = p.getNameAsString();
      String lower = memberName.toLowerCase(Locale.ROOT);
      if (!ORIGIN_MEMBER_NAMES.contains(lower)) continue;
      if (isWildcardLiteral(p.getValue())) return true;
    }
    return false;
  }

  private static void emit(Node n, StyleContext ctx, String kind) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "CORS allowedOrigins='*' disables same-origin policy; "
            + "list specific trusted origins instead (CWE-942)",
        RuleDef.Severity.MEDIUM,
        "cors-misconfig: " + kind);
  }
}
