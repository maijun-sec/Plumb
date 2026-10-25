package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** method returns object unrelated to declared type (EC_UNRELATED_TYPES). */
public final class EcUnrelatedTypesCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-ec-unrelated", "EC_UNRELATED"),
          "method returns object unrelated to declared type (EC_UNRELATED_TYPES)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EcUnrelatedTypesCheck",
          true,
          List.of("ast-style-unrelated-types", "ec-unrelated"));

  @Override
  public String name() {
    return "ec-unrelated";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody().isEmpty()) return;
    // The method must have a primitive return type
    // for the canonical mistake: a method declared
    // to return {@code int} but returning a Boolean.
    String declared = n.getType().asString();
    if (declared.equals("int")
        || declared.equals("long")
        || declared.equals("boolean")
        || declared.equals("double")) {
      // Look for a return statement returning a wrapper.
      for (ReturnStmt ret : n.getBody().get().findAll(ReturnStmt.class)) {
        if (!ret.getExpression().isPresent()) continue;
        Expression e = ret.getExpression().get();
        String exprText = e.toString();
        // Strip field-access prefix: "Boolean.TRUE" →"Boolean"
        String head = exprText;
        int dot = exprText.indexOf('.');
        if (dot > 0) head = exprText.substring(0, dot);
        if (declared.equals("int")
            && ("Integer".equals(head)
                || "Long".equals(head)
                || "Short".equals(head)
                || "Byte".equals(head)
                || "Boolean".equals(head)
                || "Double".equals(head)
                || "Float".equals(head))) {
          fire(n, ret, ctx, head, "int");
        } else if (declared.equals("long")
            && ("Integer".equals(head)
                || "Long".equals(head)
                || "Short".equals(head)
                || "Byte".equals(head)
                || "Boolean".equals(head)
                || "Double".equals(head)
                || "Float".equals(head))) {
          fire(n, ret, ctx, head, "long");
        } else if (declared.equals("boolean")
            && ("Integer".equals(head)
                || "Long".equals(head)
                || "Double".equals(head)
                || "Float".equals(head))) {
          fire(n, ret, ctx, head, "boolean");
        } else if (declared.equals("double")
            && ("Long".equals(head)
                || "Integer".equals(head)
                || "Short".equals(head)
                || "Byte".equals(head)
                || "Boolean".equals(head))) {
          fire(n, ret, ctx, head, "double");
        }
      }
    }
  }

  private void fire(
      MethodDeclaration m, ReturnStmt r, StyleContext ctx, String got, String expected) {
    int line = r.getBegin().map(p -> p.line).orElse(-1);
    int col = r.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Method '"
            + m.getNameAsString()
            + "' returns "
            + got
            + " but is declared to return "
            + expected,
        RuleDef.Severity.LOW,
        got);
  }
}
