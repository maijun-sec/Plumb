package org.plumb.style.bug;

import com.github.javaparser.ast.expr.BinaryExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Redundant null check of null value. */
public final class RedundantNullCheckOfNullValueCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-bug-redundantnullofnullvalue-sb", "REDUNDANTNULLOFNULLVALUE_SB"),
          "Redundant null check of null value",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.RedundantNullCheckOfNullValueCheck",
          true,
          List.of("ast-bug-redundant-null-check-null", "redundantnullofnullvalue_sb"));

  @Override
  public String name() {
    return "redundantnullofnullvalue-sb";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr b, StyleContext ctx) {
    if (b.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS) {
      String s = b.toString();
      if (s.contains("== null") || s.contains("!= null")) {
        int line = b.getBegin().map(p -> p.line).orElse(-1);
        int col = b.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line, col, DEFAULT_RULE, "Redundant null check of null value", RuleDef.Severity.LOW, s);
      }
    }
  }
}
