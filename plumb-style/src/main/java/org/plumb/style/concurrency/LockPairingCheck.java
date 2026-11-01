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
package org.plumb.style.concurrency;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * A lock is taken and released the wrong number of times (CWE-765 / CWE-832 / CWE-667).
 *
 * <p>Three ways to get the pairing wrong, and they are the same defect class -- a lock whose
 * acquire/release count does not balance:
 *
 * <ul>
 *   <li><b>CWE-765</b> -- released MORE times than it was taken. A second {@code unlock()} on a
 *       lock that was locked once throws {@code IllegalMonitorStateException}, and it releases a
 *       lock some other thread is holding.
 *   <li><b>CWE-832</b> -- released without ever being taken. Same exception, and the protected
 *       state was never protected at all.
 *   <li><b>CWE-667</b> -- taken and never released. The protected state stays locked forever, and
 *       every other thread blocks on it.
 * </ul>
 *
 * <p>A rule keeps ONE id per defect class, so all three numbers are carried on the same rule.
 *
 * <p><b>The counting is per lock, per method.</b> A lock acquired in one method and released in
 * another is a different program shape, and one that this rule does not claim to understand. The
 * analysis therefore counts the {@code lock()} and {@code unlock()} calls on each lock <em>within
 * one method</em>, and only reports an imbalance when both kinds of call are visible there.
 *
 * <p>A lock that is taken but never released inside the method is only reported when the method
 * releases no lock at all for that lock -- otherwise the release may happen in the caller, which is
 * a shape this rule does not judge.
 */
public final class LockPairingCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-concurrency-lock-pairing", "LOCK_PAIRING"),
          "A lock is taken and released an unbalanced number of times (CWE-765)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-765", "CWE-832", "CWE-667"),
          "org.plumb.style.concurrency.LockPairingCheck",
          true,
          List.of("ast-concurrency-multiple-unlock", "lock-pairing"));

  @Override
  public String name() {
    return "lock-pairing";
  }

  @Override
  public void init(StyleContext ctx) {
    // stateless
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    analyseScope(n, ctx);
  }

  private void analyseScope(Node scope, StyleContext ctx) {
    // Count lock()/unlock() per lock expression, in source order.
    Map<String, Integer> lockCounts = new LinkedHashMap<>();
    Map<String, Integer> unlockCounts = new LinkedHashMap<>();
    Map<String, Integer> unlockLines = new LinkedHashMap<>();

    List<MethodCallExpr> calls = scope.findAll(MethodCallExpr.class);

    for (MethodCallExpr call : calls) {
      String method = call.getNameAsString();
      if (!method.equals("lock") && !method.equals("unlock")) continue;

      String receiver = lockReceiver(call);
      if (receiver == null) continue;

      if (method.equals("lock")) {
        lockCounts.merge(receiver, 1, Integer::sum);
      } else {
        unlockCounts.merge(receiver, 1, Integer::sum);
        unlockLines.putIfAbsent(receiver, call.getBegin().map(p -> p.line).orElse(-1));
      }
    }

    for (Map.Entry<String, Integer> entry : unlockCounts.entrySet()) {
      String lock = entry.getKey();
      int unlocks = entry.getValue();
      int locks = lockCounts.getOrDefault(lock, 0);
      int line = unlockLines.getOrDefault(lock, -1);

      if (unlocks > locks) {
        // More releases than acquisitions: CWE-765 and CWE-832 together.
        int col = colOf(scope, lock, line);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "'"
                + lock
                + "' is unlocked "
                + unlocks
                + " time(s) but locked "
                + locks
                + " time(s); the extra unlock() throws "
                + "IllegalMonitorStateException (CWE-765)",
            RuleDef.Severity.MEDIUM,
            lock);
      } else if (locks == 0) {
        // No acquisition at all in this method: the release has nothing to
        // release. CWE-832.
        int col = colOf(scope, lock, line);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "'" + lock + "' is unlocked without ever being locked (CWE-832)",
            RuleDef.Severity.MEDIUM,
            lock);
      }
    }

    // A lock taken and never released, when the method does not release that
    // lock at all: the lock is still held when the method returns. CWE-667.
    for (Map.Entry<String, Integer> entry : lockCounts.entrySet()) {
      String lock = entry.getKey();
      int locks = entry.getValue();
      int unlocks = unlockCounts.getOrDefault(lock, 0);
      if (locks > unlocks && unlocks == 0) {
        MethodCallExpr lockCall = firstLockCall(scope, lock);
        if (lockCall == null) continue;
        int line = lockCall.getBegin().map(p -> p.line).orElse(-1);
        int col = lockCall.getBegin().map(p -> p.column).orElse(-1);
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "'"
                + lock
                + "' is locked but never unlocked; the lock is still "
                + "held when this method returns (CWE-667)",
            RuleDef.Severity.MEDIUM,
            lock);
      }
    }
  }

  /**
   * The text of the lock a call operates on, or null when the call is not a lock operation on a
   * named lock.
   */
  private static String lockReceiver(MethodCallExpr call) {
    if (call.getScope().isEmpty()) return null;
    // `LOCK.lock()` and `this.LOCK.lock()` both name the same lock; the
    // receiver text is enough to tell two locks apart.
    String scope = call.getScope().get().toString();
    if (scope.isEmpty()) return null;
    return scope;
  }

  private static MethodCallExpr firstLockCall(Node scope, String lock) {
    List<MethodCallExpr> calls = scope.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      if (!call.getNameAsString().equals("lock")) continue;
      if (call.getScope().isEmpty()) continue;
      if (call.getScope().get().toString().equals(lock)) return call;
    }
    return null;
  }

  private static int colOf(Node scope, String lock, int line) {
    List<MethodCallExpr> calls = scope.findAll(MethodCallExpr.class);
    for (MethodCallExpr call : calls) {
      if (!call.getNameAsString().equals("unlock")) continue;
      int callLine = call.getBegin().map(p -> p.line).orElse(-1);
      if (callLine != line) continue;
      if (call.getScope().isEmpty()) continue;
      if (call.getScope().get().toString().equals(lock)) {
        return call.getBegin().map(p -> p.column).orElse(-1);
      }
    }
    return -1;
  }
}
