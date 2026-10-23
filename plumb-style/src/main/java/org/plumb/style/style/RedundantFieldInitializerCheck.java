package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.*;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** redundant field initializer (already default value). */
public final class RedundantFieldInitializerCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-redundant-init", "REDUNDANT_INIT"),
          "redundant field initializer (already default value)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantFieldInitializerCheck",
          false,
          List.of("ast-style-redundant-field-initializer", "redundant-init")
      ); // R-834: disabled by default (0 TP / 100% FP on Juliet R-832 baseline)

  @Override
  public String name() {
    return "redundant-init";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    for (VariableDeclarator v : n.getVariables()) {
      if (v.getInitializer().isEmpty()) continue;
      Expression init = v.getInitializer().get();
      String typeName = n.getElementType().toString();
      if (isDefaultValue(init, typeName)) {
        int line = v.getBegin().map(p -> p.line).orElse(-1);
        int col = v.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Field '" + v.getNameAsString() + "' is initialized to its default value",
            RuleDef.Severity.LOW,
            v.getNameAsString());
      }
    }
  }

  private static boolean isDefaultValue(Expression e, String type) {
    if (e instanceof NullLiteralExpr) {
      return !"int".equals(type)
          && !"boolean".equals(type)
          && !"char".equals(type)
          && !"byte".equals(type)
          && !"short".equals(type)
          && !"long".equals(type)
          && !"float".equals(type)
          && !"double".equals(type);
    }
    if (e instanceof BooleanLiteralExpr b) {
      return "boolean".equals(type) && !b.getValue();
    }
    if (e instanceof IntegerLiteralExpr i) {
      return ("int".equals(type) || "byte".equals(type) || "short".equals(type))
          && "0".equals(i.getValue());
    }
    if (e instanceof LongLiteralExpr l) {
      return "long".equals(type)
          && ("0".equals(l.getValue().replaceAll("[lL]$", "")) || "0L".equals(l.getValue()));
    }
    if (e instanceof DoubleLiteralExpr d) {
      return ("double".equals(type) || "float".equals(type)) && (d.asDouble() == 0.0);
    }
    if (e instanceof CharLiteralExpr c) {
      return "char".equals(type) && c.getValue().length() == 1 && c.getValue().charAt(0) == '\0';
    }
    return false;
  }
}
