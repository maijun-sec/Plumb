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
 * C1: AST-style CWE-338 (Use of
 * Cryptographically Weak Pseudo-Random Number
 * Generator / PRNG) checker.
 *
 * <p>Fires on:</p>
 * <ul>
 *   <li>{@code Math.random()} -- non-cryptographic
 *       PRNG, returns {@code double} in [0,1).</li>
 *   <li>{@code new java.util.Random()} /
 *       {@code new java.util.Random(seed)} —
 *       non-cryptographic PRNG. Generic; does not
 *       require type-aware analysis.</li>
 * </ul>
 *
 * <p>The good pattern is
 * {@code new java.security.SecureRandom(...)} which
 * is out of scope here.</p>
 *
 * <p>Generic, no Juliet-specific patterns. Safe to
 * use on real code; FP risk is low because
 * {@code Math.random} / {@code new Random()} are
 * almost always wrong in a security context
 * (session token, password reset URL, salt, IV,
 * nonce, etc.).</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class WeakPrngCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-weak-prng", "WEAK_PRNG"),
          "Use of cryptographically weak PRNG (Math.random / new Random) (CWE-338)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-338"),
          "org.plumb.style.security.WeakPrngCheck",
          true,
          List.of("ast-security-weak-prng", "weak-prng"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "weak-prng";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"random".equals(n.getNameAsString())) return;
    if (n.getScope().isEmpty()) return;
    String recv = n.getScope().get().toString();
    // Match "Math.random()" but NOT "Math.something.random()" or
    // "someMath.random()" (the latter would be an instance method).
    // We require the receiver to be exactly "Math".
    if (!"Math".equals(recv)) return;
    fire(n, ctx, "Math.random()");
  }

  @Override
  public void visit(ObjectCreationExpr n, StyleContext ctx) {
    String typeName = n.getType().getNameAsString();
    // We want `new Random(...)` but NOT `new SecureRandom(...)` or
    // `new java.security.SecureRandom(...)` etc. The simple-name
    // check is sufficient for Juliet and most real-world code
    // because `Random` is a unique simple name in the JDK.
    if (!"Random".equals(typeName)) return;
    fire(n, ctx, "new " + typeName + "(...)");
  }

  private void fire(com.github.javaparser.ast.Node n, StyleContext ctx, String display) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|weakPrng";
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          RULE,
          "Cryptographically weak PRNG: "
              + display
              + " \u2014 use java.security.SecureRandom for security-sensitive values (CWE-338)",
          RuleDef.Severity.MEDIUM,
          display);
    }
  }
}
