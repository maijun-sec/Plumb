package org.plumb.dfa.checkers.boundary;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.solver.SolverZ3Bridge;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.summary.Operation;
import org.plumb.dfa.taint.GuardTracker;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Abstract base for "boundary constraint" checkers ? those that fire when an arithmetic / division
 * / array-index operation could exceed the type's range, divide by zero, or step outside an array.
 *
 * <p>Each concrete subclass (Overflow / Underflow / DivZero / ArrayBounds) inherits the
 * range-propagation scaffolding here and only supplies the rule-specific bits:
 *
 * <ul>
 *   <li>{@link #isBoundarySensitive} ? does this instruction potentially overflow / underflow /
 *       divide by zero / array-OOB? (Subclass-specific.)
 *   <li>{@link #sinkPredicate} ? build the "is the path reachable?" predicate for {@code ins} given
 *       the current ranges (subclass-specific).
 *   <li>{@link #hazardKind} ? return the human-readable hazard name (e.g. {@code "overflow"},
 *       {@code "underflow"}, {@code "divide-by-zero"}, {@code "out-of-bounds"}).
 * </ul>
 *
 * <h2>Why the abstract base?</h2>
 *
 * <p>The user explicitly preferred separate checkers over a single combined one so the rules show
 * up individually in the report (CWE-190 / CWE-191 / CWE-369 / CWE-129 must be distinguishable).
 * But all four share the same range-propagation + GuardStore + GuardTracker infrastructure. This
 * class factors those out.
 *
 * <h2>Reverse + forward architecture</h2>
 *
 * <p>This checker reads ranges forward (BB-RPO order, intra-block forward per {@code
 * DfaEngine#bbRpoOrder}) but writes guards both forward (on BRANCH we push) and backward (we
 * register a per-var guard into the {@link GuardStore} so other checkers can ask "is var x
 * bounded?" via {@code ctx.guardStore().impliedGuard}). The reverse summary is built once per
 * function by the engine's {@code warmUpMethodSummaries} pre-pass and cached; the forward pass
 * consumes it.
 *
 * <h2>Constraint solving (, insight)</h2>
 *
 * <p>Before reporting a finding the checker asks {@link SolverZ3Bridge#check(String, Map)}: "given
 * the current path guards, is the sink-constraint predicate satisfiable?" If UNSAT, the path can't
 * actually be taken and the finding is suppressed. The user pointed out the original {@code
 * TaintedCheckerBase}-derived checkers had {@code solverBridge} wired up but never called it for
 * boundary checks ? this class fixes that.
 */
public abstract class BoundaryConstraintChecker extends TaintedCheckerBase {

  /**
   * Per-var interval lattice. Populated forward in {@link #checkInstruction}. Cleared in {@link
   * #initFunction}.
   */
  private final Map<TacOperand.TacVar, Range> ranges = new LinkedHashMap<>();

  /**
   * Per-var MOVE lineage. {@code lineage[x] = a} records that the most recent assignment to {@code
   * x} was {@code x = a} (a direct MOVE). When {@code a}'s range is later narrowed (e.g. by a
   * guard), every {@code y} such that {@code lineage[y] = a} inherits the narrower range ? this
   * lets us answer the user-insight pattern {@code int x = a; if (a < MAX-5) { x++; }} without
   * re-traversing MOVE chains.
   *
   * <p>Chain propagation (e.g. {@code y = x, x = a}): handled by re-running the propagation loop in
   * {@link #propagate} until stable.
   */
  private final Map<TacOperand.TacVar, TacOperand.TacVar> lineage = new LinkedHashMap<>();

  /**
   * Per-var string guard (DSL form), surfaced into {@link GuardStore} so other checkers can ask "is
   * var x bounded by ...?" and we can reuse cross-checker guards (e.g. DivZeroChecker records
   * {@code data != 0}, our overflow check consumes it).
   *
   * <p>value is a {@code List<String>} rather than a single string so that nested BRANCH guards on
   * the same variable (e.g. Juliet CWE-190 s67b goodB2GSink: {@code if (data > 0) { if (data <
   * Byte.MAX_VALUE/2) { data * 2 }}}) are all conjoined in the Z3 path condition. Previously this
   * map was {@code Map<TacVar, String>}; a second {@code emitGuard} call on the same var overwrote
   * the first, and nested guards never reached the solver.
   */
  private final Map<TacOperand.TacVar, java.util.List<String>> guards = new LinkedHashMap<>();

  /**
   * BRANCH/JUMP-aware literal comparison tracker (same machinery the strict taint checkers use).
   */
  private final GuardTracker guardTracker = new GuardTracker();

  /**
   * Set of "already-reported" (file, line, op) triples. The same subexpression might fire in
   * multiple positions (e.g. pre-pass + forward pass); this suppresses duplicates.
   */
  private final Set<String> reported = new LinkedHashSet<>();

  protected BoundaryConstraintChecker(TaintRule rule) {
    super(rule);
  }

  // ─────────── Subclass hooks ───────────

  /**
   * {@code "overflow"} / {@code "underflow"} / {@code "divide-by-zero"} / {@code "out-of-bounds"}.
   */
  protected abstract String hazardKind();

  /**
   * Is this instruction a sink for the subclass's hazard? E.g. Overflow returns true for {@code a +
   * b} on int / byte, DivZero returns true for {@code a / b} or {@code a % b}, ArrayBounds returns
   * true for {@code arr[i]} and {@code new T[i]}.
   */
  protected abstract boolean isBoundarySensitive(TacInstruction ins);

  /**
   * Build the "could this happen?" SMT predicate for the sink at {@code ins}, given the current
   * ranges. The predicate is in the grammar of {@link org.plumb.dfa.solver.LocalSolverBridge}
   * (arithmetic + comparison + boolean subset).
   *
   * <p>Return {@code null} if the hazard is impossible (e.g. for a literal divisor non-zero, the
   * div-zero predicate is {@code "false"}).
   */
  protected abstract String sinkPredicate(TacInstruction ins, AnalysisContext ctx);

  // ─────────── Lifecycle ───────────

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    super.initFunction(ctx, fn);
    ranges.clear();
    lineage.clear();
    guards.clear();
    guardTracker.clear();
    reported.clear();
  }

  // ─────────── Cross-function range seed ───────────

  /**
   * emit a {@link Operation.RangeSeed} per parameter whose range is concrete (not BOTTOM). The
   * caller-side {@link BoundaryConstraintChecker#propagateRangeFromCalleeSummary} consults these at
   * every {@code CALL} site to narrow the callee's view of the parameter to whatever the caller
   * actually passed in.
   *
   * <p>Range tracking in this checker is intra-proc ? without this seed, the callee sees {@code
   * data} as BOTTOM and over-refutes (or fails to refute) on the wide range. With this seed, the
   * caller's known-bounded {@code data = Byte.MIN_VALUE} reaches the callee and the callee
   * correctly refutes / fires based on the caller's view.
   */
  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, org.plumb.tac.ir.TacFunction fn) {
    FunctionSummary base = super.summarizeFunction(ctx, fn);
    FunctionSummary.Builder b = new FunctionSummary.Builder(fn.signature());
    // 1. Carry forward the parent's taint / sink /
    //    passthrough observations verbatim.
    //    only adds range seeds on top.
    for (var entry : base.paramOps().entrySet()) {
      int i = entry.getKey();
      for (Operation op : entry.getValue()) {
        b.recordParamOp(i, op);
      }
    }
    for (Operation op : base.returnOps()) {
      b.recordReturnOp(op);
    }
    // 2. emit a RangeSeed per param whose range
    //    is concrete (not BOTTOM). The caller-side
    //    will pick this up via the summary cache and
    //    feed the seeded range into the effective
    //    range map at the call site.
    java.util.List<org.plumb.tac.ir.TacOperand.TacVar> params = fn.params();
    for (int i = 0; i < params.size(); i++) {
      org.plumb.tac.ir.TacOperand.TacVar p = params.get(i);
      Range r = ranges.get(p);
      if (r == null || r.isBottom) continue;
      // Caller-side will join this with the
      // caller's own range of the arg. If the
      // caller's range is wider, the join keeps
      // the caller's value; if the caller's is
      // narrower, the seed narrows the callee.
      b.recordParamOp(i, new Operation.RangeSeed(i, r));
    }
    return b.build();
  }

  /**
   * at every {@code CALL}, consult the callee's {@link FunctionSummary} for {@link
   * Operation.RangeSeed}s and apply them to the corresponding caller-side arguments. Stores the
   * seeded ranges in {@link #callerSeededRanges} (a per-fn map keyed by the param's effective
   * {@link org.plumb.tac.ir.TacOperand.TacVar} in the caller's view). The actual meet happens at
   * query time via {@link #withCallerSeededRanges} so {@link #ranges} is never mutated.
   */
  private final Map<org.plumb.tac.ir.TacOperand.TacVar, Range> callerSeededRanges =
      new LinkedHashMap<>();

  /**
   * Caller-side entry point ? invoked from {@code checkInstruction} for every {@code CALL} after
   * the parent class has updated its taint state. Reads the callee's summary from {@code
   * ctx.summaryCache().get(calleeSig)} (with the same fallback chain {@link
   * org.plumb.dfa.taint.TaintedCheckerBase#propagateTaintFromCalleeSummary} uses), extracts the
   * per-param {@link Operation.RangeSeed}s, and stores the meet-of-caller-and-callee-seed in {@link
   * #callerSeededRanges}.
   */
  private void propagateRangeFromCalleeSummary(
      org.plumb.tac.ir.TacInstruction ins, AnalysisContext ctx) {
    callerSeededRanges.clear();
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;
    // Math.min / Math.max are clamp primitives, not ordinary calls. The
    // result's range is the intersection of the arguments' ranges, which is
    // exactly the narrowing a guard would express as
    // `if (x > LIMIT) x = LIMIT;`. Without this, `Math.min(n, 16)` left `n`
    // looking unbounded, so every downstream division / allocation check on the
    // clamped value fired -- a whole class of false positives.
    if (applyMathClamp(ins)) return;
    FunctionSummary calleeSummary = ctx.summaryCache().get(calleeSig);
    if (calleeSummary == null) return;
    for (var entry : calleeSummary.paramOps().entrySet()) {
      int i = entry.getKey();
      for (Operation op : entry.getValue()) {
        if (!(op instanceof Operation.RangeSeed seed)) continue;
        if (i >= ins.args.size()) continue;
        org.plumb.tac.ir.TacOperand arg = ins.args.get(i);
        if (!(arg instanceof org.plumb.tac.ir.TacOperand.TacVar av)) {
          continue;
        }
        Range callerRange = ranges.getOrDefault(av, Range.BOTTOM);
        Range meet;
        if (callerRange.isBottom) {
          meet = seed.range();
        } else {
          meet = callerRange.meet(seed.range());
        }
        callerSeededRanges.put(av, meet);
      }
    }
  }

  /**
   * model {@code Math.min(a, b)} / {@code Math.max(a, b)} as a range clamp.
   *
   * <p>{@code min} yields a value no greater than either argument and no less than the lower of the
   * two; {@code max} is the dual. In range terms both are the MEET (intersection) of the argument
   * ranges when both are known, which is precisely the fact a guard-based narrowing would
   * establish. This is what makes {@code Math.min(n, 16)} suppress the overflow / divide-by-zero /
   * allocation findings that the equivalent {@code if (n > 16) n = 16;} already suppresses.
   *
   * <p>Returns {@code false} when the call is not a recognised clamp, so the caller falls through
   * to the normal summary path.
   */
  private boolean applyMathClamp(org.plumb.tac.ir.TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return false;
    String sig = ins.calleeSig;
    if (sig == null) return false;
    boolean isMin =
        sig.equals("Math.min/2")
            || sig.equals("java.lang.Math.min/2")
            || sig.startsWith("Math.min/")
            || sig.startsWith("java.lang.Math.min/");
    boolean isMax =
        sig.equals("Math.max/2")
            || sig.equals("java.lang.Math.max/2")
            || sig.startsWith("Math.max/")
            || sig.startsWith("java.lang.Math.max/");
    if (!isMin && !isMax) return false;
    // args[0] is the synthesised receiver slot; user args are args[1] and args[2].
    if (ins.args.size() < 3) return false;
    Range a = rangeOf(ins.args.get(1));
    Range b = rangeOf(ins.args.get(2));
    if (a.isBottom && b.isBottom) {
      // Neither argument bounded: the clamp tells us nothing.
      return true;
    }
    // A clamp bounds the result on ONE side even when the other argument is unknown:
    //   min(x, K) <= K   and   max(x, K) >= K
    // This is the fact that makes `Math.max(0, Math.min(index, 2))` provably safe
    // even though `index` has no range at all. A plain meet() would NOT work here:
    // meet() treats BOTTOM as "no information" and returns the other side unchanged,
    // which throws away the clamp.
    //
    // For the UNKNOWN side we substitute the declared type's full range, so the
    // arithmetic below behaves uniformly in both the known and unknown cases.
    Range ra = a.isBottom ? Range.ofType(dest.type()) : a;
    Range rb = b.isBottom ? Range.ofType(dest.type()) : b;
    Range result = isMin ? ra.meet(rb) : ra.join(rb);
    if (result.isBottom) {
      // Disjoint: min/max still yields one of the two. Keep the wider so we never
      // under-approximate and invent a false refutation.
      ranges.put(dest, ra.join(rb));
      return true;
    }
    ranges.put(dest, result.withReason(isMin ? "Math.min clamp" : "Math.max clamp"));
    if (ins.args.get(1) instanceof TacOperand.TacVar v1) {
      lineage.put(dest, v1);
    }
    return true;
  }

  /**
   * query-time meet of the live {@link #ranges} with the per-fn {@link #callerSeededRanges}.
   * Mirrors {@link #withGuardsMetRanges}: never mutates {@link #ranges}; produces a new map the
   * caller uses for Tier-0 refutation. The caller's seeded range is joined (max over-approximation)
   * with the live range so a boundary check on a parameter that the caller narrowed sees the
   * narrowed range.
   */
  private Map<org.plumb.tac.ir.TacOperand.TacVar, Range> withCallerSeededRanges() {
    if (callerSeededRanges.isEmpty()) return ranges;
    Map<org.plumb.tac.ir.TacOperand.TacVar, Range> out = new LinkedHashMap<>(ranges);
    for (var entry : callerSeededRanges.entrySet()) {
      org.plumb.tac.ir.TacOperand.TacVar v = entry.getKey();
      Range seed = entry.getValue();
      Range live = out.getOrDefault(v, Range.BOTTOM);
      // Join: keep the wider interval. The
      // caller-seeded range is the
      // intersection of caller-known +
      // callee-summary; widening the live
      // range by it lets us keep the
      // over-approximation guarantee from
      // Range::join semantics.
      Range joined = live.join(seed);
      out.put(v, joined);
    }
    return out;
  }

  // ─────────── Forward pass ───────────

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    super.checkInstruction(ins, fn, ctx);
    guardTracker.track(ins);
    switch (ins.kind) {
      case MOVE -> propagateMove(ins);
      case BINOP -> propagateBinop(ins, ctx);
      case UNARYOP -> propagateUnaryop(ins);
      case FIELDLOAD -> propagateFieldLoad(ins);
      case CALL -> propagateRangeFromCalleeSummary(ins, ctx);
      default -> {
        /* ARRAYLOAD / ARRAYSTORE / NEWARRAY handled below */
      }
    }
    if (isBoundarySensitive(ins)) {
      String predicate = sinkPredicate(ins, ctx);
      if (predicate == null) return;
      // Ask Z3 whether the sink-constraint is
      // satisfiable given the current path
      // guards ranges.
      if (!isReachable(ctx, predicate, ins)) {
        return;
      }
      reportFinding(ins, fn, ctx, predicate);
    }
  }

  private void propagateMove(TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (ins.lhs1 instanceof TacOperand.TacVar src) {
      Range srcRange = ranges.getOrDefault(src, Range.BOTTOM);
      ranges.put(dest, srcRange);
      // Record direct lineage so a later
      // narrowing of `src`'s range can
      // cascade to `dest` (and through it
      // to whatever was MOVE-copied from
      // `dest`). Without this, 's
      // `int x = a; if (a < MAX-5) { x++; }`
      // pattern leaves x with the OLD range
      // of a (BOTTOM), and Z3 can't prove
      // x++ is safe.
      lineage.put(dest, src);
    } else if (ins.lhs1 instanceof TacOperand.Const c) {
      // `MOVE dest = Const` — IR uses MOVE
      // for both var and const sources;
      // we treat constants here so the
      // range is set, not just left as
      // BOTTOM.
      Long lv = asLong(c);
      if (lv != null) {
        ranges.put(dest, Range.ofConst(lv));
        // Const sources don't have a
        // lineage (they don't change);
        // drop any prior lineage so the
        // const wins.
        lineage.remove(dest);
      } else {
        ranges.put(dest, Range.BOTTOM.withReason("const non-numeric"));
        lineage.remove(dest);
      }
    }
  }

  /**
   * Cascade a narrowed range through the MOVE graph: BFS from {@code source}, narrowing every
   * {@code y} whose lineage ultimately points back at {@code source} (chains {@code z = y; y = x; x
   * = a}).
   */
  private void propagate(TacOperand.TacVar source) {
    Deque<TacOperand.TacVar> queue = new ArrayDeque<>();
    Set<TacOperand.TacVar> visited = new LinkedHashSet<>();
    queue.add(source);
    while (!queue.isEmpty()) {
      TacOperand.TacVar cur = queue.poll();
      if (!visited.add(cur)) continue;
      Range curRange = ranges.get(cur);
      if (curRange == null || curRange.isBottom) continue;
      for (var e : lineage.entrySet()) {
        TacOperand.TacVar y = e.getKey();
        TacOperand.TacVar parent = e.getValue();
        if (parent != cur) continue;
        Range oldY = ranges.getOrDefault(y, Range.BOTTOM);
        Range joined = oldY.join(curRange);
        if (!joined.equals(oldY)) {
          ranges.put(y, joined);
          queue.add(y);
        }
      }
    }
  }

  private void propagateConst(TacInstruction ins) {
    // No-op: TAC constants arrive via
    // MOVE dest = Const (no dedicated CONST
    // kind). propagateMove handles them.
  }

  private void propagateBinop(TacInstruction ins, AnalysisContext ctx) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    Range a = rangeOf(ins.lhs1);
    Range b = rangeOf(ins.lhs2);
    Range result =
        switch (ins.binop) {
          case ADD -> a.add(b);
          case SUB -> a.sub(b);
          case MUL -> a.mul(b);
          case DIV, MOD -> {
            // Division: if divisor is a known
            // non-zero constant, the result is
            // a finite range; otherwise
            // BOTTOM (we can't reason about
            // the result safely).
            if (b.isBottom || b.canBeZero()) {
              yield Range.BOTTOM.withReason("divisor may be 0");
            }
            yield Range.BOTTOM; // safe range is hard; keep BOTTOM
          }
          default -> Range.BOTTOM;
        };
    ranges.put(dest, result);
    // Also handle guarded comparisons:
    // if this is `BRANCH t` we
    // already handled it in guardTracker.track.
    if (ins.binop == BinaryOperator.LT
        || ins.binop == BinaryOperator.LE
        || ins.binop == BinaryOperator.GT
        || ins.binop == BinaryOperator.GE) {
      // On `c = a < b` we can refine `a`'s
      // range when `b` is a known single-
      // point value — either a literal
      // constant (existing case) or a
      // single-point TacVar (
      // `a < MAX-5` where MAX-5 was
      // computed into a temp). The
      // single-point TacVar case was
      // missed before , which is
      // why the safe-guard case in the
      // user brief still fired.
      if (ins.lhs2 instanceof TacOperand.Const c && ins.lhs1 instanceof TacOperand.TacVar lv) {
        Long cv = asLong(c);
        if (cv != null) {
          Range narrowed = narrowFromCompare(lv, ins.binop, cv).narrowToType(lv.type());
          ranges.merge(lv, narrowed, Range::join);
          emitGuard(lv, ins.binop, c.toString());
          propagate(lv);
        }
      }
      if (ins.lhs1 instanceof TacOperand.Const c && ins.lhs2 instanceof TacOperand.TacVar rv) {
        Long cv = asLong(c);
        if (cv != null) {
          Range narrowed = narrowFromCompare(rv, flipOp(ins.binop), cv).narrowToType(rv.type());
          ranges.merge(rv, narrowed, Range::join);
          emitGuard(rv, flipOp(ins.binop), c.toString());
          propagate(rv);
        }
      }
      // — single-point TacVar RHS.
      // If `b` is a known single-point var
      // (e.g. `t_t2 = MAX-5` followed by
      // `a < t_t2`), use its concrete
      // range to narrow `a`.
      if (ins.lhs1 instanceof TacOperand.TacVar lv && ins.lhs2 instanceof TacOperand.TacVar rv) {
        Range rhsRange = ranges.get(rv);
        if (rhsRange != null && !rhsRange.isBottom && rhsRange.lo == rhsRange.hi) {
          long cv = rhsRange.lo;
          Range narrowed = narrowFromCompare(lv, ins.binop, cv).narrowToType(lv.type());
          ranges.merge(lv, narrowed, Range::join);
          emitGuard(lv, ins.binop, String.valueOf(cv));
          propagate(lv);
        }
        Range lhsRange = ranges.get(lv);
        if (lhsRange != null && !lhsRange.isBottom && lhsRange.lo == lhsRange.hi) {
          long cv = lhsRange.lo;
          Range narrowed = narrowFromCompare(rv, flipOp(ins.binop), cv).narrowToType(rv.type());
          ranges.merge(rv, narrowed, Range::join);
          emitGuard(rv, flipOp(ins.binop), String.valueOf(cv));
          propagate(rv);
        }
      }
    }
  }

  private void propagateUnaryop(TacInstruction ins) {
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    if (!(ins.lhs1 instanceof TacOperand.TacVar src)) return;
    Range srcRange = ranges.getOrDefault(src, Range.BOTTOM);
    Range result =
        switch (ins.unop) {
          case NEG -> srcRange.neg();
          case PRE_INC, POST_INC -> srcRange.add(Range.ofConst(1));
          case PRE_DEC, POST_DEC -> srcRange.sub(Range.ofConst(1));
          default -> Range.BOTTOM;
        };
    ranges.put(dest, result);
    // Note: do NOT remove lineage[dest].
    // PRE_INC on `x` updates x in-place;
    // x may still inherit narrowing from its
    // original source (e.g. `int x = a;
    // ++x` means x is a+1 but lineage[x] =
    // a). Removing the lineage breaks the
    // cascade for in-place unary
    // ops on MOVE-copied vars.
  }

  private void propagateFieldLoad(TacInstruction ins) {
    // FIELDLOAD: dest = lhs1.fieldName
    //
    // — special-case well-known
    // static-final constants. The receiver
    // (lhs1) is the Class object; we look
    // up the value by (className, fieldName)
    // so `Integer.MAX_VALUE` (2147483647)
    // and `Byte.MAX_VALUE` (127) get the
    // correct type-aware range.
    //
    // General field-range tracking (for
    // instance fields) is out of scope
    // here — that's territory.
    if (!(ins.dest instanceof TacOperand.TacVar dest)) return;
    String className = receiverClassName(ins.lhs1);
    Range known = lookupStaticFinal(className, ins.fieldName);
    if (known != null) {
      ranges.put(dest, known);
    } else {
      ranges.put(dest, Range.BOTTOM.withReason("field-load"));
    }
    lineage.remove(dest);
  }

  /**
   * Return the simple class name of the FIELDLOAD receiver (lhs1), or null if we can't determine
   * it. The receiver type is what tells us whether {@code MAX_VALUE} is {@code Integer.MAX_VALUE}
   * (2147483647) or {@code Byte.MAX_VALUE} (127).
   *
   * <p>Heuristic: when {@code lhs1} has no recorded class (TacType.UNKNOWN), fall back to the
   * receiver var name. The TacTranslator often assigns {@code newTemp(TacType.UNKNOWN)} but keeps
   * the simple class name in the var name when the source-level expression was a class reference.
   */
  private static String receiverClassName(TacOperand lhs1) {
    if (!(lhs1 instanceof TacOperand.TacVar v)) return null;
    TacType t = v.type();
    if (t != null && t.kind() == TacType.Kind.CLASS) {
      String fqn = t.className();
      if (fqn != null) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
      }
    }
    // Fallback: parse the var name. The
    // IR often uses the simple class name
    // as the temp var name when the value
    // is a Class object (e.g. `t_t0` named
    // "Integer" for `Integer.class`).
    String name = v.name();
    // Strip trailing dots etc.
    if (name.isEmpty()) return null;
    // Skip generic-sounding names like t_t0.
    if (name.startsWith("t_t") || name.startsWith("_")) return null;
    return name;
  }

  /**
   * ? return the concrete Range of a well-known static-final field given the simple class name and
   * the field name. Returns null if the field is not in the table (e.g. user-defined constants ?
   * those go through FIELDSTORE tracking which is out of scope here).
   */
  private static Range lookupStaticFinal(String className, String fieldName) {
    if (className == null || fieldName == null) return null;
    // Map of (className, fieldName) → Range.
    // Encoding: pre-built static map keyed
    // by `className + "." + fieldName`.
    return STATIC_FINAL_TABLE.get(className + "." + fieldName);
  }

  /**
   * ? table of well-known static-final field values. Only compile-time constants that appear as
   * FIELDLOAD in TAC (not as literal Const).
   */
  private static final Map<String, Range> STATIC_FINAL_TABLE = new LinkedHashMap<>();

  static {
    // Integer family
    STATIC_FINAL_TABLE.put("Integer.MAX_VALUE", Range.ofConst(2147483647L));
    STATIC_FINAL_TABLE.put("Integer.MIN_VALUE", Range.ofConst(-2147483648L));
    STATIC_FINAL_TABLE.put("Integer.SIZE", Range.ofConst(32L));
    STATIC_FINAL_TABLE.put("Integer.BYTES", Range.ofConst(4L));
    // Long family
    STATIC_FINAL_TABLE.put("Long.MAX_VALUE", Range.ofConst(Long.MAX_VALUE));
    STATIC_FINAL_TABLE.put("Long.MIN_VALUE", Range.ofConst(Long.MIN_VALUE));
    STATIC_FINAL_TABLE.put("Long.SIZE", Range.ofConst(64L));
    STATIC_FINAL_TABLE.put("Long.BYTES", Range.ofConst(8L));
    // Short family
    STATIC_FINAL_TABLE.put("Short.MAX_VALUE", Range.ofConst(32767L));
    STATIC_FINAL_TABLE.put("Short.MIN_VALUE", Range.ofConst(-32768L));
    // Byte family
    STATIC_FINAL_TABLE.put("Byte.MAX_VALUE", Range.ofConst(127L));
    STATIC_FINAL_TABLE.put("Byte.MIN_VALUE", Range.ofConst(-128L));
    STATIC_FINAL_TABLE.put("Byte.SIZE", Range.ofConst(8L));
    STATIC_FINAL_TABLE.put("Byte.BYTES", Range.ofConst(1L));
    // Character family
    STATIC_FINAL_TABLE.put("Character.MAX_VALUE", Range.ofConst(65535L));
    STATIC_FINAL_TABLE.put("Character.MIN_VALUE", Range.ofConst(0L));
    STATIC_FINAL_TABLE.put("Character.SIZE", Range.ofConst(16L));
    STATIC_FINAL_TABLE.put("Character.BYTES", Range.ofConst(2L));
  }

  private void propagatePhi(TacInstruction ins) {
    // TAC doesn't have a dedicated PHI kind
    // (uses MOVE / MOVE-merge at join
    // blocks). No-op for now; future +
    // work can introduce explicit phi
    // tracking if needed.
  }

  // ─────────── Guards ───────────

  /**
   * Was the value of {@code v} bounded by {@code op} against literal {@code literal}? Push the
   * guard onto the {@link GuardStore} so other checkers can consume it.
   */
  private void emitGuard(TacOperand.TacVar v, BinaryOperator op, String literal) {
    // emit the operator as a Z3 DSL symbol
    // (`<`, `>`, `<=`, `>=`) rather than the Java
    // enum's `toString()` form (`LT`, `GT`, ...),
    // which the LocalSolverBridge does not
    // recognise and would silently leave the guard
    // uninterpreted (forcing a conservative SAT
    // and a spurious finding). Flip the operator
    // for the `literal on LHS, var on RHS` case
    // upstream before calling emitGuard, so the
    // raw `op` here is always in the
    // `var <op> literal` shape.
    String opSym =
        switch (op) {
          case LT -> "<";
          case LE -> "<=";
          case GT -> ">";
          case GE -> ">=";
          default -> op.toString();
        };
    String desc = v.name() + " " + opSym + " " + literal;
    // append rather than overwrite so nested
    // BRANCH guards on the same variable stack up
    // and all reach the Z3 conjunction in isReachable.
    guards.computeIfAbsent(v, k -> new java.util.ArrayList<>()).add(desc);
  }

  /**
   * Test whether the sink predicate is reachable given the current path guards + ranges.
   *
   * <p>The test is: can the conjunction of (all known path guards) AND (sink predicate) be
   * satisfied? If UNSAT, the path can't reach the sink and we don't report.
   *
   * <p>Three-tier:
   *
   * <ol>
   *   <li><b>Range fast-path</b> — if all sink variables have concrete single-point ranges and the
   *       predicate is trivially false under those values, UNSAT without calling the bridge.
   *   <li>Bridge check (conjoin all known guards + sink predicate, ask SAT).
   * </ol>
   */
  private boolean isReachable(AnalysisContext ctx, String sinkPredicate, TacInstruction ins) {
    // — Tier -2 NE-zero quick check. See
    // isRefutedByNonZeroGuard for full rationale.
    // Short version: the classic CWE-369 FP is
    // `if (data != 0) { 100 / data; }`. Tier 0
    // (range bounds) can't refute `data == 0`
    // because the range is wide; Tier 1 (Z3) is
    // missing the NE guard string because
    // `emitGuard` only emits BOUNDING_OPS. This
    // hook fires BEFORE Tier 0 so the cheap
    // NE-vs-sink check short-circuits both tiers.
    if (sinkPredicate != null && isRefutedByNonZeroGuard(sinkPredicate)) {
      return false;
    }
    // Tier -1 type-bound refutation. The
    // classic /772a-blind cases look like
    // `(data < -2147483648)` (data is int — that
    // literal is below Integer.MIN_VALUE, so the
    // comparison is provably false regardless of
    // the data range). Tier 0 only handles
    // ranges recorded in `ranges`/`guards`; the
    // type's own [-MIN, MAX] bound is not in those
    // structures, so these semantic-impossible
    // predicates fall through to Tier 1, where
    // the LocalSolverBridge treats `data` as a
    // free variable and conservatively returns
    // SAT. parses the predicate and refutes
    // any compare whose literal is provably
    // outside the var's declared type.
    //
    // E2E outcome ( investigation):
    // 2016 over-refutations of `((t_t0 - -128)) <
    // -2147483648` style predicates where the
    // arithOp shifts the var's value out of the
    // type range entirely (e.g. byte `t_t0 + 128`
    // is always ≥ 0, so `< MIN` is always true
    // and the predicate IS reachable, not
    // refutable). 's `adjLit` adjustment
    // trick (treating `(var + k) < lit` as
    // `var < lit - k` and comparing against
    // typeR.lo) is correct in the *single-var-no-
    // shift* case but under-refutes the post-shift
    // value (a byte + 128 is in [0, 255], not in
    // [-128, 127]). Reverted before ship; the
    // LocalSolverBridge already correctly handles
    // the no-shift case (see smoke test
    // in TestSolver3.java), so is redundant
    // for the case it tries to address.
    //
    // reverted: do not invoke the helper.
    // temporarily meet the in-ranges map
    // with ranges derived from the active guard
    // stack (one-shot, no Map mutation — preserves
    // the `Range::join` path-insensitive
    // over-approximation between BRANCH back-edges).
    // This lets Tier 0 (`intervalRefutesSinkPredicate`)
    // see the conjuncted range and refute nested-guard
    // patterns like `if (data > 0) { if (data < 63) {
    // data * 2 }}` without requiring a worklist
    // re-analysis (the blocker 1 of ).
    //
    // also fold in the per-fn caller-seeded
    // ranges from the most recent CALL (if any).
    // Layered: live ranges -> guards meet -> caller
    // seeds join. Same read-only discipline as
    // never mutates `ranges`.
    Map<TacOperand.TacVar, Range> effective = withCallerSeededRanges();
    if (intervalRefutesSinkPredicateWith(sinkPredicate, withGuardsMetRanges(effective))) {
      return false;
    }
    // Tier 1: build bindings from single-point
    // ranges per-variable range guard clauses.
    Map<String, Object> bindings = new LinkedHashMap<>();
    StringBuilder conjunction = new StringBuilder();
    for (var entry : ranges.entrySet()) {
      Range r = entry.getValue();
      if (!r.isBottom) {
        if (r.lo == r.hi) {
          bindings.put(entry.getKey().name(), r.lo);
        } else {
          String g =
              "("
                  + entry.getKey().name()
                  + " >= "
                  + r.lo
                  + " && "
                  + entry.getKey().name()
                  + " <= "
                  + r.hi
                  + ")";
          if (conjunction.length() > 0) conjunction.append(" && ");
          conjunction.append(g);
        }
      }
    }
    for (java.util.List<String> guardList : guards.values()) {
      for (String g : guardList) {
        if (conjunction.length() > 0) conjunction.append(" && ");
        conjunction.append("(").append(g).append(")");
      }
    }
    if (conjunction.length() > 0) {
      conjunction.append(" && ");
    }
    conjunction.append("(").append(sinkPredicate).append(")");
    SolverZ3Bridge bridge = solverBridge();
    if (bridge == null) return true;
    SolverZ3Bridge.Result r;
    try {
      r = bridge.check(conjunction.toString(), bindings);
    } catch (NoClassDefFoundError ncde) {
      // in rare cases (e.g. shade-plugin inner-class loader
      // issues when scanning very large merged directories), the
      // bridge's transitive classes may not be loadable. Treat as
      // "solver unavailable" → conservatively report (SAT) so the
      // scanner can finish rather than crashing mid-run. The next
      // scan with the same JAR will re-evaluate.
      return true;
    } catch (RuntimeException re) {
      // Malformed predicate or unexpected solver failure: report
      // conservatively. Same rationale as above.
      return true;
    }
    if (r == SolverZ3Bridge.Result.UNSAT) {
      return false;
    }
    return true; // SAT / UNKNOWN → report
  }

  /**
   * ? Tier -2 NE-zero quick check. The classic CWE-369 false positive comes from Juliet {@code
   * good*} functions that gate a divisor with an explicit non-zero check, e.g.:
   *
   * <pre>{@code
   * if (data != 0) {
   *     IO.writeLine("" + (100 / data));
   * }
   * }</pre>
   *
   * <p>Plumb's DivZero sink predicate is {@code "(data == 0)"}. Tier 0 (interval ranges) cannot
   * refute it because the {@code data} range is {@code [-Integer.MAX_VALUE, +Integer.MAX_VALUE]} —
   * a range that includes 0. Tier 1 (Z3 SMT) <em>would</em> refute the conjunction {@code (data ==
   * 0) ∧ (data != 0)}, but the active guard for {@code data} is recorded only in {@link
   * GuardTracker#activeGuards} (NON_ZERO_OPS path); the {@code BoundaryConstraintChecker#guards}
   * map (which Tier 1 consults via {@link #emitGuard}) only carries BOUNDING_OPS guards
   * (LT/LE/GT/GE), and the {@code withGuardsMetRanges} Tier-0 helper doesn't recognise NE either.
   * So the NE guard never reaches Z3 and the finding fires as a false positive.
   *
   * <p>This helper is the cheap Tier-2 patch: parse the sink predicate to extract the {@code (var
   * <op> <literal>)} shape, then scan the active guard stack for a matching {@code (var NE
   * <literal>)} guard. If found, the conjunction is UNSAT (the divisor can't equal the literal
   * because the guard says it can't) → return {@code true} → don't fire.
   *
   * <h2>Limitation (deliberate)</h2>
   *
   * <p>Only handles the literal-on-RHS shape {@code var != <literal>} (and the symmetric {@code
   * <literal> != var} emitted via {@code recordCompareIfApplicable}'s flipped branch). General
   * {@code data < c1 || data > c2} ranges that exclude 0 are not handled here ? Tier 0's {@code
   * withGuardsMetRanges} already meets those into the effective range and refutes when the literal
   * 0 is outside. Future R-Round could lift this helper to a generic "active-guards vs.
   * sink-predicate" SMT mini- solver, but keeps the surface tight.
   *
   * <h2>Why not just emit NE into {@code guards}</h2>
   *
   * <p>NE guards carry no useful <em>narrowing</em> information for the Range lattice (the variable
   * could still be any value except the literal), so emitting them would inflate the Tier-1
   * conjunction without helping Tier 0. The proper destination is a separate {@code nonZeroGuards}
   * map consulted only by refutation helpers ? that's a larger refactor. For we read GuardTracker
   * directly because it already holds the right set and is the same source of truth the {@code
   * isGuardedBy(rhs, NE)} query would consult.
   */
  private boolean isRefutedByNonZeroGuard(String sinkPredicate) {
    if (sinkPredicate == null || guardTracker == null) return false;
    // Parse the sink predicate for the
    // `var <op> <literal>` shape (optionally
    // with `var +/- k` arith on the LHS, as
    // emitted by some Boundary sinks).
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "^[\\(\\s]*([A-Za-z_][A-Za-z_0-9]*)"
                    + "(?:\\s*([+\\-])\\s*(-?\\d+))?"
                    + "[\\)\\s]*"
                    + "\\s*(==|!=|<=|<|>=|>)\\s*(-?\\d+)[\\s)]*$")
            .matcher(sinkPredicate.trim());
    if (!m.matches()) return false;
    String sinkVar = m.group(1);
    String sinkArithOp = m.group(2);
    String sinkArithLit = m.group(3);
    String sinkOp = m.group(4);
    long sinkLit = Long.parseLong(m.group(5));
    // Only refute when the sink predicate is
    // an equality (==) or inequality (!=)
    // against a specific literal. The
    // classic case is `(var == 0)`; we also
    // handle `(var != 0)` (which is itself
    // trivially refutable if a positive
    // `var NE 0` guard exists, but only
    // for symmetry — both directions
    // (==/!=) are checked below).
    if (!("==".equals(sinkOp) || "!=".equals(sinkOp))) return false;
    // Account for `var + k` LHS arith: a
    // sink `(var + k) == L` is satisfiable
    // iff `var == L - k`. The matching NE
    // guard would be `var != L - k`. So we
    // shift the literal to the "value of
    // var" axis.
    long adjustedLit = sinkLit;
    if ("+".equals(sinkArithOp)) {
      adjustedLit = sinkLit - Long.parseLong(sinkArithLit);
    } else if ("-".equals(sinkArithOp)) {
      adjustedLit = sinkLit + Long.parseLong(sinkArithLit);
    } else if (sinkArithOp != null) {
      // * not handled here — the only
      // sinks that use arith on the LHS
      // are array-out-of-bounds (k is
      // usually 0 or 1) and integer
      // boundary. Multiplicative sinks
      // (`var * k == L`) are rare; fall
      // through to Tier 0/1.
      return false;
    }
    // Scan the active guard stack for a
    // matching NE on the same var against
    // the same adjusted literal. We match
    // `var != 0` specially (the most
    // common form) and `var != <lit>` in
    // general. We do NOT match `var !=
    // <different-literal>` — that wouldn't
    // refute the sink.
    for (GuardTracker.Guard g : guardTracker.activeGuards()) {
      if (g.op() != BinaryOperator.NE) continue;
      if (!g.boundedVar().name().equals(sinkVar)) continue;
      String litDesc = g.literalDescription();
      if (litDesc == null) continue;
      try {
        long guardLit = Long.parseLong(litDesc.trim());
        if (guardLit == adjustedLit) return true;
      } catch (NumberFormatException nfe) {
        // Field-load constants (e.g.
        // "Byte.MAX_VALUE") — fall back
        // to string equality against
        // the sink's literal
        // description. We need the sink
        // literal's string form; for
        // ints we can recover it from
        // the parsed number.
        if (litDesc.trim().equals(String.valueOf(adjustedLit))) {
          return true;
        }
      }
    }
    // the early-return guard idiom.
    //
    //   if (data == 0) { return 0; }
    //   return a / data;
    //
    // The `data == 0` guard is pushed when the BRANCH is seen and popped by the JUMP
    // at the end of the if-block, so it is long gone from the active stack by the time
    // the division is reached -- the code after an if-block is the FALSE branch, where
    // the condition provably does not hold. GuardTracker.onJump now records that
    // fall-through fact (an EQ guard excludes its literal from here on), which is what
    // this consults. Without it the classic CWE-369 safe idiom is a false positive
    // while the symmetric `if (data != 0) { ... }` form was already suppressed.
    if (guardTracker.isRefutedBy(sinkVar, String.valueOf(adjustedLit))) {
      return true;
    }
    return false;
  }

  /**
   * Tier-0 interval-only check. Returns {@code true} when the sink predicate is provably
   * unsatisfiable given the concrete ranges of every variable it references (no bridge needed).
   *
   * <p>Only handles the simple {@code var <op> <literal>} shape we emit; complex predicates fall
   * through to the bridge. If any referenced var has no range entry, we conservatively return
   * {@code false} (don't refute).
   */
  private boolean intervalRefutesSinkPredicate(String sinkPredicate) {
    // build an effective-range map that
    // meets every active guard's derived range
    // into the corresponding `ranges` entry,
    // then run the standard Tier 0 check against
    // that effective map. This is read-only —
    // it does NOT mutate `this.ranges`, so the
    // path-insensitive over-approximation
    // (Range::join) is preserved between BRANCH
    // back-edges.
    return intervalRefutesSinkPredicateWith(sinkPredicate, withGuardsMetRanges());
  }

  /**
   * returns a copy of {@code ranges} with each var's range intersected with the range derived from
   * every active guard on that var. Returns the original map unchanged when no guards are present
   * (so existing case is a no-op).
   *
   * <p>Guard strings are in the canonical {@code "<name> <sym> <literal>"} format that {@link
   * #emitGuard} emits (post-). We parse them to recover the implied range and meet it with the
   * current range.
   */
  private Map<TacOperand.TacVar, Range> withGuardsMetRanges() {
    return withGuardsMetRanges(ranges);
  }

  /**
   * same as {@link #withGuardsMetRanges()} but operates on a caller-supplied range map. Used to
   * layer the guard-derived meet on top of the caller-seeded meet. The input map is treated as
   * read-only; we copy.
   */
  private Map<TacOperand.TacVar, Range> withGuardsMetRanges(Map<TacOperand.TacVar, Range> base) {
    if (guards.isEmpty()) return base;
    java.util.regex.Matcher symMatcher =
        java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*(<=|>=|<|>)\\s*(-?\\d+)")
            .matcher("");
    Map<TacOperand.TacVar, Range> effective = new LinkedHashMap<>(base);
    for (var entry : base.entrySet()) {
      TacOperand.TacVar v = entry.getKey();
      Range r = entry.getValue();
      java.util.List<String> guardList = guards.get(v);
      if (guardList == null) continue;
      Range narrowed = r;
      for (String g : guardList) {
        java.util.regex.Matcher m = symMatcher.reset(g);
        if (!m.find()) continue;
        String sym = m.group(2);
        long lit = Long.parseLong(m.group(3));
        long newLo, newHi;
        switch (sym) {
          case "<" -> {
            newLo = Long.MIN_VALUE / 2;
            newHi = lit - 1;
          }
          case "<=" -> {
            newLo = Long.MIN_VALUE / 2;
            newHi = lit;
          }
          case ">" -> {
            newLo = lit + 1;
            newHi = Long.MAX_VALUE / 2;
          }
          case ">=" -> {
            newLo = lit;
            newHi = Long.MAX_VALUE / 2;
          }
          default -> {
            continue;
          }
        }
        Range guardR = Range.of(newLo, newHi);
        narrowed = narrowed.meet(guardR);
      }
      if (!narrowed.equals(r)) {
        effective.put(v, narrowed);
      }
    }
    return effective;
  }

  /**
   * Tier-0 refutation check using a caller-supplied range map (so we can run it against the
   * effective ranges after meeting in active guards, without mutating {@link #ranges}).
   */
  private boolean intervalRefutesSinkPredicateWith(
      String sinkPredicate, Map<TacOperand.TacVar, Range> effectiveRanges) {
    // Recursively strip outer parens.
    String p = sinkPredicate.trim();
    while (p.startsWith("(") && p.endsWith(")") && matchedParens(p)) {
      p = p.substring(1, p.length() - 1).trim();
    }
    // Disjunction: refuted only if BOTH
    // branches are refuted.
    int orIdx = findTopLevelOr(p);
    if (orIdx >= 0) {
      String left = p.substring(0, orIdx).trim();
      String right = p.substring(orIdx + 2).trim();
      return intervalRefutesSinkPredicateWith(left, effectiveRanges)
          && intervalRefutesSinkPredicateWith(right, effectiveRanges);
    }
    // Conjunction: refuted only if EITHER
    // branch is refuted.
    int andIdx = findTopLevelAnd(p);
    if (andIdx >= 0) {
      String left = p.substring(0, andIdx).trim();
      String right = p.substring(andIdx + 2).trim();
      return intervalRefutesSinkPredicateWith(left, effectiveRanges)
          || intervalRefutesSinkPredicateWith(right, effectiveRanges);
    }
    // Atomic: var <op> literal — look up range
    // in the supplied effective map.
    return atomicRefutesWith(p, effectiveRanges);
  }

  /**
   * Tier -1 type-bound refutation. Walks the sink predicate and refutes any atomic compare whose
   * literal is provably outside the instruction's destination type's [lo, hi] range. Catches the
   * classic free-var-conservative-SAT cases that Tier 0 cannot see (e.g. `(data < -2147483648)` for
   * an int `data`).
   *
   * <p>Same shape as {@link #atomicRefutesWith} but uses the <b>type's</b> type range (not the
   * recorded per-var range), since the type range is the most-over-approximated possible bound for
   * any value of the var. Combined with the active guard stack, this gives the free `data` var a
   * useful refutation context.
   */
  // helper methods removed (reverted before
  // ship — see SUMMARY for full root cause).

  /**
   * like {@link #atomicRefutes} but consults a caller-supplied range map instead of the live {@link
   * #ranges}. The supplied map is what {@link #withGuardsMetRanges} produced (live ranges already
   * met with guard-derived ranges for each var).
   */
  private boolean atomicRefutesWith(String p, Map<TacOperand.TacVar, Range> effective) {
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "^[\\(\\s]*([A-Za-z_][A-Za-z_0-9]*)"
                    + "(?:\\s*([+\\-*])\\s*(-?\\d+))?"
                    + "[\\)\\s]*"
                    + "\\s*(==|!=|<=|<|>=|>)\\s*(-?\\d+)$")
            .matcher(p.trim());
    if (!m.matches()) return false;
    String name = m.group(1);
    String arithOp = m.group(2);
    String arithLit = m.group(3);
    String op = m.group(4);
    long lit = Long.parseLong(m.group(5));
    Range r = null;
    for (var e : effective.entrySet()) {
      if (e.getKey().name().equals(name)) {
        r = e.getValue();
        break;
      }
    }
    if (r == null || r.isBottom) return false;
    if ("*".equals(arithOp)) {
      long k = Long.parseLong(arithLit);
      if (k == 0) return false;
      long loK = r.lo * k;
      long hiK = r.hi * k;
      long loProd = Math.min(loK, hiK);
      long hiProd = Math.max(loK, hiK);
      return switch (op) {
        case ">" -> hiProd < lit;
        case ">=" -> hiProd < lit;
        case "<" -> loProd > lit;
        case "<=" -> loProd > lit;
        case "==" -> loProd > lit || hiProd < lit;
        case "!=" -> loProd == lit && hiProd == lit;
        default -> false;
      };
    }
    long adjustedLit = lit;
    if ("+".equals(arithOp)) adjustedLit = lit - Long.parseLong(arithLit);
    else if ("-".equals(arithOp)) adjustedLit = lit + Long.parseLong(arithLit);
    else if (arithOp != null) return false;
    return switch (op) {
      case "==" -> r.lo > adjustedLit || r.hi < adjustedLit;
      case "!=" -> r.lo == adjustedLit && r.hi == adjustedLit;
      case "<" -> r.lo >= adjustedLit;
      case "<=" -> r.lo > adjustedLit;
      case ">" -> r.hi <= adjustedLit;
      case ">=" -> r.hi < adjustedLit;
      default -> false;
    };
  }

  /**
   * True iff the leading {@code (} matches the trailing {@code )} (not a paren of an inner
   * expression that happens to be at position 0 of the string).
   */
  private static boolean matchedParens(String s) {
    int depth = 0;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') {
        depth--;
        if (depth == 0) return i == s.length() - 1;
      }
    }
    return false;
  }

  /** Find the index of {@code ||} at paren-depth 0, or -1. */
  private static int findTopLevelOr(String s) {
    int depth = 0;
    for (int i = 0; i < s.length() - 1; i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth--;
      else if (depth == 0 && c == '|' && s.charAt(i + 1) == '|') {
        return i;
      }
    }
    return -1;
  }

  /** Find the index of {@code &&} at paren-depth 0, or -1. */
  private static int findTopLevelAnd(String s) {
    int depth = 0;
    for (int i = 0; i < s.length() - 1; i++) {
      char c = s.charAt(i);
      if (c == '(') depth++;
      else if (c == ')') depth--;
      else if (depth == 0 && c == '&' && s.charAt(i + 1) == '&') {
        return i;
      }
    }
    return -1;
  }

  /**
   * Single {@code name <op> <literal>} form. Returns {@code true} when the range of {@code name}
   * makes the comparison impossible.
   */
  private boolean atomicRefutes(String p) {
    // Match either
    //   <ident> (<op>) <literal>
    //   (<ident> +|-|* <literal>) (<op>) <literal>
    //   (<ident> - <literal>) (<op>) <literal>
    // so we can handle the `(x + 1) > 127`
    // and the `(x * 2) > 127` shapes that
    // overflow / underflow predicates take
    // without needing Z3. extended the
    // arith-op group to include `*` (
    // blocker 3) so the classic CWE-190 /
    // CWE-191 overflow predicate
    // `((data * 2)) > 127` can be refuted
    // directly from the Range lattice when
    // `data` is provably bounded.
    //
    // arithLit is optional — when absent,
    // the predicate is just `<name> <op>
    // <lit>`.
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                "^[\\(\\s]*([A-Za-z_][A-Za-z_0-9]*)"
                    + "(?:\\s*([+\\-*])\\s*(-?\\d+))?"
                    + "[\\)\\s]*"
                    + "\\s*(==|!=|<=|<|>=|>)\\s*(-?\\d+)$")
            .matcher(p.trim());
    if (!m.matches()) return false;
    String name = m.group(1);
    String arithOp = m.group(2);
    String arithLit = m.group(3);
    String op = m.group(4);
    long lit = Long.parseLong(m.group(5));
    // Look up the range.
    Range r = null;
    for (var e : ranges.entrySet()) {
      if (e.getKey().name().equals(name)) {
        r = e.getValue();
        break;
      }
    }
    if (r == null || r.isBottom) return false;
    // when arithOp is `*`, we cannot
    // simply `adjustedLit = lit - arithV` like
    // +/- (which is linear). Instead, the
    // entire `<expr> <op> <lit>` shape is
    // reduced to a pure interval-arithmetic
    // refutation:
    //   (data * k) > C  refutes iff  range.hi * k < C
    //   (data * k) < C  refutes iff  range.lo * k > C
    //   (data * k) >= C refutes iff  range.hi * k < C
    //   (data * k) <= C refutes iff  range.lo * k > C
    //   (data * k) == C refutes iff  range is
    //                          disjoint from {C/k}
    //   (data * k) != C: never refute from a
    //                          single range alone
    // (Same for `*` with k possibly negative,
    // the range is preserved on lo/hi extremes
    // by sign of k — handled below.)
    if ("*".equals(arithOp)) {
      long k = Long.parseLong(arithLit);
      if (k == 0) return false; // 0 * k always 0, no refutation
      // Apply mul: new_lo = min(lo*k, hi*k),
      // new_hi = max(lo*k, hi*k) so that the
      // resulting range reflects what
      // (data * k) can take.
      long loK = r.lo * k;
      long hiK = r.hi * k;
      long loProd = Math.min(loK, hiK);
      long hiProd = Math.max(loK, hiK);
      return switch (op) {
        case ">" -> hiProd < lit;
        case ">=" -> hiProd < lit;
        case "<" -> loProd > lit;
        case "<=" -> loProd > lit;
        case "==" -> loProd > lit || hiProd < lit;
        case "!=" -> loProd == lit && hiProd == lit;
        default -> false;
      };
    }
    long adjustedLit = lit;
    if ("+".equals(arithOp)) adjustedLit = lit - Long.parseLong(arithLit);
    else if ("-".equals(arithOp)) adjustedLit = lit + Long.parseLong(arithLit);
    else if (arithOp != null) return false;
    return switch (op) {
      case "==" -> r.lo > adjustedLit || r.hi < adjustedLit;
      case "!=" -> r.lo == adjustedLit && r.hi == adjustedLit;
      case "<" -> r.lo >= adjustedLit;
      case "<=" -> r.lo > adjustedLit;
      case ">" -> r.hi <= adjustedLit;
      case ">=" -> r.hi < adjustedLit;
      default -> false;
    };
  }

  // ─────────── Reporting ───────────

  private void reportFinding(
      TacInstruction ins, TacFunction fn, AnalysisContext ctx, String sinkPred) {
    // Dedup key: line + column + hazard. Per-function dedup because
    // initFunction clears `reported`, so collisions across functions
    // are impossible.
    String key = fnKey(ins, hazardKind());
    if (!reported.add(key)) return;
    String title = hazardKind();
    if (!title.isEmpty()) {
      title = Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }
    String flow = hazardKind() + ": " + sinkPred;
    // emit a single-step violation FlowStep with the enclosing
    // function's signature so ScanRunner.toReporterFinding can lift it
    // into the NDJSON `sink_function` field. Boundary series fire at
    // the hazard point itself (no taint propagation), so role="violation"
    // and expr=flow are the correct shape.
    FlowStep step =
        new FlowStep(
            "violation",
            "",
            ins.line,
            ins.column,
            fn == null ? "" : (fn.signature() == null ? "" : fn.signature()),
            flow);
    Finding f =
        Finding.dfa(
            this.rule.ruleId(),
            this.rule.ruleId(),
            title + " risk: " + ins,
            this.rule.severity(),
            this.rule.cwe(),
            "",
            ins.line,
            ins.column,
            flow,
            java.util.List.of(step));
    ctx.reporter().report(f);
  }

  // ─────────── Helpers ───────────

  private Range rangeOf(TacOperand op) {
    if (op instanceof TacOperand.TacVar v) {
      return ranges.getOrDefault(v, Range.BOTTOM);
    }
    if (op instanceof TacOperand.Const c) {
      Long lv = asLong(c);
      if (lv != null) return Range.ofConst(lv);
    }
    return Range.BOTTOM;
  }

  private static Long asLong(TacOperand.Const c) {
    Object v = c.value();
    if (v instanceof Long lv) return lv;
    if (v instanceof Integer iv) return (long) iv;
    if (v instanceof Short sv) return (long) sv;
    if (v instanceof Byte bv) return (long) bv;
    if (v instanceof Character cv) return (long) (int) cv;
    return null;
  }

  private static BinaryOperator flipOp(BinaryOperator op) {
    return switch (op) {
      case LT -> BinaryOperator.GT;
      case LE -> BinaryOperator.GE;
      case GT -> BinaryOperator.LT;
      case GE -> BinaryOperator.LE;
      default -> op;
    };
  }

  /** Refine a range from a comparison with a constant. Conservative: only narrows, never widens. */
  private static Range narrowFromCompare(TacOperand.TacVar v, BinaryOperator op, long c) {
    return switch (op) {
      case LT -> Range.of(Long.MIN_VALUE / 2, c - 1);
      case LE -> Range.of(Long.MIN_VALUE / 2, c);
      case GT -> Range.of(c + 1, Long.MAX_VALUE / 2);
      case GE -> Range.of(c, Long.MAX_VALUE / 2);
      default -> Range.BOTTOM;
    };
  }

  /**
   * Make a stable dedup key for the (line, column, hazard-kind) tuple. Per-function because
   * initFunction clears `reported`.
   */
  private static String fnKey(TacInstruction ins, String hazard) {
    return ins.line + ":" + ins.column + ":" + hazard;
  }
}
