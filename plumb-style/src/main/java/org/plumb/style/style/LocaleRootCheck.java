package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** locale-sensitive API without explicit Locale. */
public final class LocaleRootCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-locale-root", "LOCALE_ROOT"),
          "locale-sensitive API without explicit Locale",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-758"),
          "org.plumb.style.LocaleRootCheck",
          true,
          List.of("ast-style-locale-root", "locale-root"));

  private static final Set<String> METHODS =
      Set.of(
          "toLowerCase",
          "toUpperCase",
          "format",
          "getDisplayName",
          "getDisplayLanguage",
          "getDisplayCountry",
          "getDisplayVariant");

  @Override
  public String name() {
    return "locale-root";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!METHODS.contains(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    // Narrow: only String.toLowerCase / toUpperCase
    // with no argument. The format methods are
    // overloaded; we'd need a heuristic for the
    // call site.
    String name = n.getNameAsString();
    if (!"toLowerCase".equals(name) && !"toUpperCase".equals(name)) return;
    if (n.getArguments().size() != 0) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "String." + name + "() without Locale : pass Locale.ROOT for portability",
        RuleDef.Severity.LOW,
        name);
  }
}
