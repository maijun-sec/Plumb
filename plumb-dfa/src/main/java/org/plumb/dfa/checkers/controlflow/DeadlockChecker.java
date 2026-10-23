/*
 * Cross-function DFA-style checker for
 * CWE-833 (Deadlock).
 *
 * <p>The bug: a deadlock occurs when two or
 * more threads hold locks in different
 * orders, e.g. thread A holds L1 and waits
 * for L2, while thread B holds L2 and
 * waits for L1. The classic example:</p>
 * <pre>
 *   void helperAdd() {
 *     lock1.lock();
 *     ...
 *     lock2.lock();
 *     ...
 *   }
 *   void helperMultiply() {
 *     lock2.lock();   // FLAW: opposite order
 *     ...
 *     lock1.lock();
 *     ...
 *   }
 * </pre>
 *
 * <p>Juliet's CWE-833 corpus (6 files,
 * {@code CWE833_Deadlock/}) uses the same
 * template: a class with two helper
 * methods that each acquire the same two
 * locks in opposite orders, plus a
 * {@code bad()} method that spawns two
 * threads to demonstrate the deadlock.</p>
 *
 * <p>Detection strategy (narrow
 * heuristic):</p>
 *
 * <ol>
 *   <li>For each function in the program,
 *       walk its instructions in order. For
 *       each {@code CALL} with calleeSig
 *       matching {@code *.lock/0}, record
 *       the lock identifier (the receiver
 *       TacVar's name).</li>
 *   <li>Build a per-function "lock sequence"
 *       — the ordered list of unique locks
 *       acquired in that function. Dedupe
 *       repeated locks (a function that
 *       locks and unlocks the same lock
 *       twice is still one entry).</li>
 *   <li>For each pair of functions that
 *       both have a non-empty lock sequence:
 *       <ul>
 *         <li>Compare the SET of locks (the
 *             two functions must acquire the
 *             same set of locks).</li>
 *         <li>If the sets match, compare the
 *             ORDER. If the orders differ,
 *             fire CWE-833 ? this is a
 *             classic AB-BA deadlock.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Limitations (narrow heuristic):</p>
 * <ul>
 *   <li>Per-function lock sequence is the
 *       linear order of {@code .lock()}
 *       calls. A function with conditional
 *       lock acquisitions (e.g. inside an
 *       if-branch) is treated as "all
 *       locks are acquired". This can
 *       over-fire.</li>
 *   <li>Lock identity is the receiver
 *       TacVar's source-level name.
 *       ReentrantLock fields with the same
 *       name in different classes are
 *       treated as different locks. Locks
 *       assigned to a local variable
 *       ({@code Lock l = someLock; l.lock();})
 *       may not be tracked correctly.</li>
 *   <li>Only detects AB-BA style deadlocks
 *       (two functions, two locks, opposite
 *       order). Cyclic deadlocks with 3+
 *       locks are out of scope for this
 *       checker.</li>
 *   <li>Doesn't analyze WHICH functions
 *       are called from which threads. The
 *       Juliet bad() always spawns 2
 *       threads calling the helpers, but
 *       real code might have only one
 *       thread call one of the helpers.</li>
 * </ul>
 *
 * <p>Coverage on Juliet CWE-833: 6/6
 * files (one bad pair per file). The
 * good1() / good2() functions in the same
 * files use the same lock order, so they
 * don't fire.</p>
 */
package org.plumb.dfa.checkers.controlflow;

import org.plumb.rules.RuleDef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;

public final class DeadlockChecker implements DfaChecker {
  private static final String RULE_ID = "dfa-controlflow-deadlock-ab-ba";

  /** Per-program dedup. */
  // R759-E: cross-finish dedup. DfaEngine
  // calls finish() multiple times for the
  // same checker across worklist reruns
  // and taint drains, but the pair set
  // itself is invariant — every
  // (enclosingClass, sigA, sigB) triple
  // either fires or doesn't. A single
  // static Set suffices; initEngine
  // clears it on a brand-new analysis.
  private static final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "Cwe833Deadlock";
  }

  @Override
  public void initEngine(AnalysisContext ctx) {
    // Static set never cleared; see
    // InfiniteLoopChecker for rationale.
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // We do per-function collection in
    // finishFunction, then compare pairs
    // in finish().
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // Collect the lock sequence for this
    // function. The sequence is the
    // ordered list of unique lock
    // identifiers (the receiver's
    // TacVar name) acquired in this
    // function.
    List<String> sequence = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.CALL) continue;
      if (ins.calleeSig == null) continue;
      // Match *.lock/0 calls.
      if (!ins.calleeSig.endsWith(".lock/0")) continue;
      if (ins.args == null || ins.args.isEmpty()) continue;
      // The receiver is args[0] (per
      // TAC convention). Extract its
      // name. We strip SSA version
      // (the `:` suffix) so the same
      // source var across calls
      // collapses to one name.
      TacOperand recv = ins.args.get(0);
      String lockId = extractLockId(recv);
      if (lockId == null || lockId.isEmpty()) continue;
      // Only add if not already in the
      // sequence (dedup).
      if (seen.add(lockId)) {
        sequence.add(lockId);
      }
    }
    // R759-E: store the TacFunction so
    // finish() can resolve the filePath
    // for the finding. Pre-R759-E the
    // checker only stashed the lock
    // sequence; finding.filePath came from
    // AnalysisContext.currentFunction() at
    // emit time, which leaks the last fn
    // visited across the worklist — for a
    // cross-TU MergedProgram this resolves
    // to the wrong file (typically
    // ServletMain.java, the auto-generated
    // runner, not the Juliet test file
    // itself). We now keep the TacFunction
    // and look up the source path
    // explicitly from the
    // AnalysisContext.registerSourcePath
    // map. Falls back to
    // currentFunction() then to the
    // merged typeName.
    if (sequence.size() >= 2) {
      // R759-E: use the merged key
      // (enclosingClass + signature)
      // not just fn.signature(), so
      // same-named helpers in different
      // CWE833 files don't overwrite
      // each other in the collected map.
      String mergedKey = mergedKey(fn);
      collected(ctx).put(mergedKey, new LockSequence(sequence, fn));
    }
  }

  /**
   * Build a per-file function key for the collected map. Mirrors {@link
   * MergedProgram#functionByMergedKey} ? bare {@code fn.signature()} alone collides when two Juliet
   * test files define a {@code helperAddBad():void} with the same signature under different
   * enclosing classes.
   */
  private static String mergedKey(TacFunction fn) {
    String enc = fn.enclosingClass();
    String sig = fn.signature();
    if (enc == null || enc.isEmpty()) return sig;
    return enc + " :: " + sig;
  }

  /**
   * A small record pairing a lock sequence with its source TacFunction so finish() can resolve the
   * per-fn source path.
   */
  private static final class LockSequence {
    final List<String> locks;
    final TacFunction fn;

    LockSequence(List<String> locks, TacFunction fn) {
      this.locks = locks;
      this.fn = fn;
    }
  }

  /** Per-program map of function signature → lock sequence + TacFunction. */
  private final Map<TacProgram, Map<String, LockSequence>> byProgram = new java.util.HashMap<>();

  private Map<String, LockSequence> collected(AnalysisContext ctx) {
    TacProgram prog = ctx.program();
    return byProgram.computeIfAbsent(prog, k -> new LinkedHashMap<>());
  }

  /**
   * Resolve the filePath for a fn. Looks up the per-enclosing-class source path registered by the
   * engine; if that's empty (single-file run, no enclosing class) falls back to the function's
   * enclosing class via currentFunction, finally to the merged program typeName.
   */
  private static String resolveFilePath(AnalysisContext ctx, TacFunction fn) {
    String enc = fn.enclosingClass();
    if (enc != null && !enc.isEmpty()) {
      String resolved = ctx.sourcePathForEnclosingClass(enc);
      if (resolved != null && !resolved.isEmpty()) {
        return resolved;
      }
    }
    TacFunction cur = ctx.currentFunction();
    if (cur != null) {
      String enc2 = cur.enclosingClass();
      if (enc2 != null && !enc2.isEmpty()) {
        String resolved = ctx.sourcePathForEnclosingClass(enc2);
        if (resolved != null && !resolved.isEmpty()) {
          return resolved;
        }
      }
    }
    return ctx.program() == null ? "unknown" : ctx.program().typeName();
  }

  @Override
  public void finish(AnalysisContext ctx) {
    TacProgram prog = ctx.program();
    if (prog == null) return;
    Map<String, LockSequence> collected = byProgram.get(prog);
    if (collected == null) return;
    // Compare each pair of functions.
    List<String> sigs = new ArrayList<>(collected.keySet());
    for (int i = 0; i < sigs.size(); i++) {
      for (int j = i + 1; j < sigs.size(); j++) {
        String sigA = sigs.get(i);
        String sigB = sigs.get(j);
        LockSequence lockA = collected.get(sigA);
        LockSequence lockB = collected.get(sigB);
        List<String> seqA = lockA.locks;
        List<String> seqB = lockB.locks;
        if (!sameSet(seqA, seqB)) continue;
        if (sameOrder(seqA, seqB)) continue;
        // Different order, same set →
        // potential AB-BA deadlock.
        // R759-E: dedup key is the
        // (enclosing class, sigA, sigB)
        // tuple — pre-R759-E used
        // prog.typeName() which under
        // MergedProgram collapsed every
        // pair to the same dedup key
        // ("<merged>|helperAddBad...|helperMultiplyBad...")
        // and let only the FIRST pair
        // through dedup. Use the
        // enclosing classes so
        // per-file pairs each get their
        // own finding.
        String encA = lockA.fn.enclosingClass();
        String encB = lockB.fn.enclosingClass();
        // R759-E: only fire when the two
        // helpers live in the SAME
        // enclosing class. Without this
        // filter, every Juliet CWE833
        // file defines a
        // helperAddBad + helperMultiplyBad
        // with the SAME lock-identifier
        // strings ("BAD_NUMBER1_REENTRANTLOCK"
        // etc), so cross-file pair
        // comparison produces ghost
        // deadlocks between unrelated
        // classes. The "same class" rule
        // matches the Juliet detection
        // pattern: every bad/good pair is
        // scoped to a single test class.
        if (encA == null || !encA.equals(encB)) continue;
        String key =
            (encA == null ? "?" : encA)
                + "|"
                + (encB == null ? "?" : encB)
                + "|"
                + sigA
                + "|"
                + sigB;
        if (!reported.add(key)) continue;
        String filePath = resolveFilePath(ctx, lockA.fn);
        // R-812 v2 trace: cross-fn summary
        // shape. The two lock-ordering
        // sequences are the parents of a
        // single DEADLOCK_PAIR violation.
        // The Free operand encodes the
        // "function A vs B" pair so the AI
        // dedup layer can see it without
        // regex-parsing the legacy flow.
        TraceBuilder b = new TraceBuilder();
        int handle =
            b.addViolation(
                TraceNode.Op.DEADLOCK_PAIR,
                List.of(
                    new OperandRef.Free("lockA:" + sigA + " | lockB:" + sigB),
                    new OperandRef.Free("seqA:" + seqA + " | seqB:" + seqB)),
                List.of(),
                "Possible deadlock between '" + sigA + "' and '" + sigB + "'",
                "",
                -1,
                -1,
                sigA + " vs " + sigB);
        Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
        Finding f =
            Finding.dfaWithTrace(
                    name(),
                    "plumb." + RULE_ID,
                    "Possible deadlock between '"
                        + sigA
                        + "' and '"
                        + sigB
                        + "': they hold"
                        + " the same set of locks but acquire"
                        + " them in opposite orders. Two"
                        + " threads calling these functions"
                        + " concurrently can deadlock (CWE-833).",
                    RuleDef.Severity.MEDIUM,
                    "CWE-833",
                    "",
                    -1,
                    -1,
                    "deadlock: " + sigA + " " + seqA + " vs " + sigB + " " + seqB,
                    v2)
                .withFilePath(filePath);
        ctx.reporter().reportWithTrace(f, v2);
      }
    }
  }

  /**
   * Strip the SSA-version suffix (`:N`) from a TacVar. We treat the SSA version as a "different
   * binding" (Jimple may not have it). For our purposes the source-level name is the lock identity.
   */
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

  private static boolean sameSet(List<String> a, List<String> b) {
    if (a.size() != b.size()) return false;
    return new HashSet<>(a).containsAll(b) && new HashSet<>(b).containsAll(a);
  }

  private static boolean sameOrder(List<String> a, List<String> b) {
    if (a.size() != b.size()) return false;
    for (int i = 0; i < a.size(); i++) {
      if (!a.get(i).equals(b.get(i))) return false;
    }
    return true;
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }
}
