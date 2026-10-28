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

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-600 (Uncaught Exception in Servlet) -- file-wide check for risky checked-exception-throwing
 * calls.
 */
public final class UncaughtExceptionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-cwe600-uncaught-exception", "CWE600_UNCAUGHT_EXCEPTION"),
          "Risky call without try/catch (CWE-600).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-600"),
          "org.plumb.style.UncaughtExceptionCheck",
          true,
          List.of("ast-style-cwe600-uncaught-exception-style", "cwe600-uncaught-exception"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "cwe600-uncaught-exception";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    BlockStmt body = m.getBody().orElse(null);
    if (body == null) return;
    body.walk(
        MethodCallExpr.class,
        mc -> {
          if (!isRiskyCall(mc)) return;
          if (isInsideTryCatch(mc)) return;
          int line = mc.getBegin().map(p -> p.line).orElse(-1);
          String key = m.getNameAsString() + "|" + line;
          if (!reported.add(key)) return;
          ctx.report(
              line,
              -1,
              DEFAULT_RULE,
              "Risky call '"
                  + mc.getNameAsString()
                  + "' at line "
                  + line
                  + " in method '"
                  + m.getNameAsString()
                  + "()' has no enclosing try/catch (CWE-600).",
              RuleDef.Severity.MEDIUM,
              "cwe600@" + m.getNameAsString() + " line " + line);
        });
  }

  private static boolean isRiskyCall(MethodCallExpr mc) {
    String name = mc.getNameAsString();
    switch (name) {
      case "getByName":
        return hasReceivingTypeName(mc, "InetAddress");
      case "forName":
        return hasReceivingTypeName(mc, "Class");
      case "start":
        if (mc.getScope().isPresent()) {
          return hasScopeWithDeclaredType(mc, "ProcessBuilder");
        }
        return false;
      default:
        return false;
    }
  }

  private static boolean hasReceivingTypeName(MethodCallExpr mc, String typeName) {
    Optional<com.github.javaparser.ast.Node> parent = mc.getParentNode();
    for (int depth = 0; depth < 6 && parent.isPresent(); depth++) {
      com.github.javaparser.ast.Node p = parent.get();
      if (p instanceof com.github.javaparser.ast.body.VariableDeclarator vd) {
        String t = vd.getTypeAsString();
        if (t.contains(typeName)) return true;
      }
      if (p instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
        String t = vde.getElementType().asString();
        if (t.contains(typeName)) return true;
      }
      parent = p.getParentNode();
    }
    return false;
  }

  private static boolean hasScopeWithDeclaredType(MethodCallExpr mc, String typeName) {
    if (!mc.getScope().isPresent()) return false;
    if (!(mc.getScope().get() instanceof NameExpr ne)) return false;
    String varName = ne.getNameAsString();
    com.github.javaparser.ast.Node cur = mc;
    while (cur != null && !(cur instanceof com.github.javaparser.ast.body.MethodDeclaration)) {
      cur = cur.getParentNode().orElse(null);
    }
    if (cur == null) return false;
    com.github.javaparser.ast.body.MethodDeclaration md =
        (com.github.javaparser.ast.body.MethodDeclaration) cur;
    final boolean[] found = {false};
    md.walk(
        com.github.javaparser.ast.body.VariableDeclarator.class,
        vd -> {
          if (found[0]) return;
          if (varName.equals(vd.getNameAsString())) {
            String t = vd.getTypeAsString();
            if (t.contains(typeName)) found[0] = true;
          }
        });
    return found[0];
  }

  private static boolean isInsideTryCatch(MethodCallExpr mc) {
    Optional<com.github.javaparser.ast.Node> parent = mc.getParentNode();
    while (parent.isPresent()) {
      if (parent.get() instanceof TryStmt) return true;
      parent = parent.get().getParentNode();
    }
    return false;
  }
}
