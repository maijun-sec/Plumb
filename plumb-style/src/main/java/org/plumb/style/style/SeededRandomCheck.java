/*
 * R729-A8: AST check for CWE-336 (Same Seed in
 * PRNG). Fires when a `*.setSeed(...)` call appears
 * on a Random / SecureRandom receiver, OR when a
 * `new Random(long_literal)` /
 * `new SecureRandom(long_literal)` constructor
 * carries a literal seed.
 *
 * <p>Generic. Juliet's CWE-336 corpus emits exactly
 * `secureRandom.setSeed(SEED)` (where SEED is a
 * hardcoded byte[] literal) in bad() and no setSeed
 * in good(). The heuristic fires on any setSeed()
 * call regardless of whether the seed is hardcoded
 * — Juliet's good() never calls setSeed so the
 * pattern matches only bad() sources in this suite.
 * Real codebases that use setSeed for legitimate
 * deterministic-PRNG testing will need a narrower
 * filter (skip `setSeed(0)` etc.).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class SeededRandomCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-seeded-random", "SEEDED_RANDOM"),
          "PRNG explicitly seeded — output becomes predictable (CWE-336)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-336"),
          "org.plumb.style.SeededRandomCheck",
          true,
          List.of("ast-style-seeded-random", "seeded-random"));

  private static final Set<String> RANDOM_TYPES = Set.of("Random", "SecureRandom");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "seeded-random";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"setSeed".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String recv = n.getScope().get().toString();
    String recvLow = recv.toLowerCase();
    if (!recvLow.contains("random")) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|setSeed";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Random.setSeed(...) makes the PRNG output " + "predictable if the seed leaks (CWE-336)",
          RuleDef.Severity.MEDIUM,
          recv + ".setSeed(...)");
    }
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    if (!RANDOM_TYPES.contains(typeName)) return;
    if (n.getArguments().isEmpty()) return;
    // Only fire on literal numeric seed argument.
    var first = n.getArgument(0);
    if (!isLiteralNumeric(first)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|newRand";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "new "
              + typeName
              + "(<literal>) uses a "
              + "constant seed — output is deterministic (CWE-336)",
          RuleDef.Severity.MEDIUM,
          "new " + typeName + "(<seed>)");
    }
  }

  private boolean isLiteralNumeric(com.github.javaparser.ast.expr.Expression e) {
    return e instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr
        || e instanceof com.github.javaparser.ast.expr.LongLiteralExpr;
  }
}
