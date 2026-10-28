/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * A8: AST check for CWE-336 (Same Seed in
 * PRNG). Fires when a `*.setSeed(...)` call appears
 * on a Random / SecureRandom receiver, OR when a
 * `new Random(long_literal)` /
 * `new SecureRandom(long_literal)` constructor
 * carries a literal seed.
 *
 * <p>Generic: the heuristic fires on any {@code setSeed()} call. Code that uses
 * {@code setSeed} for legitimate deterministic-PRNG testing needs a narrower
 * filter (skip {@code setSeed(0)} etc.).</p>
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
