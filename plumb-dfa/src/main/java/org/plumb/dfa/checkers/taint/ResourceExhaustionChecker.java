/*
 * Resource Exhaustion (CWE-400).
 *
 * <p>Taint-light pattern detector built on the unified
 * {@link TaintedCheckerBase} machinery. Tracks vars
 * tainted by untrusted input (readLine, parseInt,
 * getParameter, etc.) and fires on:</p>
 * <ul>
 *   <li>{@code Thread.sleep(duration)} with tainted
 *       duration (CWE-400 via Thread.sleep)</li>
 *   <li>BRANCH-with-tainted-bound for a for-loop
 *       (CWE-400 via for_loop : the Juliet V01-V17
 *       baseline)</li>
 * </ul>
 *
 * <p>converted from a standalone DfaChecker to a
 * {@link TaintedCheckerBase} subclass so the cross-fn
 * field re-analysis extends to this rule. The
 * V-41 / V-42 / V-45 Juliet patterns (private-field /
 * cross-method-return) need the cross-fn fold to fire
 * this checker. Before , ResourceExhaustionChecker
 * was a standalone checker, so V-41 / V-42 / V-45
 * always read clean field taints and missed.</p>
 *
 * <p>The CWE-400 sinks ({@code Thread.sleep/1} and the
 * for-loop BRANCH-with-tainted-bound) are emitted
 * directly in {@link #checkInstruction} after the
 * super call sets up taint propagation. Thread.sleep
 * would normally go through the
 * {@code mem-alloc.sinks} / sink-family catalog, but
 * the BRANCH-with-tainted-bound pattern is rule-specific
 * and not in any catalog.</p>
 */
package org.plumb.dfa.checkers.taint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class ResourceExhaustionChecker extends TaintedCheckerBase {
  private static final String RULE_ID = "dfa-taint-resource-exhaustion";

  /** Per-function dedup for emitted findings. */
  private final Set<String> reported = new HashSet<>();

  public ResourceExhaustionChecker() {
    // Use the numeric-overflow source kind so parseInt etc.
    // mark the dest as tainted (see UncontrolledMemAllocChecker).
    // The sink family is unused : both sinks are emitted
    // directly from checkInstruction below.
    super(
        new TaintRule(
            RULE_ID,
            "plumb.numeric.overflow-hazard",
            "resource-exhaustion.sinks",
            "CWE-400",
            "MEDIUM",
            TaintDomain.NumericTaint.INSTANCE));
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // 1) Run the base class taint propagation.
    // (MOVE, BINOP, CALL, FIELDSTORE, FIELDLOAD).
    // For the same MOVE `count = Integer.MAX_VALUE`,
    // super.checkInstruction's propagateMOVE sees RHS
    // as a CONST (not a TacVar) and so falls into the
    // "drop any prior taint" branch : it does NOT
    // affect count because count wasn't tainted before
    // this call (count wasn't in taintedVars yet).
    super.checkInstruction(ins, fn, ctx);

    // 2) seed high-value CONST literal sources.
    // `count = Integer.MAX_VALUE` is the Juliet
    // CWE-400 bad-source pattern; we treat it as
    // tainted via markSource so the label is in this
    // checker's taintedVars map. We run AFTER
    // super.checkInstruction so the same-call
    // propagateMOVE doesn't wipe our seed (it drops
    // labels that aren't `param:` / `input:`; the
    // `max_const:` prefix is our own and is preserved
    // because we run second). Subsequent instructions
    // (FIELDSTORE, BRANCH, Thread.sleep) see the
    // tainted value through the standard
    // super.checkInstruction propagation on their own
    // calls.
    //
    // Why this works for V45 cross-fn: once `count` is
    // in our taintedVars, super.checkInstruction's
    // FIELDSTORE path (recordFieldOpForStore) sees it
    // and writes the label into
    // {@code crossFnClassFieldTaint}, so a subsequent
    // FIELDLOAD in the callee restores the taint on
    // the callee's local. The standard taintedVars
    // propagation also handles V42 (cross-method via
    // argument) through the FunctionSummary fold.
    //
    // Why this does NOT cause cross-rule FPs:
    // taintedVars is per-checker (each
    // TaintedCheckerBase subclass has its own instance
    // map). Marking `count` in OUR taintedVars is
    // invisible to SqlInjectionChecker,
    // CommandInjectionChecker, etc. : they have their
    // own independent maps.
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.dest instanceof TacOperand.TacVar mvDest
        && ins.lhs1 instanceof TacOperand.Const mvC
        && isHighValueConst(mvC)) {
      markSource(
          mvDest,
          "max_const:" + mvC.toString(),
          ins,
          fn.signature(),
          "max_const " + mvC.toString());
    }

    // 3) Sink 1: Thread.sleep with a tainted arg : fire.
    if (ins.kind == TacInstruction.Kind.CALL && ins.calleeSig != null) {
      String sig = ins.calleeSig;
      if (sig.equals("java.lang.Thread.sleep/1")
          || sig.equals("Thread.sleep/1")
          || sig.equals("java.lang.Thread.sleep/0")
          || sig.equals("Thread.sleep/0")) {
        for (TacOperand arg : ins.args) {
          if (!(arg instanceof TacOperand.TacVar av)) continue;
          if (!taintedVars.containsKey(av)) continue;
          String srcLabel = taintedVars.get(av);
          String key = fn.enclosingClass() + "#" + fn.signature() + "|" + ins.line + "|400-sleep";
          if (!reported.add(key)) return;
          // wire this Thread.sleep
          // sink back to the taint source
          // the base class recorded for
          // `av` via a v2 trace. The base
          // class has already emitted the
          // MARK_SOURCE node for the input
          // (readLine/etc.) — we just append
          // a SINK_MEM_ALLOC sink onto the
          // same per-function DAG.
          int parentHandle = v2TraceHandleFor(av);
          int sinkHandle =
              v2Builder()
                  .addSink(
                      TraceNode.Op.SINK_MEM_ALLOC,
                      List.of(new OperandRef.CallRef(sig), new OperandRef.Var(av.name(), 0)),
                      parentHandle < 0 ? List.of() : List.of(parentHandle),
                      "Thread.sleep sink with tainted duration",
                      "",
                      ins.line,
                      ins.column,
                      fn.signature());
          // Build the v2 trace from the base
          // class's per-function builder with
          // our newly-added sink as the anchor.
          // We can't reuse base class's
          // v2SinkHandles list (private), so
          // we just emit a one-shot trace from
          // the builder state with our
          // handle as the sink id.
          Trace v2Trace =
              v2Builder().build(java.util.List.of(sinkHandle), Trace.CompressionLevel.FULL);
          // Keep the source+violation
          // FlowStep pair so v1 consumers see
          // the same trace as pre-.
          List<FlowStep> steps = new ArrayList<>(2);
          steps.add(
              new FlowStep(
                  "source", "", ins.line, ins.column, fn.signature(), "tainted: " + srcLabel));
          steps.add(
              new FlowStep(
                  "violation",
                  "",
                  ins.line,
                  ins.column,
                  fn.signature(),
                  "Thread.sleep() called with tainted duration"));
          Finding finding =
              Finding.dfa(
                  RULE_ID,
                  RULE_ID,
                  "Thread.sleep() called with tainted duration: "
                      + "an attacker can force the thread to "
                      + "sleep for an unbounded amount of time, "
                      + "denying service to other threads. "
                      + "Validate the duration against a known "
                      + "upper bound before passing to sleep().",
                  RuleDef.Severity.MEDIUM,
                  "CWE-400",
                  "",
                  ins.line,
                  ins.column,
                  "resource-exhaustion: " + sig + " from " + srcLabel,
                  java.util.Collections.unmodifiableList(steps));
          ctx.reporter().reportWithTrace(finding, v2Trace);
          return;
        }
      }
      return;
    }

    // 4) Sink 2: BRANCH-with-tainted-bound : for_loop
    // (a loop bound that is itself tainted).
    if (ins.kind == TacInstruction.Kind.BRANCH && ins.lhs1 instanceof TacOperand.TacVar condVar) {
      int branchIdx = fn.instructions().indexOf(ins);
      if (branchIdx < 0) return;
      int binopIdx = findDefiningBinop(fn.instructions(), branchIdx, condVar.name());
      if (binopIdx < 0) return;
      TacInstruction binop = fn.instructions().get(binopIdx);
      if (binop.binop == null) return;
      String op = binop.binop.name();
      if (!op.equals("LT") && !op.equals("LE") && !op.equals("GT") && !op.equals("GE")) {
        return;
      }
      String label = null;
      if (binop.lhs1 instanceof TacOperand.TacVar l && taintedVars.containsKey(l)) {
        label = taintedVars.get(l);
      } else if (binop.lhs2 instanceof TacOperand.TacVar r && taintedVars.containsKey(r)) {
        label = taintedVars.get(r);
      }
      if (label == null) return;
      String key = fn.enclosingClass() + "#" + fn.signature() + "|" + ins.line + "|400-for";
      if (!reported.add(key)) return;
      // same v2 trace wiring as the
      // Thread.sleep branch — append a
      // SINK_MEM_ALLOC sink onto the base
      // class's per-function DAG, parented
      // to the tainted bound's v2 source
      // handle.
      TacOperand.TacVar boundVar =
          binop.lhs1 instanceof TacOperand.TacVar l && taintedVars.containsKey(l)
              ? l
              : binop.lhs2 instanceof TacOperand.TacVar r && taintedVars.containsKey(r) ? r : null;
      if (boundVar != null) {
        int parentHandle = v2TraceHandleFor(boundVar);
        int sinkHandle =
            v2Builder()
                .addSink(
                    TraceNode.Op.SINK_LOOP_BOUND,
                    List.of(new OperandRef.Var(boundVar.name(), 0)),
                    parentHandle < 0 ? List.of() : List.of(parentHandle),
                    "for-loop bound derived from tainted count",
                    "",
                    ins.line,
                    ins.column,
                    fn.signature());
        Trace v2Trace =
            v2Builder().build(java.util.List.of(sinkHandle), Trace.CompressionLevel.FULL);
        // The same source/violation FlowStep pair as the
        // Thread.sleep branch. Reports where the tainted bound
        // came from + the actual violation site.
        List<FlowStep> steps = new ArrayList<>(2);
        steps.add(
            new FlowStep("source", "", ins.line, ins.column, fn.signature(), "tainted: " + label));
        steps.add(
            new FlowStep(
                "violation",
                "",
                ins.line,
                ins.column,
                fn.signature(),
                "For-loop bound derived from tainted count"));
        Finding finding =
            Finding.dfa(
                RULE_ID,
                RULE_ID,
                "For-loop bound derived from tainted count: "
                    + "an attacker can force unbounded "
                    + "iteration. Validate the count against "
                    + "a known upper bound before using it as "
                    + "a loop bound.",
                RuleDef.Severity.MEDIUM,
                "CWE-400",
                "",
                ins.line,
                ins.column,
                "resource-exhaustion: for-loop bound from " + label,
                java.util.Collections.unmodifiableList(steps));
        ctx.reporter().reportWithTrace(finding, v2Trace);
      }
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    // use the base-class summary builder so the
    // cross-fn propagation for V42 (data returned from
    // one method to another) and V45 (private field)
    // works. Returning {@code FunctionSummary.empty()}
    // here silently drops the TaintPropagate ops on
    // returned vars that the base class's
    // {@code passedOutTaint} list would emit : and the
    // V42 Juliet pattern (`bad() { int x = badSource();
    // for (...x...) }`) needs those ops to seed taint
    // on the caller's local.
    //
    // The base class's summary also includes sink
    // observations (none for CWE-400 : both sinks are
    // emitted directly from {@link #checkInstruction}
    // and do not match any
    // {@link org.plumb.dfa.config.SinkFamily}), so the
    // summary size is bounded by the propagated taint
    // sink entries. The commit originally
    // returned empty here because the V41/V42/V45
    // private-field / cross-method-return patterns
    // relied on {@code crossFnClassFieldTaint} only :         // which handles V45 but NOT V42's
    // "return through
    // call chain" flow.
    return super.summarizeFunction(ctx, fn);
  }

  /**
   * Walk back from a BRANCH (whose lhs1 is {@code condVar}, the boolean result of some comparison)
   * to find the BINOP that defined that comparison. Mirrors the helper in {@link
   * org.plumb.dfa.checkers.taint.UncheckedLoopConditionChecker}.
   */
  private static int findDefiningBinop(List<TacInstruction> insns, int upTo, String varName) {
    for (int j = upTo - 1; j >= 0; j--) {
      TacInstruction p = insns.get(j);
      if (p.kind != TacInstruction.Kind.BINOP) continue;
      if (p.dest instanceof TacOperand.TacVar dv && varName.equals(dv.name())) {
        return j;
      }
    }
    return -1;
  }

  /**
   * recognise "high-value constant" sources.
   *
   * <p>A literal is high-value when:
   *
   * <ul>
   *   <li>INT {@code >= Integer.MAX_VALUE / 2} (catches {@code Integer.MAX_VALUE}, {@code
   *       Integer.MAX_VALUE - 1}, and other near-MAX constants used as unbounded loop bounds or
   *       sleep durations); OR
   *   <li>INT {@code <= Integer.MIN_VALUE / 2} (catches {@code Integer.MIN_VALUE}, {@code
   *       Integer.MIN_VALUE + 1}, etc., which would underflow any subtraction or comparison);
   *   <li>LONG same threshold cast to long; OR
   *   <li>{@code Long.MAX_VALUE} / {@code Integer.MAX_VALUE} exact value.
   * </ul>
   *
   * <p>The threshold is conservative : we only flag values that obviously cannot represent a
   * bounded loop count or sleep duration. Values like {@code 1000} or {@code 2000} (Juliet's
   * validation thresholds) are NOT flagged.
   */
  private static boolean isHighValueConst(TacOperand.Const c) {
    if (c.kind() == TacOperand.Const.Kind.INT && c.value() instanceof Integer iv) {
      return iv >= (Integer.MAX_VALUE / 2) || iv <= (Integer.MIN_VALUE / 2);
    }
    if (c.kind() == TacOperand.Const.Kind.LONG && c.value() instanceof Long lv) {
      return lv >= ((long) Integer.MAX_VALUE / 2) || lv <= ((long) Integer.MIN_VALUE / 2);
    }
    return false;
  }

  private static final String AI_PROMPT =
"""
            RULE: Resource exhaustion via Thread.sleep (CWE-400).
            This rule fires when Thread.sleep(duration) is called
            with a tainted duration value (one that came from
            untrusted user input: readLine, parseInt, getParameter,
            getQueryString, etc.).

            A finding is a TRUE POSITIVE (TP) when the duration
            came from outside the trust boundary AND is not
            validated against a known upper bound.

            A finding is a FALSE POSITIVE (FP) when:
              * The duration is a hardcoded constant
              * The duration is bounded (Math.min(d, MAX_SLEEP_MS))

            UNKNOWN when the validation is implicit (e.g. via
            a non-obvious comparison).
""";

  @Override
  public String getAiPrompt(org.plumb.reporter.Finding finding) {
    return AI_PROMPT;
  }
}
