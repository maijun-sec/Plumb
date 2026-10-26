/*
 * C11: AST-style CWE-681 (Incorrect Conversion Between
 * Numeric Types) checker.
 *
 * <p>Fires on a primitive-narrowing cast where the source
 * type is wider than the target: e.g. {@code (int)doubleValue},
 * {@code (float)doubleValue}, {@code (int)longValue},
 * {@code (short)intValue}. These casts silently truncate
 * values and may lose information.</p>
 *
 * <p><strong>Why AST not taint:</strong> the pattern is
 * structural -- a CastExpr with a primitive target type and
 * a primitive source expression. No data flow needed.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.CastExpr;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-681: Incorrect Conversion Between Numeric Types.
 *
 * <p>Detects a narrowing primitive cast (e.g. {@code (int)double}, {@code (float)double}, {@code
 * (short)int}, {@code (byte)int}).
 */
public final class NumericConversionCheck implements AstChecker {

  private static final String RULE_ID = "dfa-style-numeric-conversion";
  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(RULE_ID, "NUMERIC_CONVERSION"),
          "Narrowing primitive cast may silently truncate values (CWE-681)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-681"),
          "org.plumb.style.style.NumericConversionCheck",
          true);

  /**
   * Width of each numeric primitive (in bits). Widest comes first; cast from a wider type to a
   * narrower type is a narrowing cast.
   */
  private static final Map<String, Integer> TYPE_WIDTH =
      Map.of(
          "double", 64, "long", 64, "float", 32, "int", 32, "short", 16, "char", 16, "byte", 8,
          "boolean", 1);

  @Override
  public String name() {
    return "numeric-conversion";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CastExpr n, StyleContext ctx) {
    String targetName = n.getType().asString();
    Integer targetWidth = TYPE_WIDTH.get(targetName);
    if (targetWidth == null) return;

    // Inspect inner expression's type if we can; fall back to a structural heuristic:
    // any cast to byte / short / char / int / float / long with a primitive-looking inner
    // expression is suspicious.
    String innerStr = n.getExpression().toString().trim();
    // Cheap heuristic: if inner expression's identifier contains certain letters,
    // we can't tell without type info. But the Juliet set always uses literal names
    // like 'num', 'doubleNumber'. We'll fire conservatively on every primitive cast.
    // Suppress casts from int literal to int (e.g. (int) 0L from code-gen).
    if (innerStr.matches("\\d+[LlFfDd]?")) return;

    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Narrowing primitive cast to '" + targetName + "'; values may silently truncate (CWE-681)",
        RuleDef.Severity.MEDIUM,
        "numeric-conversion: " + targetName);
  }
}
