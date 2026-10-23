package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Path manipulation via {@code getCanonicalPath()} / {@code getAbsolutePath()}.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(MethodCallExpr.class)} before.
 */
public final class PathCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-path", "PATH"),
          "Path manipulation",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.PathCheck",
          true,
          List.of("ast-security-check", "path")
      );

  @Override
  public String name() {
    return "path";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    String name = m.getNameAsString();
    if (!"getCanonicalPath".equals(name) && !"getAbsolutePath".equals(name)) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(line, col, DEFAULT_RULE, "Path manipulation", RuleDef.Severity.LOW, name);
  }
}