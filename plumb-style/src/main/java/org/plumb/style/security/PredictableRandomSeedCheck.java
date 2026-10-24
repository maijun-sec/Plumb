/*
 * R-812 ? PredictableRandomSeedCheck (CWE-332 / CWE-336)
 * migrated from plumb-dfa/.../PredictableRandomSeedChecker.java
 * to plumb-style AST.
 *
 * <p>Two patterns fire:</p>
 * <ol>
 *   <li>{@code new Random(42)} or {@code new SecureRandom(seed)} —
 *       the seed is a compile-time int / long literal,
 *       so the resulting sequence is deterministic.</li>
 *   <li>{@code random.setSeed(N)} where {@code N} is a
 *       compile-time int / long literal. The legacy
 *       AST-only check covers the ctor; this version
 *       also covers the setter.</li>
 * </ol>
 *
 * <p>Variable seeds (e.g. {@code System.currentTimeMillis()})
 * are out of scope here ? the DFA half used a
 * const-var tracker that the AST half does not
 * re-implement.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class PredictableRandomSeedCheck implements AstChecker {

  private static final Set<String> RNG_TYPES = Set.of("Random", "SecureRandom");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-predictable-random-seed", "PREDICTABLE_RANDOM_SEED"),
          "Predictable random-number seed (CWE-332 / CWE-336)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-332", "CWE-336"),
          "org.plumb.style.security.PredictableRandomSeedCheck",
          true,
          List.of("dfa-security-predictable-rng-seed", "predictable-random-seed"));

  @Override
  public String name() {
    return "predictable-random-seed";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!RNG_TYPES.contains(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    com.github.javaparser.ast.expr.Expression seed = n.getArgument(0);
    if (!isIntOrLongLiteral(seed)) return;
    emit(n, ctx, typeName, n.toString());
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"setSeed".equals(n.getNameAsString())) return;
    if (n.getArguments().isEmpty()) return;
    com.github.javaparser.ast.expr.Expression arg = n.getArgument(0);
    if (!isIntOrLongLiteral(arg)) return;
    // setSeed is a method on a Random / SecureRandom
    // receiver; the call is enough to fire (no need
    // to inspect the receiver type — the AST half is
    // conservative).
    emit(n, ctx, "setSeed", n.toString());
  }

  private static boolean isIntOrLongLiteral(com.github.javaparser.ast.expr.Expression e) {
    return e instanceof IntegerLiteralExpr || e instanceof LongLiteralExpr;
  }

  private void emit(com.github.javaparser.ast.Node n, StyleContext ctx, String what, String expr) {
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Predictable random seed: "
            + what
            + " with a compile-time literal — "
            + "use System.currentTimeMillis() or a "
            + "SecureRandom without a seed (CWE-332)",
        RuleDef.Severity.MEDIUM,
        expr);
  }
}
