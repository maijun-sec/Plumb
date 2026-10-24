package org.plumb.style.performance;

import com.github.javaparser.ast.body.VariableDeclarator;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Array store of incompatible type.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(VariableDeclarator.class)} before.
 */
public final class ArrayStoreIncompatibleCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-store-incompatible", "ARRAY_STORE_INCOMPATIBLE"),
          "Array store of incompatible type",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ArrayStoreIncompatibleCheck",
          true,
          List.of("ast-performance-store-incompatible", "array-store-incompatible"));

  @Override
  public String name() {
    return "array-store-incompatible";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(VariableDeclarator v, StyleContext ctx) {
    String s = v.getInitializer().map(Object::toString).orElse("");
    if (!s.contains("new Object[")) return;
    if (!v.getTypeAsString().contains("String")) return;
    int line = v.getBegin().map(p -> p.line).orElse(-1);
    int col = v.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Array store of incompatible type",
        RuleDef.Severity.MEDIUM,
        v.getNameAsString());
  }
}
