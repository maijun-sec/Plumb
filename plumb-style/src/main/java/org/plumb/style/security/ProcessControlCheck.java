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
 * AST-style CWE-114 (Process Control) checker.
 *
 * <p>Fires on calls to {@code System.loadLibrary(name)} or {@code Runtime.loadLibrary(name)} (the
 * unsafe JVM / OS path search). The fix is {@code System.load(fullPath + name)} with an explicit
 * fully-qualified path.
 *
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ProcessControlCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-process-control", "PROCESS_CONTROL"),
          "Process control via loadLibrary (CWE-114)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-114"),
          "org.plumb.style.security.ProcessControlCheck",
          true,
          List.of("ast-security-process-control", "process-control"));

  @Override
  public String name() {
    return "process-control";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    if (!"loadLibrary".equals(m.getNameAsString())) return;
    if (m.getArguments().size() != 1) return;
    if (!isSystemOrRuntimeScope(m.getScope().orElse(null))) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        RULE,
        "Use of loadLibrary(): lets the JVM / OS choose the"
            + " native library path. An attacker who controls"
            + " LD_LIBRARY_PATH or java.library.path can hijack"
            + " the load. Use System.load(fullPath + name) with"
            + " an explicit, fully-qualified path instead.",
        RuleDef.Severity.HIGH,
        "loadLibrary");
  }

  private static boolean isSystemOrRuntimeScope(Expression scope) {
    if (scope == null) return false;
    if (scope instanceof FieldAccessExpr fa) {
      return ("System".equals(fa.getScope().toString())
              || "Runtime".equals(fa.getScope().toString()))
          && "loadLibrary".equals(fa.getNameAsString());
    }
    if (scope instanceof NameExpr ne) {
      String s = ne.getNameAsString();
      return "System".equals(s) || "Runtime".equals(s);
    }
    return false;
  }
}
