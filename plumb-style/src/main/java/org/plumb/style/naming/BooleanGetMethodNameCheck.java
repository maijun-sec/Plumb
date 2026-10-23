package org.plumb.style.naming;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Boolean getter should be {@code isX()} or {@code hasX()}.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback directly; was {@code
 * visit(CompilationUnit) + findAll(MethodDeclaration.class)} before.
 */
public final class BooleanGetMethodNameCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-naming-boolean-get-method-name", "BOOLEAN_GET_METHOD_NAME"),
          "boolean getter should be 'isX()' or 'hasX()'",
          RuleDef.Severity.LOW,
          RuleDef.Type.NAMING,
          List.of(),
          "org.plumb.style.BooleanGetMethodNameCheck",
          true,
          List.of("ast-naming-boolean-get-method-name", "boolean-get-method-name")
      );

  @Override
  public String name() {
    return "boolean-get-method-name";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    if (!(m.getType() instanceof PrimitiveType)) return;
    PrimitiveType pt = (PrimitiveType) m.getType();
    if (!pt.asString().equals("boolean")) return;
    if (!m.getNameAsString().startsWith("get")) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Boolean getter should be isX/hasX, not getX: " + m.getNameAsString(),
        RuleDef.Severity.LOW,
        m.getNameAsString());
  }
}