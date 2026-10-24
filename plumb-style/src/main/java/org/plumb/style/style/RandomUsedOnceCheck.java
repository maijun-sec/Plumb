package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** new Random() used only once. */
public final class RandomUsedOnceCheck implements AstChecker {

  private StyleContext ctx;
  private RuleDef rule;

  @Override
  public String name() {
    return "dmi-random-used-only-once";
  }

  @Override
  public void init(StyleContext ctx) {
    this.ctx = ctx;
    this.rule = ctx.rules().byId(RuleRegistry.RANDOM_USED_ONCE).orElse(null);
  }

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!(n.getScope().orElse(null) instanceof ObjectCreationExpr ctor)) {
      return;
    }
    String typeName = ctor.getType().asString();
    // Match "Random" (imported) or "java.util.Random"
    // (FQN). Subclasses of Random (e.g. SecureRandom)
    // are not in scope for this rule per design.
    if (!"Random".equals(typeName) && !"java.util.Random".equals(typeName)) {
      return;
    }
    // The chain call's name should be one of the
    // common Random methods. (Anything else means
    // the user is doing something custom.)
    String method = n.getNameAsString();
    if (!isRandomMethod(method)) return;
    int line = n.getRange().map(r -> r.begin.line).orElse(-1);
    int col = n.getRange().map(r -> r.begin.column).orElse(-1);
    this.ctx.report(
        line,
        col,
        this.rule,
        "new Random() is used "
            + "only once; share a single Random instance or use "
            + "ThreadLocalRandom.current() to avoid wasting the "
            + "entropy pool",
        RuleDef.Severity.LOW,
        n.toString());
  }

  private static boolean isRandomMethod(String name) {
    return switch (name) {
      case "nextInt",
          "nextLong",
          "nextDouble",
          "nextFloat",
          "nextBoolean",
          "nextBytes",
          "nextGaussian",
          "ints",
          "longs",
          "doubles" ->
          true;
      default -> false;
    };
  }
}
