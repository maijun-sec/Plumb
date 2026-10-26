package org.plumb.style.style;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Month literal in {@code 1..12} range passed to {@code set/add}/{@code MONTH} -- use {@code
 * Calendar.JANUARY (0)} .. {@code Calendar.DECEMBER (11)} instead (DMI_BAD_MONTH).
 */
public final class DmiBadMonthCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-dmi-bad-month", "DMI_BAD_MONTH"),
          "month literal 1-12 -- use Calendar.JANUARY (0) (DMI_BAD_MONTH)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.DmiBadMonthCheck",
          true,
          List.of("ast-style-bad-month", "dmi-bad-month"));

  @Override
  public String name() {
    return "dmi-bad-month";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(IntegerLiteralExpr lit, StyleContext ctx) {
    int v;
    try {
      v = lit.asNumber().intValue();
    } catch (Exception ex) {
      return;
    }
    if (v < 1 || v > 12) return;
    if (!looksLikeMonthContext(lit)) return;
    int line = lit.getBegin().map(p -> p.line).orElse(-1);
    int col = lit.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Month literal " + v + " -- use Calendar.JANUARY (0)",
        RuleDef.Severity.LOW,
        String.valueOf(v));
  }

  /**
   * Heuristic: the literal is in month position if its parent is a {@code MethodCallExpr} whose
   * name starts with {@code set} / {@code add} or is exactly {@code MONTH}.
   */
  private static boolean looksLikeMonthContext(IntegerLiteralExpr lit) {
    Node parent = lit.getParentNode().orElse(null);
    if (parent == null) return false;
    if (!(parent instanceof MethodCallExpr mc)) return false;
    String n = mc.getNameAsString();
    return n.startsWith("set") || n.startsWith("add") || "MONTH".equals(n);
  }
}
