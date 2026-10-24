package org.plumb.style.performance;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * {@code ==} / {@code !=} comparison on a {@link String} value. Use {@code .equals()} instead.
 *
 * <p>S3 audit (2026-10-17) replaced the previous name-heuristic with JavaParser's type resolver.
 * The old code accepted any {@code NameExpr} whose text contained {@code
 * str/name/text/msg/word/label} which produced both false positives ({@code stream}, {@code
 * strong}, {@code buffer}, etc.) and false negatives ({@code query}, {@code command}, {@code
 * input}). The new code asks {@code StyleContext.resolvedTypeOf(...)} whether the operand is
 * actually {@code java.lang.String}.
 *
 * <p>When the type solver cannot resolve (e.g. the project isn't on the classpath or the symbol is
 * declared in a different module), we fall back to: a {@code StringLiteralExpr} is always
 * considered string-like, and a {@code NameExpr} whose lowercased name contains {@code string}
 * (case-insensitive, very narrow) is considered string-like. We do NOT continue the old broad
 * substring heuristic ? that produced more noise than signal.
 */
public final class CompareStringsWithEqualsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-string-eq", "STRING_EQ"),
          "use.equals() not == for String comparison",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-595"),
          "org.plumb.style.CompareStringsWithEqualsCheck",
          true,
          List.of("ast-performance-compare-strings-with-equals", "string-eq"));

  @Override
  public String name() {
    return "string-eq";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(BinaryExpr n, StyleContext ctx) {
    if (n.getOperator() != BinaryExpr.Operator.EQUALS
        && n.getOperator() != BinaryExpr.Operator.NOT_EQUALS) {
      return;
    }
    // S3: require BOTH sides to be string-like. Previously
    // the rule required just one side to look like a string
    // (using a name heuristic), which produced false
    // positives on mixed-type comparisons where one side was
    // a String and the other was, say, an Object.
    boolean left = isStringLike(n.getLeft(), ctx);
    boolean right = isStringLike(n.getRight(), ctx);
    if (!left || !right) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "== comparison on String \u2014 use .equals()",
        RuleDef.Severity.HIGH,
        "==");
  }

  /**
   * True when {@code e} is a {@link String} value or {@code String} reference. Resolution order:
   * string literal (always), then JavaParser's type solver, then a very narrow name fallback when
   * the solver fails.
   */
  private static boolean isStringLike(
      com.github.javaparser.ast.expr.Expression e, StyleContext ctx) {
    if (e instanceof StringLiteralExpr) return true;
    // Try the type solver first. StyleContext already
    // catches RuntimeException and logs at DEBUG so we
    // don't need a try/catch here.
    if (ctx != null) {
      ResolvedType rt = ctx.resolvedTypeOf(e);
      if (rt != null) {
        return "java.lang.String".equals(rt.describe());
      }
    }
    // Fallback: only accept names that *literally*
    // contain "string" (case-insensitive). This is
    // narrower than the old heuristic but still catches
    // common patterns like `myString`, `theStringValue`,
    // `STRING_CONSTANT`. The old heuristic also caught
    // `stream`, `strong`, `instruct`, etc., which was
    // the source of the false positives.
    if (e instanceof com.github.javaparser.ast.expr.NameExpr ne) {
      String lower = ne.getNameAsString().toLowerCase();
      return lower.contains("string");
    }
    return false;
  }
}
