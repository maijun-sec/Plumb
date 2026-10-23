package org.plumb.style.performance;

import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** arr.toString() / arr.hashCode() should use Arrays.toString / Arrays.hashCode. */
public final class ArrayHashCodeAndToStringCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-array-hashcode-tostring", "ARRAY_HASHCODE_TOSTRING"),
          "arr.toString() / arr.hashCode() should use Arrays.toString / Arrays.hashCode",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-477"),
          "org.plumb.style.ArrayHashCodeAndToStringCheck",
          true,
          List.of("ast-performance-hashcode-tostring", "array-hashcode-tostring")
      );

  /**
   * Names that indicate an array variable. We use a narrow whitelist: names ending with "[]" or
   * containing common array suffixes. This is the lightweight check; a full type-resolver-based
   * approach (Type::isArray based on full type resolution) is out of scope. Note: the "s" suffix is
   * included for plural names (>= 3 chars to avoid over-firing on single-letter names like "s" for
   * "string").
   */
  private static final Set<String> ARRAY_NAME_SUFFIXES =
      Set.of(
          "[]",
          "list",
          "array",
          "buf",
          "buffer",
          "data",
          "items",
          "values",
          "keys",
          "entries",
          "arrs",
          "bufs",
          "dataList",
          "keySet",
          "entrySet");

  @Override
  public String name() {
    return "array-hashcode-tostring";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    // Must be a no-arg call to toString or hashCode.
    if (n.getArguments().size() != 0) return;
    final String name = n.getNameAsString();
    if (!"toString".equals(name) && !"hashCode".equals(name)) return;
    // Receiver must be a name expression, this, super,
    // or array access (we skip array access : those
    // return elements, not the array).
    final Optional<Expression> scope = n.getScope();
    if (scope.isEmpty()) return;
    final Expression s = scope.get();
    if (s instanceof NameExpr ne) {
      if (!isLikelyArrayName(ne.getNameAsString())) return;
    } else if (s instanceof ThisExpr || s instanceof SuperExpr) {
      // this.toString() or super.toString() on the
      // current class : out of scope.
      return;
    } else if (s instanceof ArrayAccessExpr) {
      return;
    } else {
      return;
    }
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Use Arrays."
            + name
            + "(array) instead of array."
            + name
            + "() (returns identity-based value)",
        RuleDef.Severity.MEDIUM,
        name);
  }

  private static boolean isLikelyArrayName(String name) {
    for (String suffix : ARRAY_NAME_SUFFIXES) {
      if (name.endsWith(suffix)) return true;
    }
    // Plural "s" suffix for >= 3-char names like "arrs", "bufs"
    if (name.length() >= 3 && name.endsWith("s")) return true;
    return false;
  }
}
