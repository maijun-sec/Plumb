/*
 * Coverity <code>SERVLET_ATOMICITY</code> (CWE-362) ? fires when a
 * {@code getAttribute} and {@code setAttribute} on the same
 * attribute (same string key) on
 * {@code ServletContext} / {@code HttpSession} / {@code JspContext}
 * occur outside of the same locked context.
 *
 * <p><b>R-818 (2026-10-14): pattern D rewrite.</b> The previous
 * implementation used {@code visit(CompilationUnit)} +
 * {@code n.findAll(MethodDeclaration)} (1 walk) + for each method
 * {@code body.findAll(MethodCallExpr)} (1 walk per method). The
 * new implementation uses {@code visit(MethodDeclaration)}
 * dispatched by {@code AstDispatcher} + a single walk with
 * instanceof dispatch to collect get/set call sites. Total:
 * 1 walk per file (was 1 + M walks).</p>
 */
package org.plumb.style.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ServletAtomicityCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-concurrency-servlet-atomicity", "SERVLET_ATOMICITY"),
          "Servlet getAttribute/setAttribute on the same key outside a synchronized block",
          RuleDef.Severity.HIGH,
          RuleDef.Type.MULTITHREADING,
          List.of("CWE-362"),
          "org.plumb.style.ServletAtomicityCheck",
          true,
          List.of("ast-concurrency-servlet-atomicity", "servlet-atomicity")
      );

  private static final Set<String> SERVLET_RECEIVER_TYPES =
      Set.of(
          "ServletContext",
          "HttpSession",
          "JspContext",
          "javax.servlet.ServletContext",
          "javax.servlet.http.HttpSession",
          "javax.servlet.jsp.JspContext",
          "jakarta.servlet.ServletContext",
          "jakarta.servlet.http.HttpSession",
          "jakarta.servlet.jsp.JspContext");

  @Override
  public String name() {
    return "servlet-atomicity";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    if (md.getBody().isEmpty()) return;
    Map<String, List<MethodCallExpr>> gets = new HashMap<>();
    Map<String, List<MethodCallExpr>> sets = new HashMap<>();
    // Single walk to collect get/set call sites with
    // instanceof dispatch.
    md.getBody()
        .get()
        .walk(
            com.github.javaparser.ast.Node.class,
            node -> {
              if (!(node instanceof MethodCallExpr m)) return;
              if (!isServletReceiver(m)) return;
              String name = m.getNameAsString();
              if (!"getAttribute".equals(name) && !"setAttribute".equals(name)) return;
              if (m.getArguments().isEmpty()) return;
              Expression first = m.getArgument(0);
              if (!(first instanceof StringLiteralExpr)) return;
              String attrName = ((StringLiteralExpr) first).getValue();
              if ("getAttribute".equals(name)) {
                gets.computeIfAbsent(attrName, k -> new ArrayList<>()).add(m);
              } else {
                sets.computeIfAbsent(attrName, k -> new ArrayList<>()).add(m);
              }
            });
    for (Map.Entry<String, List<MethodCallExpr>> e : sets.entrySet()) {
      String attrName = e.getKey();
      List<MethodCallExpr> getSites = gets.get(attrName);
      if (getSites == null || getSites.isEmpty()) continue;
      for (MethodCallExpr set : e.getValue()) {
        if (sharesSyncBlock(getSites, set)) continue;
        int line = set.getBegin().map(p -> p.line).orElse(-1);
        int col = set.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "SERVLET_ATOMICITY: setAttribute(\""
                + attrName
                + "\") follows a getAttribute(\""
                + attrName
                + "\") on the same servlet-scoped object but the two"
                + " are not inside the same synchronized block"
                + " — concurrent requests can lose updates",
            RuleDef.Severity.HIGH,
            "setAttribute(\"" + attrName + "\") in " + md.getNameAsString());
      }
    }
  }

  private static boolean isServletReceiver(MethodCallExpr m) {
    if (!m.getScope().isPresent()) return false;
    String scope = m.getScope().get().toString();
    String lower = scope.toLowerCase(java.util.Locale.ROOT);
    if (lower.contains("session")
        || lower.contains("application")
        || lower.contains("servletcontext")
        || lower.contains("context")
        || lower.contains("jspcontext")
        || lower.contains("pagecontext")) {
      return true;
    }
    for (String t : SERVLET_RECEIVER_TYPES) {
      if (scope.equals(t) || scope.endsWith("." + t)) return true;
    }
    return false;
  }

  private static boolean sharesSyncBlock(List<MethodCallExpr> getSites, MethodCallExpr setSite) {
    Set<Node> setAncestors = new HashSet<>();
    for (Node cur = setSite; cur != null; cur = cur.getParentNode().orElse(null)) {
      if (cur instanceof SynchronizedStmt) {
        setAncestors.add(cur);
      }
    }
    if (setAncestors.isEmpty()) return false;
    for (MethodCallExpr g : getSites) {
      for (Node cur = g; cur != null; cur = cur.getParentNode().orElse(null)) {
        if (cur instanceof SynchronizedStmt && setAncestors.contains(cur)) {
          return true;
        }
      }
    }
    return false;
  }
}
