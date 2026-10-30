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
package org.plumb.dfa.taint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Base class for <b>path-sensitive</b> DFA checkers. This is the {@link TaintedCheckerBase}
 * counterpart for checkers whose detection rule is "tainted <em>without</em> a sink call" rather
 * than "tainted <em>flowing to</em> a sink call".
 *
 * <h2>The two dataflow shapes</h2>
 *
 * <p>The {@link TaintedCheckerBase} handles the "flow-to-sink" pattern: a value carries taint along
 * the SSA def-use chain, and a finding fires when the taint reaches a call site matching a sink
 * family. This is the right shape for SQL injection, command injection, taint-to-log, etc. — the
 * "taint flows to a place that consumes it" pattern.
 *
 * <p>The <em>alloc-without-close</em> pattern (CWE-404 / CWE-401, "resource leak") is the opposite:
 * an allocation creates a value, and a finding fires if the value is <em>not</em> closed (or
 * returned) on a path. The bug condition is the absence of a sink call, not its presence. The
 * {@link TaintedCheckerBase} template doesn't fit this shape well -- there's no "destination sink"
 * to check, only a "source alloc" whose close obligation must be tracked to the function exit.
 *
 * <p>This class is the right starting point for the alloc-without-close family. It tracks the alloc
 * sites in the current function ({@link #allocSites}) and the close calls observed ({@link
 * #closeSites}), and at {@link #summarizeFunction} time it asks the subclass to decide what to do
 * with the unmatched allocs (typically: emit a finding).
 *
 * <h2>Why the abstract class is so small</h2>
 *
 * <p>The template hooks for {@link TaintedCheckerBase} are <em>what's the sink family</em> +
 * <em>what's the source kind</em>. The template hooks for {@code PathSensitiveCheckerBase} are
 * <em>what's an alloc site</em> + <em>what's a close call</em>. Both templates are small (under 200
 * lines each); the bulk of the logic is in the subclass. The split is intentional: combining the
 * two would make each subclass a "kitchen sink" with irrelevant fields and hooks. Keep them
 * separate.
 *
 * <h2>Cross-function propagation</h2>
 *
 * <p>If a callee returns an allocated resource (e.g. {@code Connection#open()} returns a {@code
 * Connection}), the caller inherits the "needs close" obligation. We propagate this by checking the
 * callee's summary for an {@code Operation.PassThrough} (or a dedicated {@code
 * Operation.AllocReturn} -- this class just looks for any return-side TaintPropagate / PassThrough
 * that the caller-side checker can recognise) and recording a local alloc for the dest of the call.
 */
public abstract class PathSensitiveCheckerBase implements DfaChecker {

  /**
   * The variables that the function allocated in this body, mapped to the {@link TacInstruction}
   * that allocated them. The value is used in {@link #summarizeFunction} to report the alloc site
   * (line / column) to the user.
   */
  protected final Map<TacOperand.TacVar, TacInstruction> allocSites = new LinkedHashMap<>();

  /**
   * The variables that the function closed in this body, mapped to the list of {@link
   * TacInstruction}s that closed them (multiple closes are legal; we just need at least one for the
   * variable to count as "closed"). Cleared at every function boundary by {@link #initFunction}.
   */
  protected final Map<TacOperand.TacVar, List<TacInstruction>> closeSites = new LinkedHashMap<>();

  /**
   * The variables that the function passed (returned or threw) to the caller, so the caller
   * inherits the close obligation. By default we use the {@code RETURN} instruction's operand as
   * the "passed out" variable; subclasses with more elaborate exit semantics (out-parameters,
   * checked exceptions) can override.
   */
  protected final List<TacOperand.TacVar> passedOut = new ArrayList<>();

  /**
   * The variables that are aliases of an allocated value, mapped to the variable they alias.
   *
   * <p><b>Why this exists.</b> The TAC lowers {@code FileInputStream in = new FileInputStream(f);}
   * to a {@code NEW} into a TEMPORARY followed by a {@code MOVE} into the local:
   *
   * <pre>
   *   NEW    dest=t_t0   (the allocation)
   *   MOVE   dest=in     lhs1=t_t0
   *   CALL   in.close/0  (the close)
   * </pre>
   *
   * <p>So the allocation is recorded on {@code t_t0} and the close on {@code in}, and the two never
   * meet. Without this map, {@code in.close()} did not count as closing {@code t_t0}, and the
   * checker reported a resource leak on code that closes the resource correctly.
   *
   * <p>The same shape appears in a try-with-resources, where the resource is a local and the
   * implicit close is on that local.
   *
   * <p>The map is deliberately shallow: one level of MOVE. A deeper chain would need the alias to
   * be resolved transitively, and the value here is bounded -- an allocation is either closed
   * through the local it was assigned to, or it is not closed at all.
   */
  protected final Map<TacOperand.TacVar, TacOperand.TacVar> aliases = new LinkedHashMap<>();

  /**
   * The closes that happened inside a branch arm, so on SOME path only.
   *
   * <p>They are kept apart from {@link #closeSites} because a conditional close does not discharge
   * the close obligation. The rule fires when the resource is "not closed on every path", so a
   * close on one path is not a close at all.
   */
  protected final Map<TacOperand.TacVar, List<TacInstruction>> conditionalCloseSites =
      new LinkedHashMap<>();

  /**
   * The labels a BRANCH points at, and the merge label that ends the arm.
   *
   * <p>The TAC lowers an {@code if} to a {@code BRANCH} plus labelled regions. The arm a close sits
   * in is identified by the label that starts it; the arm ends at the merge label, which the arm's
   * trailing {@code JUMP} names.
   */
  private final Map<String, Integer> armDepthByLabel = new LinkedHashMap<>();

  /** The branch-arm depth of the instruction currently being examined. */
  private int currentArmDepth = 0;

  /** The labels a BRANCH has opened arms at, waiting to be entered. */
  private final java.util.Set<String> pendingArmTargets = new java.util.LinkedHashSet<>();

  /** How many arms the BRANCH instructions so far have opened. */
  private int pendingArmDepth = 0;

  /**
   * The index of the instruction that ends the finally region, or {@code -1}.
   *
   * <p>A {@code finally} block runs on EVERY exit path -- normal completion, an exception, a {@code
   * return}. So a close inside one is unconditional with respect to the resource's lifetime: by the
   * time the method returns, the resource has been closed.
   *
   * <p>This matters because the canonical shape for closing a resource puts a null guard around the
   * close:
   *
   * <pre>
   *   try { ... } finally { if (in != null) { in.close(); } }
   * </pre>
   *
   * The guard lowers to a BRANCH, so the close sits inside a branch arm. Without knowing about the
   * finally, the checker would call that close CONDITIONAL and report a leak on code that closes
   * the resource correctly. The guard's condition does not make the close conditional -- the close
   * happens exactly when there is something to close, and the finally runs either way.
   *
   * <p>The TAC marks the region with a {@code FINALLY_ENTER} instruction and records the region's
   * extent in the function's try regions, so the extent is read from there.
   */
  private int finallyEndIndex = -1;

  /** The index of the instruction that begins the finally region, or {@code -1}. */
  private int finallyStartIndex = -1;

  /**
   * The index of the instruction currently being examined, within the function's instruction list.
   *
   * <p>The try region's bounds are indices into that list, so a close can be located relative to
   * the finally region.
   */
  private int currentInstructionIndex = -1;

  /** Human-readable rule id (overridden by the subclass). */
  public abstract String ruleId();

  /** CWE label (e.g. "CWE-404"), or null. */
  public abstract String cwe();

  /** Severity label (e.g. "HIGH"). */
  public String severityLabel() {
    return "HIGH";
  }

  @Override
  public final String name() {
    return ruleId();
  }

  /**
   * The rule this checker emits, with its CWE.
   *
   * <p>The rule id and the CWE are already declared by the subclass -- as {@link #ruleId()} and
   * {@link #cwe()} -- so this only has to wrap them in the shape the rule map reads. The map is
   * built from the engine so that it cannot drift from the rules the engine actually emits.
   */
  @Override
  public org.plumb.rules.RuleDef ruleDef() {
    String cwe = cwe();
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId(ruleId(), ruleId()),
        ruleId(),
        org.plumb.rules.RuleDef.Severity.valueOf(severityLabel()),
        org.plumb.rules.RuleDef.Type.CORRECTNESS,
        cwe == null || cwe.isBlank() ? java.util.List.of() : java.util.List.of(cwe),
        getClass().getName(),
        true,
        java.util.List.of());
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    allocSites.clear();
    closeSites.clear();
    passedOut.clear();
    aliases.clear();
    conditionalCloseSites.clear();
    pendingArmTargets.clear();
    pendingArmDepth = 0;
    currentArmDepth = 0;
    finallyStartIndex = -1;
    finallyEndIndex = -1;
    currentInstructionIndex = -1;
    // Parameters are provisionally
    // "potentially-allocated" — the caller's
    // summary may refine this via
    // Operation.PassThrough on the paramOps.
    // We don't seed the alloc map here
    // because not every parameter is an
    // allocated resource; the subclass
    // decides via {@link #isAllocCall}.
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // Forward: locate the instruction within the function's list, so the finally region's
    // bounds (which are indices into that list) can be compared against it.
    this.currentInstructionIndex = indexOf(fn, ins);
    // Forward: track the finally region, so a close inside it is told apart from a close that
    // only happens on some paths.
    trackFinallyRegion(ins, fn);
    // Forward: track the branch-arm structure, so a close can be told apart from a close that
    // only happens on some paths.
    trackBranchArm(ins);
    // Forward: detect an alloc site.
    if (isAllocSite(ins) && ins.dest instanceof TacOperand.TacVar destVar) {
      allocSites.put(destVar, ins);
    }
    // Forward: remember the MOVE that binds an allocation to a local, so a close on the local
    // counts as closing the allocation. Without this, `X x = new X(...); x.close();` reported a
    // leak, because the allocation is on the temporary and the close is on the local.
    if (ins.kind == TacInstruction.Kind.MOVE
        && ins.dest instanceof TacOperand.TacVar moveDest
        && ins.lhs1 instanceof TacOperand.TacVar moveSrc) {
      aliases.put(moveDest, moveSrc);
    }
    // Forward: detect a close call.
    if (ins.kind == TacInstruction.Kind.CALL && isCloseCall(ins)) {
      recordCloseIfMatches(ins);
    }
    // Forward: cross-function — if the callee
    // returns an allocated resource, the
    // caller's dest inherits the close
    // obligation.
    if (ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && ins.dest instanceof TacOperand.TacVar destVar) {
      FunctionSummary calleeSummary = ctx.summaryCache().get(ins.calleeSig);
      boolean retAlloc = calleeSummary.returnOps().stream().anyMatch(op -> isAllocReturnOp(op));
      if (retAlloc) {
        allocSites.put(destVar, ins);
      }
    }
    // Forward: if the instruction is a
    // RETURN carrying a value, the value is
    // passed out to the caller.
    if (ins.kind == TacInstruction.Kind.RETURN && ins.lhs1 instanceof TacOperand.TacVar returned) {
      passedOut.add(returned);
    }
  }

  /**
   * Subclass hook: is this instruction an allocation site? Examples: a {@code NEW} of a class, a
   * {@code NEWARRAY}, a constructor call to a known resource factory ({@code FileInputStream},
   * {@code Connection#open}, etc.).
   */
  protected abstract boolean isAllocSite(TacInstruction ins);

  /**
   * Subclass hook: is this CALL a close operation? Default: any method named {@code close}.
   * Subclasses with broader or narrower definitions override.
   */
  protected boolean isCloseCall(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.CALL) return false;
    String callee = ins.calleeSig;
    if (callee == null) return false;
    // Match the plumb-tac IR's
    // "Class.method/arity" form. The
    // method is "close", so the callee
    // looks like "readerBuffered.close/0"
    // (with `.` separator) or
    // "java.io.Reader#close/0" (with `#`
    // separator). Some synthetic
    // instructions may omit the arity
    // suffix, so we accept both forms.
    // Avoid matching "Closeable" (a class
    // name) by requiring the close token
    // to be followed by either `/` (arity)
    // or end of string (no-arity form).
    return callee.contains(".close/")
        || callee.contains("#close/")
        || callee.endsWith(".close")
        || callee.endsWith("#close");
  }

  /**
   * Subclass hook: does this {@link org.plumb.dfa.summary.Operation} mean "the callee returns an
   * allocated resource, so the caller's dest inherits the close obligation"? Default: any {@code
   * TaintPropagate} on the return side is treated as a potential alloc indicator
   * (TaintPropagate-style propagation). A subclass that wants a stricter "the callee definitely
   * returns a fresh resource" check can override.
   */
  protected boolean isAllocReturnOp(org.plumb.dfa.summary.Operation op) {
    if (op == null) return false;
    return op instanceof org.plumb.dfa.summary.Operation.TaintPropagate;
  }

  /**
   * The index of an instruction within the function's instruction list, or {@code -1}.
   *
   * <p>The instruction objects are the ones in the list, so identity is enough -- but the lookup is
   * by identity rather than by {@code equals}, because two instructions can be equal and be at
   * different places in the stream.
   */
  private static int indexOf(TacFunction fn, TacInstruction ins) {
    List<TacInstruction> instructions = fn.instructions();
    for (int i = 0; i < instructions.size(); i++) {
      if (instructions.get(i) == ins) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Track the finally region of the current function.
   *
   * <p>The TAC records the finally block's extent on the function's try regions. A close inside
   * that extent is unconditional with respect to the resource's lifetime, because the finally block
   * runs on every exit path.
   *
   * <p>The extent is read once, when the finally region's marker instruction is seen, rather than
   * being recomputed for every instruction: the region's bounds do not change, and the instruction
   * stream is walked in order.
   */
  private void trackFinallyRegion(TacInstruction ins, TacFunction fn) {
    if (ins.kind != TacInstruction.Kind.FINALLY_ENTER) {
      return;
    }
    // The marker names the region's label. The extent comes from the try region that
    // references it.
    for (TacFunction.TryRegion region : fn.tryRegions()) {
      if (region.hasFinally() && ins.label != null && ins.label.equals(region.finallyLabel)) {
        this.finallyStartIndex = region.finallyStart;
        this.finallyEndIndex = region.finallyEnd;
        return;
      }
    }
  }

  /**
   * Whether the instruction being examined sits inside a finally region.
   *
   * <p>A close inside a finally block is unconditional, so it discharges the obligation even when
   * the finally body contains a branch. See {@link #finallyEndIndex}.
   */
  protected boolean isInsideFinallyRegion() {
    return this.finallyEndIndex >= 0
        && this.currentInstructionIndex >= this.finallyStartIndex
        && this.currentInstructionIndex <= this.finallyEndIndex;
  }

  /**
   * Track the branch-arm structure of the instruction stream.
   *
   * <p>The TAC lowers an {@code if} to a {@code BRANCH} with two targets, and each arm is a
   * labelled region:
   *
   * <pre>
   *   if flag goto L_THEN_0 else goto L_ELSE_1
   *  L_THEN_0:
   *   in.close()          &lt;-- inside an arm
   *   goto L_END_IF_2
   *  L_ELSE_1:
   *  L_END_IF_2:
   *   in.read()
   * </pre>
   *
   * <p>So an arm is: a label a BRANCH points at, up to the {@code JUMP} that leaves it. The
   * tracking is deliberately coarse -- it does not model the CFG, and it does not try to prove
   * dominance. It identifies the shape the rule cares about: a close that only happens on some
   * paths.
   *
   * <p>A close that is NOT inside an arm is unconditional, and it discharges the obligation. A
   * close inside an arm is conditional, and it does not.
   *
   * @param ins the instruction
   */
  private void trackBranchArm(TacInstruction ins) {
    switch (ins.kind) {
      case BRANCH -> {
        // A branch opens an arm at each of its two targets. The merge label -- the one the arm's
        // trailing JUMP names -- is not known yet, so the arm is closed when that JUMP is seen.
        pendingArmTargets.add(ins.trueLabel);
        pendingArmTargets.add(ins.falseLabel);
        pendingArmDepth++;
      }
      case LABEL -> {
        if (pendingArmTargets.contains(ins.label)) {
          currentArmDepth = pendingArmDepth;
          pendingArmTargets.remove(ins.label);
        } else if (mergeLabelOf(ins.label) != null) {
          // The merge point: the arm is over.
          currentArmDepth = 0;
          pendingArmDepth = Math.max(0, pendingArmDepth - 1);
        }
      }
      case JUMP -> {
        // The arm's trailing jump names the merge label. A jump that LEAVES the current arm ends
        // it.
        if (currentArmDepth > 0 && ins.label != null && ins.label.startsWith("L_END")) {
          currentArmDepth = 0;
          pendingArmDepth = Math.max(0, pendingArmDepth - 1);
        }
      }
      default -> {
        // not a control-flow instruction
      }
    }
  }

  /**
   * The merge label a region label ends at, or {@code null}.
   *
   * <p>The TAC names an {@code if}'s merge label {@code L_END_IF_<n>}, and the arm's trailing
   * {@code JUMP} names it. So the merge label of an arm is the one a JUMP out of the arm targets,
   * and the label itself carries the {@code L_END} prefix.
   */
  private static String mergeLabelOf(String label) {
    if (label == null) {
      return null;
    }
    return label.startsWith("L_END") ? label : null;
  }

  /**
   * Record a close observation for the appropriate argument. By default the close is on argument 0
   * (the first user-visible arg after the receiver for instance methods, or the only arg for static
   * close). Subclasses that use different arg positions override {@link #closeArgumentIndex}.
   *
   * <p>The close is recorded on the variable the call names AND on the allocation it aliases. The
   * TAC lowers {@code X x = new X(...); x.close();} to a {@code NEW} into a temporary followed by a
   * {@code MOVE} into the local, so the allocation lives on the temporary and the close on the
   * local. Recording only the local left the two apart, and the checker reported a leak on code
   * that closes the resource correctly.
   *
   * <p>A close that sits inside a BRANCH ARM is recorded as CONDITIONAL, and a conditional close
   * does not discharge the obligation. The rule is "not closed on EVERY path", so a close on one
   * path is not a close at all: {@code if (flag) { in.close(); } in.read();} still leaks on the
   * other path, and reporting that is the whole point of the rule.
   */
  private void recordCloseIfMatches(TacInstruction ins) {
    int argIdx = closeArgumentIndex(ins);
    if (argIdx < 0 || argIdx >= ins.args.size()) return;
    TacOperand arg = ins.args.get(argIdx);
    if (!(arg instanceof TacOperand.TacVar cv)) return;
    if (isInsideBranchArm() && !isInsideFinallyRegion()) {
      // A conditional close. Remember it, but not as a discharge of the obligation.
      //
      // A close inside a finally block is the exception: the finally runs on every exit path,
      // so the close is unconditional with respect to the resource's lifetime. The guard that
      // usually wraps it (`if (in != null) in.close();`) is a null check, not a path choice --
      // it says "close it if there is something to close", and the finally guarantees the
      // close happens before the method leaves.
      conditionalCloseSites.computeIfAbsent(cv, k -> new ArrayList<>()).add(ins);
      TacOperand.TacVar conditionalAliased = aliases.get(cv);
      if (conditionalAliased != null) {
        conditionalCloseSites.computeIfAbsent(conditionalAliased, k -> new ArrayList<>()).add(ins);
      }
      return;
    }
    closeSites.computeIfAbsent(cv, k -> new ArrayList<>()).add(ins);
    // The alias direction is local -> allocation, so the allocation is the value of the map.
    TacOperand.TacVar aliased = aliases.get(cv);
    if (aliased != null) {
      closeSites.computeIfAbsent(aliased, k -> new ArrayList<>()).add(ins);
    }
  }

  /**
   * Whether the instruction being examined sits inside a branch arm.
   *
   * <p>The TAC lowers an {@code if} to a {@code BRANCH} with two targets, and each arm is a
   * labelled region:
   *
   * <pre>
   *   if flag goto L_THEN_0 else goto L_ELSE_1
   *  L_THEN_0:
   *   in.close()          &lt;-- inside an arm
   *   goto L_END_IF_2
   *  L_ELSE_1:
   *  L_END_IF_2:
   * </pre>
   *
   * <p>A close is inside an arm when the region it sits in was entered through a branch target that
   * is not the merge point. The tracking is deliberately coarse -- it identifies the arm by the
   * labels a BRANCH points at, and leaves the arm at the merge label -- but it is exactly right for
   * the shape the rule cares about: a close that only happens on some paths.
   *
   * @return true when the current instruction is inside a conditional arm
   */
  private boolean isInsideBranchArm() {
    return currentArmDepth > 0;
  }

  /**
   * Subclass hook: which argument of the close call carries the resource being closed. Default: 0
   * (instance method's receiver treated as arg 0 by the IR).
   */
  protected int closeArgumentIndex(TacInstruction ins) {
    return 0;
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    // Build a summary that records the
    // alloc/close observations for the
    // caller. A caller-side checker can
    // look up the callee's summary and
    // decide whether to inherit the
    // "needs close" obligation.
    FunctionSummary.Builder b = new FunctionSummary.Builder(fn.signature());
    // For each param, if the param is in
    // our close sites (meaning the function
    // closed it before returning), record a
    // PassThrough so the caller doesn't
    // think the callee leaked it.
    for (int i = 0; i < fn.params().size(); i++) {
      TacOperand.TacVar p = fn.params().get(i);
      if (closeSites.containsKey(p)) {
        b.recordParamOp(i, org.plumb.dfa.summary.Operation.PassThrough.INSTANCE);
      }
    }
    // For each alloc that we know is also
    // passed out (returned), record
    // TaintPropagate on the return so
    // callers inherit the "needs close"
    // obligation.
    for (TacOperand.TacVar ret : passedOut) {
      if (allocSites.containsKey(ret)) {
        b.recordReturnOp(
            new org.plumb.dfa.summary.Operation.TaintPropagate("alloc-return: " + ret.name(), 1.0));
      }
    }
    return b.build();
  }

  /**
   * Default emit helper. Subclasses call this from their own decision logic if they want the
   * standard "alloc + no close on any path" finding message.
   *
   * <p>— emits a two-step {@link org.plumb.reporter.FlowStep} trace: a {@code "source"} step at the
   * allocation site (where the resource was created) and a {@code "violation"} step at the function
   * entry (the leak manifests when the function returns without closing). The two-step shape
   * matches the taint-style checkers' {@code source → sink} trace so reporters can render
   * consistently.
   */
  protected void emitLeakFinding(
      TacOperand.TacVar allocVar, TacInstruction allocSite, TacFunction fn, AnalysisContext ctx) {
    java.util.List<org.plumb.reporter.FlowStep> steps = new java.util.ArrayList<>(2);
    steps.add(
        new org.plumb.reporter.FlowStep(
            "source",
            "",
            allocSite.line,
            allocSite.column,
            fn.signature(),
            "alloc " + allocVar.name() + " at " + allocSite));
    // The "violation" location is the alloc
    // site too (we don't have a single
    // function-exit instruction in TAC; the
    // function exit is implicit). Use the
    // alloc site line/column but a distinct
    // role + expr so the two steps are not
    // collapsed by reporters.
    steps.add(
        new org.plumb.reporter.FlowStep(
            "violation",
            "",
            allocSite.line,
            allocSite.column,
            fn.signature(),
            allocVar.name() + " not closed on every path"));
    Finding f =
        Finding.dfa(
            ruleId(),
            ruleId(),
            "Resource leak: "
                + allocVar.name()
                + " allocated at "
                + allocSite
                + " is not closed on every path",
            severityValue(),
            cwe(),
            "", // filePath filled by reporter
            allocSite.line,
            allocSite.column,
            "alloc: " + allocVar.name(),
            java.util.Collections.unmodifiableList(steps));
    ctx.reporter().report(f);
  }

  private RuleDef.Severity severityValue() {
    String s = severityLabel();
    if (s == null) return RuleDef.Severity.HIGH;
    try {
      return RuleDef.Severity.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      return RuleDef.Severity.HIGH;
    }
  }

  @Override
  public CheckerConfig config(AnalysisContext ctx) {
    EngineConfig cfg = ctx.config();
    return cfg.checkers().byName(name()).orElse(null);
  }

  // ─── introspection helpers for tests ────────

  public int allocSiteCount() {
    return allocSites.size();
  }

  public int closeSiteCount() {
    return closeSites.size();
  }

  public int passedOutCount() {
    return passedOut.size();
  }

  public boolean isAllocated(TacOperand.TacVar v) {
    return allocSites.containsKey(v);
  }

  public boolean isClosed(TacOperand.TacVar v) {
    return closeSites.containsKey(v);
  }
}
