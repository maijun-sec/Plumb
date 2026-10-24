package org.plumb.style.style;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** String.toLowerCase/toUpperCase without Locale (locale-dependent). */
public final class LocaleDependentCaseConversionCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-locale-dependent-case-conversion", "LOCALE_DEPENDENT_CASE_CONVERSION"),
          "String.toLowerCase/toUpperCase without Locale (locale-dependent)",
          RuleDef.Severity.LOW,
          RuleDef.Type.I18N,
          List.of(),
          "org.plumb.style.LocaleDependentCaseConversionCheck",
          true,
          List.of(
              "ast-style-locale-dependent-case-conversion", "locale-dependent-case-conversion"));

  private static final Set<String> CASE_METHODS = Set.of("toLowerCase", "toUpperCase");

  @Override
  public String name() {
    return "locale-dependent-case-conversion";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!CASE_METHODS.contains(n.getNameAsString())) return;
    // Must be a no-arg call. With-arg calls (Locale
    // parameterized) are correct.
    if (n.getArguments().size() != 0) return;
    // Receiver must be a String-like type. We use a
    // narrow whitelist on the receiver name.
    final java.util.Optional<Expression> scope = n.getScope();
    if (scope.isEmpty()) return;
    final Expression s = scope.get();
    if (!(s instanceof NameExpr)) return;
    final String recName = ((NameExpr) s).getNameAsString();
    // Narrow to likely String variables: contains
    // "str" / "name" / "text" / "key" or starts with
    // "s" + 1+ chars. This is the lightweight check;
    // a full type-resolver-based approach
    // ('s exact detector) is out of scope.
    if (!isLikelyStringName(recName)) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Use Locale-parameterized toLowerCase()/toUpperCase() to avoid locale-dependent conversion",
        RuleDef.Severity.LOW,
        n.getNameAsString());
  }

  private static boolean isLikelyStringName(String name) {
    String lower = name.toLowerCase();
    if (lower.contains("str")) return true;
    if (lower.contains("name")) return true;
    if (lower.contains("text")) return true;
    if (lower.contains("key")) return true;
    if (lower.contains("msg")) return true;
    if (lower.contains("line")) return true;
    return false;
  }
}
