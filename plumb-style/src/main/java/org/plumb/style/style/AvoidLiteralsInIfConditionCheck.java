package org.plumb.style.style;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Avoid literals in if conditions (use named constants). */
public final class AvoidLiteralsInIfConditionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-avoid-literals-in-if", "AVOID_LITERALS_IN_IF"),
          "avoid literals in if conditions (use named constants)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.AvoidLiteralsInIfConditionCheck",
          true,
          List.of("ast-style-literals-in-if", "avoid-literals-in-if"));

  @Override
  public String name() {
    return "avoid-literals-in-if";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IfStmt ifs, StyleContext ctx) {
    if (!(ifs.getCondition() instanceof BinaryExpr be)) return;
    boolean leftLit = be.getLeft() instanceof IntegerLiteralExpr;
    boolean rightLit = be.getRight() instanceof IntegerLiteralExpr;
    if (!leftLit && !rightLit) return;
    int line = ifs.getBegin().map(p -> p.line).orElse(-1);
    int col = ifs.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Avoid literal in if condition",
        RuleDef.Severity.LOW,
        be.toString());
  }
}
