package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Catching {@code Throwable} is too broad.
 *
 * <p>M5 part-3 migration (2026-10-17): per-node-type callback; was {@code visit(CompilationUnit)
 * + findAll(CatchClause.class)} before.
 */
public final class AvoidCatchingThrowableCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-avoid-throwable", "AVOID_THROWABLE"),
          "catching Throwable is too broad",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidCatchingThrowableCheck",
          true,
          List.of("ast-error-avoid-throwable", "avoid-throwable")
      );

  @Override
  public String name() {
    return "avoid-throwable";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    if (!(cc.getParameter().getType() instanceof ClassOrInterfaceType t)) return;
    if (!"Throwable".equals(t.getNameAsString())) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col = cc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Catching Throwable is too broad",
        RuleDef.Severity.MEDIUM,
        t.getNameAsString());
  }
}