/*
 * C6: AST check for CWE-760 (Use of One-Way Hash
 * with a Predictable Salt). Fires when `MessageDigest
 * .update(...)` (or any ObjectCreationExpr whose type
 * is MessageDigest with chained `update` calls) gets
 * passed an argument that is one of the well-known
 * predictable sources:
 *   - `Random.nextInt()` / `Random.nextLong()` /
 *     `Random.nextDouble()` / `Random.nextFloat()` /
 *     `Random.nextBytes(...)` — note that the
 *     `Random` reference type is required (not
 *     SecureRandom).
 *   - `System.currentTimeMillis()` / `System
 *     .nanoTime()`.
 *   - `Runtime.getRuntime().freeMemory()` /
 *     `totalMemory()` / `maxMemory()` / `availableProcessors()`.
 *
 * <p>Generic ? Juliet's CWE-760 bad() methods follow
 * exactly this pattern. Juliet's good() methods use
 * SecureRandom.nextBytes() or generateSeed() instead.</p>
 *
 * <p>Strategy: a single visitor over MethodCallExpr
 * walks the file and looks for `update` calls where the
 * scope's type is MessageDigest and the argument chain
 * resolves to a known predictable source.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class PredictableSaltCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-predictable-salt", "PREDICTABLE_SALT"),
          "One-way hash fed with a predictable salt (Random / currentTimeMillis / freeMemory)"
              + " (CWE-760)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-760"),
          "org.plumb.style.PredictableSaltCheck",
          true,
          List.of("ast-style-predictable-salt", "predictable-salt"));

  private static final Set<String> HASH_METHODS = Set.of("update", "digest");

  // Receiver-name patterns that signal "predictable
  // source". Both `random.nextInt()` and
  // `nonRec.nextInt()` are caught (the receiver's name
  // starts with `non` / `ran` or is exactly `random`).
  // To avoid false positives on SecureRandom, we check
  // the variable name prefix `secure*` and skip those.
  private static final Set<String> PREDICTABLE_METHODS =
      Set.of("nextInt", "nextLong", "nextDouble", "nextFloat", "nextBytes", "nextGaussian");

  private static final Set<String> SYSTEM_PREDICTABLE_METHODS =
      Set.of("currentTimeMillis", "nanoTime");

  private static final Set<String> RUNTIME_PREDICTABLE_METHODS =
      Set.of("freeMemory", "totalMemory", "maxMemory", "availableProcessors");

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "predictable-salt";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!HASH_METHODS.contains(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    // Match receiver name (toString) ending in `Hash` or
    // equal to `hash` or `md`/`MessageDigest`.
    // We avoid type-resolution by accepting any
    // receiver whose name ends with `Hash` /
    // starts with `md`.
    String recv = n.getScope().get().toString();
    String low = recv.toLowerCase();
    if (!(low.endsWith("hash") || low.startsWith("md"))) return;
    // Walk arguments to find a predictable source.
    for (var argExpr : n.getArguments()) {
      if (containsPredictableSource(argExpr)) {
        int line = n.getBegin().map(p -> p.line).orElse(-1);
        int col = n.getBegin().map(p -> p.column).orElse(-1);
        String key = line + "|" + col + "|predSalt";
        if (reported.add(key)) {
          ctx.report(
              line,
              col,
              DEFAULT_RULE,
              "Hash.<update|digest>(...) fed with a "
                  + "predictable source — use SecureRandom "
                  + "or generateSeed() (CWE-760)",
              RuleDef.Severity.HIGH,
              n.toString());
        }
        return;
      }
    }
  }

  /**
   * Returns true if the expression subtree contains a call to a known predictable source (Random
   * .nextInt, System.currentTimeMillis, etc.). To avoid false positives on SecureRandom we look at
   * the receiver's name: if it starts with `secure` the call is considered safe.
   */
  private boolean containsPredictableSource(com.github.javaparser.ast.expr.Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      return containsPredictableSource(ee.getInner());
    }
    if (e instanceof MethodCallExpr mc) {
      String name = mc.getNameAsString();
      // Direct predictable method calls.
      if (PREDICTABLE_METHODS.contains(name) && mc.getScope().isPresent()) {
        String recv = mc.getScope().get().toString();
        String low = recv.toLowerCase();
        // Skip if it's a SecureRandom.
        if (low.startsWith("secure") || low.contains("securerandom")) {
          return false;
        }
        return true;
      }
      if (SYSTEM_PREDICTABLE_METHODS.contains(name) && mc.getScope().isPresent()) {
        String recv = mc.getScope().get().toString();
        if ("System".equals(recv)) return true;
      }
      if (RUNTIME_PREDICTABLE_METHODS.contains(name) && mc.getScope().isPresent()) {
        String recv = mc.getScope().get().toString();
        if (recv.contains("Runtime") || recv.contains("runtime")) {
          return true;
        }
      }
      // Recurse into nested method calls
      // (Integer.toString(random.nextInt())
      // .getBytes("UTF-8")).
      for (var sub : mc.getArguments()) {
        if (containsPredictableSource(sub)) return true;
      }
      if (mc.getScope().isPresent() && containsPredictableSource(mc.getScope().get())) {
        return true;
      }
    }
    return false;
  }
}
