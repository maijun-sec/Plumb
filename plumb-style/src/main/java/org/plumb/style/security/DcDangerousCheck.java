/*
 * Coverity <code>DC.DANGEROUS</code> — detects calls to dangerous Java APIs:
 *
 * <ul>
 *   <li>{@link Thread#stop()} and {@link ThreadGroup#stop()} ? deprecated and inherently unsafe:
 *       they release all locks the thread holds, which can leave shared objects in inconsistent
 *       states.
 *   <li>{@link Thread#destroy()} ? defined on {@code java.lang.Thread} but (as of JDK 17) only the
 *       {@code ThreadGroup.destroy()} variant remains implemented; the {@code Thread.destroy()}
 *       declaration was removed. The checker still flags the call because the developer intent
 *       ("destroy a thread") is unsafe either way.
 *   <li>{@code java.io.ObjectOutputStream.PutField.write(ObjectOutput)} — * corrupt serialization
 *       streams because the field was never committed to the stream.
 * </ul>
 *
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class DcDangerousCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-dc-dangerous", "DC_DANGEROUS"),
          "Call to dangerous API (Thread.stop, Thread.destroy, ObjectOutputStream$PutField.write)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-676", "CWE-396"),
          "org.plumb.style.DcDangerousCheck",
          true,
          List.of("ast-security-dc-dangerous", "dc-dangerous"));

  @Override
  public String name() {
    return "dc-dangerous";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String methodName = m.getNameAsString();
    if (!"stop".equals(methodName)
        && !"destroy".equals(methodName)
        && !"write".equals(methodName)) {
      return;
    }
    String hitReason = classifyDangerous(m, methodName);
    if (hitReason == null) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line, col, DEFAULT_RULE, "DC.DANGEROUS: " + hitReason, RuleDef.Severity.HIGH, m.toString());
  }

  /**
   * Returns a non-null reason string if the call matches one of the four dangerous signatures; null
   * otherwise.
   */
  private static String classifyDangerous(MethodCallExpr m, String methodName) {
    String scopeType = scopeQualifiedName(m);
    boolean methodResolves = false;
    try {
      methodResolves = m.resolve() != null;
    } catch (RuntimeException ignore) {
      // method no longer exists in this JDK; rely on scope type
    }

    switch (methodName) {
      case "stop":
        if (m.getArguments().isEmpty()
            && (isType(scopeType, "java.lang.Thread")
                || isType(scopeType, "java.lang.ThreadGroup"))) {
          return scopeType + ".stop() is deprecated and can leave shared state corrupted";
        }
        return null;
      case "destroy":
        if (m.getArguments().isEmpty()
            && (isType(scopeType, "java.lang.Thread")
                || isType(scopeType, "java.lang.ThreadGroup"))) {
          return scopeType
              + ".destroy() is deprecated; Thread.destroy() was never "
              + "implemented and ThreadGroup.destroy() has been deprecated "
              + "since Java 16";
        }
        return null;
      case "write":
        if (m.getArguments().size() == 1
            && isType(scopeType, "java.io.ObjectOutputStream$PutField")) {
          return "ObjectOutputStream$PutField.write(ObjectOutput) can corrupt "
              + "serialization streams";
        }
        if (methodResolves && m.getArguments().size() == 1) {
          try {
            if (m.resolve()
                instanceof
                com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration) {
              com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration rmd =
                  (com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration)
                      m.resolve();
              if ("java.io.ObjectOutputStream$PutField"
                  .equals(rmd.declaringType().getQualifiedName())) {
                return "ObjectOutputStream$PutField.write(ObjectOutput) can corrupt "
                    + "serialization streams";
              }
            }
          } catch (RuntimeException ignore) {
            // ignore
          }
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
}
