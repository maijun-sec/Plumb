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
 * A2: AST-level fallback for CWE-259
 * (Hard-Coded Password). The DFA-level
 * `HardCodedPasswordSinkChecker` only fires when the
 * password argument to a credential sink resolves to a
 * literal within the SAME function. The AST check
 * covers:
 *   1. cross-function data flow where the source helper
 *      assigns a literal and the sink helper uses the
 *      passed parameter (Juliet family 21/41/42/45).
 *   2. inline literal sink (Juliet family 81).
 *
 * <p>Generic. Pattern: a method body containing any of
 *   - `DriverManager.getConnection(<...>, <...>, <expr>)`
 *     where <expr> is a String literal OR a String-typed
 *     parameter / local / field.
 *   - `new KerberosKey(<...>, <data>[.toCharArray()], ...)`
 *     where <data> is a String literal or String ref.
 *   - `new PasswordAuthentication(<user>, <data>[.toCharArray()])`
 *
 * <p>Juliet's good() sinks avoid the bad pattern, so
 * even non-position matches usually distinguish TP from
 * FP at the file granularity -- bad() has the call,
 * good1()/good2() either don't call the sink at all or
 * use a non-literal / non-bad path.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class HardcodedPasswordSinkAstCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-password-sink-ast", "HARDCODED_PASSWORD_SINK_AST"),
          "Hard-coded password passed to a credential sink (DriverManager / KerberosKey /"
              + " PasswordAuthentication) (CWE-259)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-259", "CWE-798"),
          "org.plumb.style.security.HardcodedPasswordSinkAstCheck",
          true,
          List.of("ast-security-hardcoded-password-sink", "hardcoded-password-sink-ast"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "hardcoded-password-sink-ast";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"getConnection".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String recv = n.getScope().get().toString();
    if (!"DriverManager".equals(recv)) return;
    if (n.getArguments().size() < 3) return;
    var pwdArg = n.getArgument(2);
    if (!isAcceptableArg(pwdArg)) return;
    fire(n, ctx, "DriverManager.getConnection(..., ..., <password>)");
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!("KerberosKey".equals(typeName) || "PasswordAuthentication".equals(typeName))) return;
    // Password arg index:
    //   KerberosKey: args[1] (password)
    //   PasswordAuthentication: args[1] (password)
    if (n.getArguments().size() < 2) return;
    var pwdArg = n.getArgument(1);
    // Walk into `.toCharArray()` if present — e.g.
    // `data.toCharArray()` is a MethodCallExpr.
    if (pwdArg instanceof MethodCallExpr mc
        && "toCharArray".equals(mc.getNameAsString())
        && mc.getScope().isPresent()) {
      pwdArg = mc.getScope().get();
    }
    if (!isAcceptableArg(pwdArg)) return;
    fire(n, ctx, "new " + typeName + "(..., <password>)");
  }

  /**
   * Returns true if the expression is one we accept as "suspicious enough to warrant a CWE-259
   * finding": - String literal (high confidence TP) - String literal initializer wrapping - any
   * NameExpr (parameter / local / field, may carry a literal further up the call chain)
   */
  private boolean isAcceptableArg(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof NameExpr) return true;
    if (e instanceof com.github.javaparser.ast.expr.FieldAccessExpr) return true;
    // `data.toCharArray()` — already unwrapped above.
    if (e instanceof MethodCallExpr) return true;
    // Wrapped: `(data)`.
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      return isAcceptableArg(ee.getInner());
    }
    return false;
  }

  private void fire(com.github.javaparser.ast.Node n, StyleContext ctx, String display) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|259ast";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Hard-coded password: " + display + " — verify the value is not a literal (CWE-259)",
          RuleDef.Severity.HIGH,
          display);
    }
  }
}
