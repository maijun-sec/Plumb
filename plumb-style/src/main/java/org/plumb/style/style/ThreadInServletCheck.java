/*
 * C4: AST check for CWE-383 (J2EE Bad Practices:
 * Direct Use of Threads). Fires when a class that
 * appears to be a servlet (extends HttpServlet, or has
 * HttpServletRequest / HttpServletResponse parameter
 * types) uses Thread-management primitives
 * (`new Thread(...)`, `Thread.sleep`, `xxx.start()`).
 *
 * <p>Generic. The "is a servlet" check is purely
 * structural: any class whose declaration extends
 * HttpServlet OR declares a method with parameter type
 * HttpServletRequest / HttpServletResponse is treated
 * as servlet-like. Real J2EE rules forbid direct thread
 * management in servlet methods -- async work belongs
 * in a worker queue, not a Thread.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ThreadInServletCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-thread-in-servlet", "THREAD_IN_SERVLET"),
          "Direct use of Thread management in a servlet method (CWE-383)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-383"),
          "org.plumb.style.ThreadInServletCheck",
          true,
          List.of("ast-style-direct-threads-in-servlet", "thread-in-servlet"));

  // The class declared type signatures that mark the
  // enclosing class as a servlet. Accept the short and
  // FQN forms.
  private static final Set<String> SERVLET_TYPES =
      Set.of(
          "HttpServlet",
          "javax.servlet.http.HttpServlet",
          "GenericServlet",
          "javax.servlet.GenericServlet");

  // Method-call signatures that count as Thread
  // management in J2EE. .start() is dangerous on any
  // object but the most common case is a Thread
  // instance — the name `Thread` heuristic alone misses
  // `threadOne.start()` where the receiver is a
  // NameExpr. We accept `start` calls whose receiver
  // name begins with `thread` or whose receiver type
  // is Thread. Thread.sleep() is a static call.
  private static final Set<String> THREAD_CTOR_NAMES = Set.of("Thread");
  private static final Set<String> THREAD_STATIC_METHODS =
      Set.of("sleep", "yield", "currentThread");

  private final Set<String> reported = new HashSet<>();
  private boolean inServlet = false;

  @Override
  public String name() {
    return "thread-in-servlet";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
    inServlet = false;
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    inServlet = isServletClass(n);
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    if (!inServlet) return;
    String typeName = n.getType().getNameAsString();
    if (!THREAD_CTOR_NAMES.contains(typeName)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|newThread";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Direct 'new Thread(...)' inside a servlet — " + "use an async work queue (CWE-383)",
          RuleDef.Severity.HIGH,
          "new " + typeName + "(...)");
    }
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!inServlet) return;
    String name = n.getNameAsString();
    // Static Thread.foo() — Thread.sleep, Thread.yield, etc.
    if (n.getScope().isPresent()
        && "Thread".equals(n.getScope().get().toString())
        && THREAD_STATIC_METHODS.contains(name)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|thrStatic";
      if (reported.add(key)) {
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Direct Thread."
                + name
                + "(...) inside a servlet — "
                + "use an async work queue (CWE-383)",
            RuleDef.Severity.HIGH,
            "Thread." + name + "(...)");
      }
      return;
    }
    // .start() — the receiver is a NameExpr named thread*
    if ("start".equals(name) && n.getScope().isPresent()) {
      String recv = n.getScope().get().toString();
      String low = recv.toLowerCase();
      if (low.startsWith("thread") || low.contains("thread")) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        String key = line + "|" + col + "|start";
        if (reported.add(key)) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Direct '.start()' on a Thread inside a servlet — "
                  + "use an async work queue (CWE-383)",
              RuleDef.Severity.HIGH,
              recv + ".start()");
        }
      }
    }
  }

  /**
   * Decide whether the current class declaration is a servlet. Two heuristics: (a)
   * extends/implements one of the SERVLET_TYPES. (b) declares a method whose parameter list
   * contains HttpServletRequest/Response.
   */
  private boolean isServletClass(ClassOrInterfaceDeclaration n) {
    for (ClassOrInterfaceType ext : n.getExtendedTypes()) {
      if (SERVLET_TYPES.contains(ext.getNameAsString())) return true;
      String fqn = ext.toString();
      if (SERVLET_TYPES.contains(fqn)) return true;
    }
    for (ClassOrInterfaceType impl : n.getImplementedTypes()) {
      String fqn = impl.toString();
      if (SERVLET_TYPES.contains(impl.getNameAsString())) return true;
      if (SERVLET_TYPES.contains(fqn)) return true;
    }
    for (MethodDeclaration m : n.getMethods()) {
      for (Parameter p : m.getParameters()) {
        String t = p.getTypeAsString();
        if (t.contains("HttpServletRequest") || t.contains("HttpServletResponse")) {
          return true;
        }
      }
    }
    return false;
  }
}
