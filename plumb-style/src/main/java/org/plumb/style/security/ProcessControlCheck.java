/*
 * R-813: AST-style CWE-114 (Process Control) checker.
 *
 * <p>Fires on calls to {@code System.loadLibrary(name)} or {@code Runtime.loadLibrary(name)} (the
 * unsafe JVM / OS path search). The fix is {@code System.load(fullPath + name)} with an explicit
 * fully-qualified path.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)} before. The {@code System/Runtime}
 * scope check is preserved via {@link #isSystemOrRuntimeScope(com.github.javaparser.ast.expr.Expression)}.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.reporter.Finding;
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
          List.of("ast-security-process-control", "process-control")
      );

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