/*
 * Use of broken / risky cryptographic algorithm.
 *
 * <p>M7 audit (2026-10-17): the previous implementation held its weak-algorithm table as a
 * private {@code HashMap} inline. Operators wanting to extend the list (e.g. add a vendor-
 * specific algorithm) had to find and edit the checker's source. The algorithm table is now
 * {@link CheckerConfig#weakCryptoAlgorithms()}; new entries can be added by editing the central
 * config (a single file) rather than 591 checkers.
 *
 * <p>R-AST-FIX-2026-10-18 (Pass A): migrated to per-type {@code visit(MethodCallExpr, ...)}.
 *
 * <p>R-AST-FIX-2026-10-18 (Pass B cleanup): previous version called {@code ruleFor(message)} once
 * per finding ? allocating a fresh {@code RuleDef} per emission. That defeated the RuleRegistry's
 * alias-resolution cache (StyleContext#report uses RuleDef identity to short-circuit redundant
 * lookups). Single DEFAULT_RULE now serves all emissions; the CWE tag from the algorithm-
 * specific message is informational only.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;
import org.plumb.style.config.CheckerConfig;

public final class InsecureCryptoCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-insecure-crypto", "INSECURE_CRYPTO"),
          "Use of broken / risky cryptographic algorithm",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-327"),
          "org.plumb.style.InsecureCryptoCheck",
          /* enabled */ true,
          List.of("ast-security-insecure-crypto", "insecure-crypto")
      );

  @Override
  public String name() {
    return "insecure-crypto";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr m, StyleContext ctx) {
    // M7: pull the lookup table from CheckerConfig so
    // operators have a single place to extend the weak-
    // algorithm list. The map is immutable so no
    // defensive copy is needed.
    Map<String, String> weak = CheckerConfig.weakCryptoAlgorithms();

    // We only care about getInstance-style factories:
    // the algorithm name is the first argument.
    if (!"getInstance".equals(m.getNameAsString())) return;
    if (m.getArguments().isEmpty()) return;
    if (!(m.getArgument(0) instanceof StringLiteralExpr s)) return;
    String algo = s.getValue().toLowerCase();
    String message = weak.get(algo);
    if (message == null) return;
    int line = m.getBegin().map(p -> p.line).orElse(-1);
    int col = m.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Use of weak cryptographic algorithm: " + algo + " — " + message,
        RuleDef.Severity.HIGH,
        algo);
  }
}
