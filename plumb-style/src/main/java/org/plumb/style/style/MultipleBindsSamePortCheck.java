/*
 * R729-A5: AST check for CWE-605 (Multiple Binds to
 * the Same Port). Fires when, within a single method
 * body, the same `new InetSocketAddress(<port>)` port
 * literal appears in two or more `bind(...)` /
 * constructor invocations.
 *
 * <p>Generic. We walk the compilation unit method-by-
 * method; for each method, collect every
 * `InetSocketAddress` ObjectCreationExpr whose first
 * argument is an IntegerLiteralExpr. If any literal
 * value is repeated, fire on the second+ occurrence.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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

public final class MultipleBindsSamePortCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-multiple-binds-same-port", "MULTIPLE_BINDS_SAME_PORT"),
          "Two InetSocketAddress bindings to the same port literal in one method (CWE-605)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-605"),
          "org.plumb.style.MultipleBindsSamePortCheck",
          true,
          List.of("ast-style-multiple-binds-same-port", "multiple-binds-same-port")
      );

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "multiple-binds-same-port";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!m.getBody().isPresent()) return;
    // port literal -> first ObjectCreationExpr line.
    // We accept either:
    //   new InetSocketAddress(<int_port>)
    //   new InetSocketAddress("<hostname>", <int_port>)
    Map<Integer, Integer> seen = new HashMap<>();
    for (ObjectCreationExpr oce : m.getBody().get().findAll(ObjectCreationExpr.class)) {
      if (!"InetSocketAddress".equals(oce.getType().getNameAsString())) {
        continue;
      }
      int port = -1;
      if (oce.getArguments().size() == 1) {
        if (!(oce.getArgument(0) instanceof IntegerLiteralExpr lit)) continue;
        try {
          port = Integer.parseInt(lit.getValue());
        } catch (NumberFormatException ex) {
          continue;
        }
      } else if (oce.getArguments().size() >= 2) {
        if (!(oce.getArgument(1) instanceof IntegerLiteralExpr lit)) continue;
        try {
          port = Integer.parseInt(lit.getValue());
        } catch (NumberFormatException ex) {
          continue;
        }
      } else {
        continue;
      }
      Integer prev = seen.get(port);
      if (prev != null) {
        int line = oce.getBegin().map(p -> p.line).orElse(-1);
        int col = oce.getBegin().map(p -> p.column).orElse(-1);
        String key = line + "|" + col + "|bindDup";
        if (reported.add(key)) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Multiple InetSocketAddress bindings to "
                  + "port "
                  + port
                  + " in method "
                  + m.getNameAsString()
                  + " (also at line "
                  + prev
                  + ")"
                  + " (CWE-605)",
              RuleDef.Severity.HIGH,
              "new InetSocketAddress(" + port + ")");
        }
      } else {
        seen.put(port, line(oce));
      }
    }
  }

  private int line(ObjectCreationExpr oce) {
    return oce.getBegin().map(p -> p.line).orElse(-1);
  }
}
