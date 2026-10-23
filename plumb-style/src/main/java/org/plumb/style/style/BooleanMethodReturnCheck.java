package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Optional;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Boolean-returning method that returns null is dangerous (autoboxing NPE). */
public final class BooleanMethodReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-boolean-method-returns-null", "BOOLEAN_METHOD_RETURNS_NULL"),
          "Boolean-returning method that returns null is dangerous (autoboxing NPE)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-476"),
          "org.plumb.style.BooleanMethodReturnCheck",
          true,
          List.of("ast-style-boolean-returns-null", "boolean-method-returns-null")
      );

  @Override
  public String name() {
    return "boolean-method-returns-null";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (!returnsBoolean(n.getType())) return;
    final Optional<com.github.javaparser.ast.stmt.BlockStmt> body = n.getBody();
    if (body.isEmpty()) return;
    for (Statement s : body.get().getStatements()) {
      if (!(s instanceof ReturnStmt ret)) continue;
      if (ret.getExpression().isEmpty()) continue;
      if (ret.getExpression().get().toString().equals("null")) {
        ctx.report(
            ret.getBegin().map(p -> p.line).orElse(-1),
            ret.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Method '"
                + n.getNameAsString()
                + "' returns null but the return type is Boolean "
                + "(autoboxing NPE risk)",
            RuleDef.Severity.HIGH,
            n.getNameAsString());
      }
    }
  }

  private static boolean returnsBoolean(Type t) {
    if (t instanceof ClassOrInterfaceType coi) {
      String name = coi.getNameAsString();
      String fqn = coi.getNameWithScope();
      return "Boolean".equals(name) || "java.lang.Boolean".equals(fqn);
    }
    return false;
  }
}
