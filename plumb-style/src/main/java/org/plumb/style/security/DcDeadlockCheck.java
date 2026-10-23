/*
 * Coverity <code>DC.DEADLOCK</code> — detects calls to deadlock-prone Java APIs:
 *
 * <ul>
 *   <li>{@link Thread#suspend()} and {@link ThreadGroup#suspend()} ? the target thread holds all
 *       monitors while suspended; any other thread that subsequently tries to enter one of those
 *       monitors deadlocks.
 *   <li>{@link Thread#resume()} and {@link ThreadGroup#resume()} ? the matching pair to {@code
 *       suspend()}; using either in isolation can leave threads in a state where they cannot make
 *       progress.
 *   <li>{@link Thread#countStackFrames()} ? implemented on top of {@code suspend()} so the call
 *       itself can deadlock.
 *   <li>{@link ThreadGroup#allowThreadSuspension(boolean)} ? the ThreadGroup-level knob that
 *       controls {@code suspend()} behavior project-wide and inherits the same deadlock hazards.
 * </ul>
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)} before. The type-resolution logic
 * inside {@link #classifyDeadlock} is unchanged.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class DcDeadlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-dc-deadlock", "DC_DEADLOCK"),
          "Call to deadlock-prone API (Thread.suspend / resume / countStackFrames,"
              + " ThreadGroup.suspend / resume / allowThreadSuspension)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-662", "CWE-764"),
          "org.plumb.style.DcDeadlockCheck",
          true,
          List.of("ast-security-dc-deadlock", "dc-deadlock")
      );

  @Override
  public String name() {
    return "dc-deadlock";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String methodName = m.getNameAsString();
    if (!"suspend".equals(methodName)
        && !"resume".equals(methodName)
        && !"countStackFrames".equals(methodName)
        && !"allowThreadSuspension".equals(methodName)) {
      return;
    }
    String reason = classifyDeadlock(m, methodName);
    if (reason == null) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "DC.DEADLOCK: " + reason, RuleDef.Severity.HIGH, m.toString());
  }

  private static String classifyDeadlock(MethodCallExpr m, String methodName) {
    String scopeType = scopeQualifiedName(m);
    switch (methodName) {
      case "suspend":
      case "resume":
        if (m.getArguments().isEmpty()
            && (isType(scopeType, "java.lang.Thread")
                || isType(scopeType, "java.lang.ThreadGroup"))) {
          return scopeType + "." + methodName + "() is deadlock-prone and deprecated";
        }
        return null;
      case "countStackFrames":
        if (m.getArguments().isEmpty() && isType(scopeType, "java.lang.Thread")) {
          return "Thread.countStackFrames() depends on suspend() and is deadlock-prone";
        }
        return null;
      case "allowThreadSuspension":
        if (m.getArguments().size() == 1
            && isType(scopeType, "java.lang.ThreadGroup")
            && isBooleanArg(m, 0)) {
          return "ThreadGroup.allowThreadSuspension(boolean) controls suspend() "
              + "behavior project-wide and inherits its deadlock hazard";
        }
        return null;
      default:
        return null;
    }
  }

  private static String scopeQualifiedName(MethodCallExpr m) {
    if (!m.getScope().isPresent()) return null;
    Expression scope = m.getScope().get();
    ResolvedType rt;
    try {
      rt = scope.calculateResolvedType();
    } catch (RuntimeException ex) {
      return null;
    }
    if (rt == null) return null;
    return rt.describe();
  }

  private static boolean isType(String qualified, String expected) {
    if (qualified == null) return false;
    if (qualified.equals(expected)) return true;
    int dot = expected.lastIndexOf('.');
    if (dot >= 0 && qualified.equals(expected.substring(dot + 1))) return true;
    return false;
  }

  private static boolean isBooleanArg(MethodCallExpr m, int i) {
    if (i >= m.getArguments().size()) return false;
    Expression arg = m.getArgument(i);
    try {
      ResolvedType rt = arg.calculateResolvedType();
      if (rt != null && "boolean".equals(rt.describe())) return true;
    } catch (RuntimeException ignore) {
      // fall through to text check
    }
    String text = arg.toString().trim();
    return "true".equals(text) || "false".equals(text);
  }
}