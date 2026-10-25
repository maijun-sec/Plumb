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
 * at runtime ? the lock isn't held by
 * the current thread.</p>
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
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
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
    if (!ins.calleeSig.endsWith(".unlock/0")) return;
    if (ins.args == null || ins.args.isEmpty()) return;
    String unlockId = extractLockId(ins.args.get(0));
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
      if (!prev.calleeSig.endsWith(".lock/0")) continue;
      if (prev.args == null || prev.args.isEmpty()) continue;
      String lockId = extractLockId(prev.args.get(0));
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
        unlockId + ".unlock/0",
        name(),
        "plumb." + RULE_ID,
        "Function '"
            + fn.name()
            + "' calls"
            + " '"
            + unlockId
            + ".unlock()' without"
            + " a matching '"
            + unlockId
            + ".lock()' earlier in the function."
            + " The lock is not held by the current"
            + " thread, which throws"
            + " IllegalMonitorStateException at"
            + " runtime (CWE-832).",
        RuleDef.Severity.MEDIUM,
        "CWE-832",
        "unlock-not-locked: " + unlockId + ".unlock()" + " has no matching .lock()");
  }

  private static String extractLockId(TacOperand op) {
    if (op instanceof TacOperand.TacVar tv) {
      String n = tv.name();
      if (n == null) return null;
      int colon = n.indexOf(':');
      return colon >= 0 ? n.substring(0, colon) : n;
    }
    if (op instanceof TacOperand.Const c) {
      return c.value() == null ? null : "const:" + c.value();
    }
    return null;
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }
}
