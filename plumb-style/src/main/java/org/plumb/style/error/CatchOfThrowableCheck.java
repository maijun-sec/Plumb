package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Catching {@code Throwable} / {@code Error} is too broad.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit) +
 * findAll(CatchClause.class)} before.
 */
public final class CatchOfThrowableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-catch-of-throwable", "CATCH_OF_THROWABLE"),
          "catching Throwable/Error is too broad",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-396"),
          "org.plumb.style.CatchOfThrowableCheck",
          true,
          List.of("ast-error-of-throwable", "catch-of-throwable"));

  @Override
  public String name() {
    return "catch-of-throwable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    if (!(cc.getParameter().getType() instanceof ClassOrInterfaceType t)) return;
    String name = t.getNameAsString();
    if (!"Throwable".equals(name) && !"Error".equals(name)) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col = cc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Catching " + name + " is too broad; catch a specific subtype",
        RuleDef.Severity.MEDIUM,
        name);
  }
}
