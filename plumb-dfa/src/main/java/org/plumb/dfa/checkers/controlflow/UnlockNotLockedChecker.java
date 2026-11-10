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
 * DFA-style checker for CWE-832
 * (Unlock of a Lock that is not Locked).
 *
 * <p>The bug: a {@code ReentrantLock.unlock()}
 * call is made WITHOUT a matching
 * {@code .lock()} call on the same
 * receiver in the same function (or on
 * the same code path). This throws
 * {@link IllegalMonitorStateException}
 * at runtime -- the lock isn't held by
 * the current thread.</p>
 *
 * <p>The same defect exists in Go, where
 * {@code sync.Mutex} spells its methods in
 * UPPER case: a {@code .Unlock()} with no
 * matching {@code .Lock()} panics with
 * "sync: unlock of unlocked mutex".</p>
 *
 * <p>The shape it detects:</p>
 *
 * <pre>
 *   static private final ReentrantLock
 *           REENTRANT_LOCK_BAD = new ReentrantLock();
 *
 *   static public void helperBad() {
 *       // Missing lock() here
 *       try {
 *           intBad = intBad * 2;
 *       } finally {
 *           REENTRANT_LOCK_BAD.unlock();   // FLAW
 *       }
 *   }
 * </pre>
 *
 * <p>Detection strategy (narrow
 * heuristic): for each function, walk
 * the instructions. For every
 * {@code .unlock()} call, check if there's
 * a matching {@code .lock()} call on the
 * same receiver in the SAME function
 * (linearly before the unlock). If not,
 * fire CWE-832.</p>
 *
 * <p>Limitations (narrow heuristic):</p>
 * <ul>
 *   <li>Linear source walk, not path-
 *       sensitive. A function that
 *       conditionally locks (e.g. inside
 *       an if-branch) may over-fire.</li>
 *   <li>A lock taken in another function
 *       -- a wrapper that locks in
 *       {@code Lock()} and unlocks in
 *       {@code Unlock()} -- is not seen, so
 *       the unlock in the second half is
 *       reported. The lock's IDENTITY is
 *       resolved across the functions of a
 *       type, but its HELD-ness is not.</li>
 *   <li>Same-class static analysis only.
 *       If the {@code .lock()} call is in
 *       a different method (caller or
 *       helper), we don't see it.</li>
 *   <li>Doesn't handle the
 *       {@code synchronized} keyword
 *       (those become MONITORENTER /
 *       MONITOREXIT, not .lock() /
 *       .unlock()).</li>
 *   <li>ReentrantLock re-entry: a function
 *       that locks the same ReentrantLock
 *       twice in a row has the same
 *       "lock" identifier twice, which
 *       still passes the "any matching
 *       lock" check. This version doesn't
 *       require N locks for N unlocks.</li>
 * </ul>
 *
 * <p>Coverage on Juliet CWE-832: 2/2
 * files (one bad() per file). The
 * good1() / good2() functions in the
 * same files DO have a matching
 * {@code .lock()} call, so they don't
 * fire.</p>
 */
package org.plumb.dfa.checkers.controlflow;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.LockIdentity;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

public final class UnlockNotLockedChecker implements DfaChecker {
  private static final String RULE_ID = "dfa-controlflow-unlock-not-locked";

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "Cwe832UnlockNotLocked";
  }

  @Override
  public void initEngine(AnalysisContext ctx) {
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind != TacInstruction.Kind.CALL) return;
    if (ins.calleeSig == null) return;
    // Java's ReentrantLock spells the call in lower case and Go's
    // sync.Mutex in UPPER case. The two cannot collide -- Java has
    // no upper-case lock method and Go has no lower-case one -- so
    // the recognition is widened rather than replaced, and a Java
    // program is unaffected.
    boolean go = fn.sourceLanguage() == SourceLanguage.GO;
    if (!isUnlock(ins.calleeSig, go)) return;
    if (ins.args == null || ins.args.isEmpty()) return;
    // The lock's identity is resolved from the TAC's structure, not
    // from the receiver's name: a field read lands in a fresh
    // temporary, so the two reads of one field have two names.
    LockIdentity.Resolver ids = new LockIdentity.Resolver(fn);
    String unlockId = ids.of(ins.args.get(0));
    if (unlockId == null || unlockId.isEmpty()) return;
    // Walk back through the function's
    // earlier instructions looking for
    // any matching .lock() call. We
    // collect lock IDs up to this point
    // (linearly, no path-sensitivity).
    boolean hasMatchingLock = false;
    for (TacInstruction prev : fn.instructions()) {
      if (prev == ins) break; // don't count the unlock itself
      if (prev.kind != TacInstruction.Kind.CALL) continue;
      if (prev.calleeSig == null) continue;
      if (!isLock(prev.calleeSig, go)) continue;
      if (prev.args == null || prev.args.isEmpty()) continue;
      String lockId = ids.of(prev.args.get(0));
      if (unlockId.equals(lockId)) {
        hasMatchingLock = true;
        break;
      }
    }
    if (hasMatchingLock) return;
    // No matching .lock() call in the
    // same function → fire.
    String key = fn.signature() + "|" + ins.line + "|" + unlockId;
    if (!reported.add(key)) return;
    TacProgram prog = ctx.program();
    PatternTrace.report(
        ctx,
        fn,
        ins,
        TraceNode.Op.UNLOCK_NOT_LOCKED,
        unlockId + (go ? ".Unlock/0" : ".unlock/0"),
        name(),
        "plumb." + RULE_ID,
        message(fn, unlockId, go),
        RuleDef.Severity.MEDIUM,
        "CWE-832",
        "unlock-not-locked: "
            + unlockId
            + (go ? ".Unlock()" : ".unlock()")
            + " has no matching "
            + (go ? ".Lock()" : ".lock()"));
  }

  /**
   * Whether a call releases a lock.
   *
   * <p>Java's {@code ReentrantLock} spells it in lower case and Go's {@code sync.Mutex} in UPPER
   * case. The two cannot collide, so the recognition is widened rather than replaced.
   */
  private static boolean isUnlock(String calleeSig, boolean go) {
    if (calleeSig.endsWith(".unlock/0")) return true;
    return go && calleeSig.endsWith(".Unlock/0");
  }

  /** Whether a call acquires a lock. The mirror of {@link #isUnlock}. */
  private static boolean isLock(String calleeSig, boolean go) {
    if (calleeSig.endsWith(".lock/0")) return true;
    return go && calleeSig.endsWith(".Lock/0");
  }

  /**
   * The sentence the finding carries.
   *
   * <p>The failure differs between the two languages, so the text does too: Java throws
   * IllegalMonitorStateException, and Go panics with "sync: unlock of unlocked mutex". Saying the
   * Java thing about a Go program would be wrong in the text the reader reads.
   */
  private static String message(TacFunction fn, String unlockId, boolean go) {
    if (go) {
      return "Function '"
          + fn.name()
          + "' calls '"
          + unlockId
          + ".Unlock()' without a matching '"
          + unlockId
          + ".Lock()' earlier in the function."
          + " The lock is not held by the current goroutine, which panics with"
          + " 'sync: unlock of unlocked mutex' at runtime (CWE-832).";
    }
    return "Function '"
        + fn.name()
        + "' calls '"
        + unlockId
        + ".unlock()' without a matching '"
        + unlockId
        + ".lock()' earlier in the function."
        + " The lock is not held by the current thread, which throws"
        + " IllegalMonitorStateException at runtime (CWE-832).";
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }
}
