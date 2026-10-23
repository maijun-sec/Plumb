package org.plumb.dfa.taint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.dfa.summary.FieldOp;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Per-function tracker for taint propagation through {@code this.f} field writes and reads.
 *
 * <p>Background: a function like {@code void setData(String d) { this.data = d; }} writes a
 * parameter to an instance field. The caller-side taint checker that does {@code
 * setData(tainted_arg); ...; use(this.data); } needs the taint to flow from {@code arg} to {@code
 * this.data} via the callee's {@link org.plumb.dfa.summary.FunctionSummary}.
 *
 * <p>The {@link org.plumb.dfa.summary.FunctionSummary} records a {@code fieldOps} list of field
 * writes (each with the source's param index). The caller-side application: when the caller hits a
 * CALL whose callee has {@code fieldOps}, the tracker seeds {@code this.f} with taint from the
 * matching caller arg. When the caller later reads {@code this.f} (a {@code FIELDLOAD}), the
 * tracker restores the taint so the loaded value is correctly marked as tainted.
 *
 * <h2>Cross-instance field tracking</h2>
 *
 * <p>The base tracker only handled the {@code this.f} case ? the receiver was implicitly assumed to
 * be {@code this}. The tracker now handles <b>any</b> receiver:
 *
 * <pre>
 *   class Helper {
 *     String data;
 *     void setData(String d) { this.data = d; }
 *   }
 *   class Caller {
 *     void bad(HttpServletRequest req, Connection conn) {
 *       Helper h = new Helper();
 *       String u = req.getParameter("u");
 *       h.setData(u);  // args[0] = h (a TacVar)
 *       Statement s = conn.createStatement();
 *       s.execute("... " + h.data + " ...");  // FIELDLOAD h.data
 *     }
 *   }
 * </pre>
 *
 * The naive fold would seed {@code this.data} in the caller (which is wrong ? there's no {@code
 * this.data} here; the field lives on {@code h}). The tracker keys by {@code (receiver, field)}
 * pairs and uses the actual receiver from {@code args[0]} at the call site. The {@code this.f} case
 * is now a strict subset (keyed as {@code ("this", f)}).
 *
 * <h2>FN_DEEP-style patterns</h2>
 *
 * <p>The classic Juliet test case:
 *
 * <pre>
 *   class DeepFlow {
 *     String data;  // field
 *     public void bad(HttpServletRequest req, Connection conn) {
 *       String u = req.getParameter("u");
 *       setData(u);  // this.data = u  (cross-function)
 *       Statement s = conn.createStatement();
 *       s.execute("SELECT * FROM t WHERE u='" + data + "'");
 *     }
 *     void setData(String d) { this.data = d; }
 *   }
 * </pre>
 *
 * The {@code data} read in the {@code s.execute} line is a {@code FIELDLOAD} of {@code this.data}.
 * Without field tracking, the taint would be lost at the {@code setData(u)} call site (the callee's
 * {@code TaintPropagate} on param 0 is applied to the caller's arg, but the field write inside the
 * callee is not propagated back to the caller's {@code this.data}). The cross-function fold fixes
 * this by walking the callee's {@code fieldOps} at the call site and seeding the caller's tracker
 * with {@code this.data} ← {@code arg 0}.
 *
 * <h2>Scope (deliberately limited)</h2>
 *
 * <ul>
 *   <li><b>Any instance field, any receiver</b>. The earlier {@code this.f}-only case is a strict
 *       subset. Cross-class instance fields are now supported. Static fields are still deferred
 *       (they have no receiver in the IR).
 *   <li><b>Per-function state</b> — the tracker is reset at every function boundary. The tracker
 *       does NOT persist across functions; the cross-function flow goes through the {@link
 *       org.plumb.dfa.summary.FunctionSummaryCache}.
 *   <li><b>Last-write-wins</b> — multiple writes to the same {@code (receiver, field)} pair in one
 *       function: the later write's taint state is what subsequent reads see. The earlier write is
 *       silently overwritten. This is sound for a path-insensitive intra-function analysis (we take
 *       the union of paths; a later write would be the one observed by any subsequent read).
 *   <li><b>TacVar-name keying</b> — the receiver is the {@link TacOperand.TacVar#name()} of the
 *       FIELDSTORE/FIELDLOAD's {@code lhs1}. The fold uses the CALL's {@code args[0]} name
 *       directly. Multiple SSA versions of the same source-level variable share a name
 *       (conservative ? sound for path-insensitive intra-fn analysis).
 * </ul>
 *
 * <h2>Where it fits in the engine</h2>
 *
 * <p>Used by {@link TaintedCheckerBase}:
 *
 * <ul>
 *   <li>{@code initFunction} ? the tracker is cleared.
 *   <li>{@code checkInstruction} ? {@code FIELDSTORE} branch calls {@link #recordWrite(String,
 *       String, List)}; {@code FIELDLOAD} branch calls {@link #readField(String)} to restore taint.
 *   <li>{@code propagateTaintFromCalleeSummary} ? walks the callee's {@code fieldOps} and calls
 *       {@link #applyCalleeFieldOp(FieldOp, int, String, List, TacOperand, String, int, int,
 *       String)} for each.
 * </ul>
 */
public final class FieldTaintTracker {

  /**
   * A snapshot of a field's taint state.
   *
   * <p><b>R-804 follow-up</b> — {@code v2Handle} is the v2 {@code Trace} node id of the source this
   * field write propagated from. When a subsequent {@code FIELDLOAD} reads the field, the loader
   * can attach a real parent to the new {@code PROPAGATE} node via {@code
   * addPropagate(parents=[v2Handle], ...)} instead of treating the load as a fresh orphan SOURCE
   * entry. {@code v2Handle=-1} means "no recovered handle (the writer predates v2 dual-write, or
   * the taint came from a non-tracked path)" — in that case the loader still falls back to the
   * orphan-SOURCE shape from R-804.
   */
  public record TrackedTaint(String label, List<FlowStep> path, int v2Handle) {
    public TrackedTaint {
      Objects.requireNonNull(label, "label");
      path = path == null ? List.of() : List.copyOf(path);
    }

    /** Convenience for callers that don't have a v2 handle — defaults to {@code -1} (unknown). */
    public TrackedTaint(String label, List<FlowStep> path) {
      this(label, path, -1);
    }
  }

  /**
   * Compose the internal tracker key from a {@code (receiver, field)} pair. Uses {@code "."} as the
   * separator (matches the IR {@code lhs1.fieldName} display format). Returns {@code null} if
   * either component is null or empty.
   */
  private static String keyOf(String receiver, String fieldName) {
    if (receiver == null || receiver.isEmpty()) return null;
    if (fieldName == null || fieldName.isEmpty()) return null;
    return receiver + "." + fieldName;
  }

  /**
   * Per-function map: {@code (receiver, field)} → its taint state. Cleared at every function
   * boundary.
   *
   * <p>The key encodes the receiver, so different receivers writing the same field name (e.g.
   * {@code this.data} and {@code h.data} in the same function) get distinct taint state.
   */
  private final Map<String, TrackedTaint> thisFields = new HashMap<>();

  /**
   * Record a write to {@code this.<fieldName>} with the given taint state. Convenience overload for
   * the {@code this} case; delegates to {@link #recordWrite(String, String, String, List)} with
   * {@code receiver = "this"}.
   */
  public void recordWrite(String fieldName, String label, List<FlowStep> path) {
    recordWrite("this", fieldName, label, path);
  }

  /**
   * Record a write to {@code <receiver>.<fieldName>} with the given taint state. The write
   * OVERWRITES any prior taint for the same {@code (receiver, field)} pair (last-write-wins, which
   * is sound for a path-insensitive intra-function analysis).
   *
   * <p>{@code label} is the conventional taint label (e.g. {@code "param:data"} or {@code
   * "callee-ret:Helper#m"}). The caller is responsible for building the label; the tracker just
   * stores it.
   *
   * <p>{@code path} is the source-to-here taint path. A defensive copy is taken so
   * post-construction mutation of the caller's path is impossible.
   *
   * <p>No-op when either component is null / empty or {@code label} is null (the tracker only
   * stores meaningful state).
   */
  public void recordWrite(String receiver, String fieldName, String label, List<FlowStep> path) {
    recordWrite(receiver, fieldName, label, path, -1);
  }

  /**
   * R-804 follow-up ? overload that records the v2 {@code Trace} handle of the source this field
   * write propagated from. The handle is later recovered by {@link #readField} consumers (notably
   * {@code TaintedCheckerBase}'s {@code restoreFieldTaintOnLoad} site) so the load can attach a
   * real parent to the v2 PROPAGATE node instead of a fresh orphan SOURCE. Pass {@code -1} if the
   * source is unknown (legacy call sites that haven't been dual-written yet).
   */
  public void recordWrite(
      String receiver, String fieldName, String label, List<FlowStep> path, int v2Handle) {
    String k = keyOf(receiver, fieldName);
    if (k == null) return;
    if (label == null) return;
    thisFields.put(
        k, new TrackedTaint(label, path == null ? List.of() : new ArrayList<>(path), v2Handle));
  }

  /**
   * Read {@code this.<fieldName>}'s taint state. Convenience overload for the {@code this} case.
   */
  public TrackedTaint readField(String fieldName) {
    return readField("this", fieldName);
  }

  /**
   * Read {@code <receiver>.<fieldName>}'s taint state. Returns {@code null} when the {@code
   * (receiver, field)} pair has no recorded taint (i.e. the write side never saw a tainted value
   * for this pair, or the field was never written in this function).
   */
  public TrackedTaint readField(String receiver, String fieldName) {
    String k = keyOf(receiver, fieldName);
    if (k == null) return null;
    return thisFields.get(k);
  }

  /**
   * Walk the tracker's keys to find <i>any</i> tainted field whose receiver matches the given name.
   * Used by the dest-taint pass in {@code propagateTaintFromCalleeSummary} to detect the "getter
   * method" pattern: the caller writes {@code h.data = u}, then calls {@code h.getData()}. The
   * receiver {@code h} is not directly tainted, but {@code h.data} is. Without this lookup, the
   * dest of {@code getData()} would not inherit the taint and the cross-function getter flow would
   * be missed.
   *
   * <p>Returns the first tainted entry (no field-name preference) or {@code null} if no field of
   * the receiver is tainted.
   */
  public TrackedTaint readAnyTaintedFieldFor(String receiver) {
    String prefix = receiver + ".";
    for (Map.Entry<String, TrackedTaint> e : thisFields.entrySet()) {
      if (e.getKey().startsWith(prefix) && e.getValue() != null) {
        return e.getValue();
      }
    }
    return null;
  }

  /**
   * Whether a write has been recorded for {@code this.<fieldName>}. Convenience overload for the
   * {@code this} case.
   */
  public boolean hasField(String fieldName) {
    return hasField("this", fieldName);
  }

  /**
   * Whether a write has been recorded for {@code <receiver>.<fieldName>} (regardless of whether the
   * written value is tainted ? see {@link #readField(String, String)} for the taint query).
   */
  public boolean hasField(String receiver, String fieldName) {
    String k = keyOf(receiver, fieldName);
    if (k == null) return false;
    return thisFields.containsKey(k);
  }

  /**
   * Clear all field taint state. Called at every function boundary by {@link
   * TaintedCheckerBase#initFunction}.
   */
  public void clear() {
    thisFields.clear();
  }

  /** Clear taint for a single field on {@code this}. Convenience overload. */
  public void clearField(String fieldName) {
    clearField("this", fieldName);
  }

  /**
   * Clear taint for a single {@code (receiver, field)} pair (e.g. after a clean write to {@code
   * <receiver>.<field>} overwrites a previous tainted write). The pair is removed from the tracked
   * set so subsequent reads see "no tracked taint" (which is the same as "clean"). No-op when the
   * pair is not tracked.
   */
  public void clearField(String receiver, String fieldName) {
    String k = keyOf(receiver, fieldName);
    if (k == null) return;
    thisFields.remove(k);
  }

  /**
   * @return the set of {@code (receiver, field)} keys currently tracked. Exposed for tests and for
   *     the future "all fields at function exit" reporting.
   */
  public java.util.Set<String> trackedFields() {
    return Collections.unmodifiableSet(thisFields.keySet());
  }

  /**
   * Apply a single {@link FieldOp} from the callee's summary to the caller's tracker.
   *
   * <p>The semantics: the callee wrote to {@code this.<fieldOp.fieldName()>} from its param at
   * {@code fieldOp.sourceParamIndex()}. The caller's matching arg is at {@code callArgIndex} in the
   * CALL's args list. If that arg is currently tainted, the caller's tracker records that {@code
   * <receiver>.<fieldName>} is now tainted with the same taint state (label + path), plus a
   * "summary-fold" step noting the cross-function origin.
   *
   * <p>The {@code receiverName} parameter identifies the actual receiver in the caller (e.g. {@code
   * "h"} for {@code h.setData(...)}). The {@code "this"} case is preserved (the caller passes
   * {@code "this"} when the receiver is the implicit self). Any non-empty receiver name is accepted
   * and used as part of the tracker key.
   *
   * <p>If the caller's matching arg is NOT tainted, the caller's tracker records the field as
   * having a non-tainted write (i.e. we still record the write, but with a "clean" taint state).
   * This matters for the case where a later unconditional write in the caller would otherwise be
   * missed (e.g. {@code this.data = "literal";} after a helper that wrote clean data).
   *
   * <p>The {@code fieldOp.sourceParamIndex() == -1} case means "source is a local, not a param" —
   * we still record the write, but treat the source as non-tainted (no caller arg to map from).
   *
   * @param fieldOp the FieldOp from the callee's summary
   * @param callArgIndex the 0-based index in the CALL's args list for the matching caller arg. For
   *     instance methods this is {@code sourceParamIndex + 1} (to skip the receiver at args[0]);
   *     for static methods this is {@code sourceParamIndex} directly.
   * @param calleeSig the callee's signature (for the "summary-fold" step)
   * @param callerArgs the full args list of the CALL instruction
   * @param callerTaintedVars the caller's current taint map (var → label)
   * @param callerTaintPaths the caller's current taint path map (var → path)
   * @param callLine the CALL instruction's line
   * @param callColumn the CALL instruction's column
   * @param receiverName the receiver's name (e.g. {@code "h"} for {@code h.setData(...)}; {@code
   *     "this"} for the implicit self; must be non-null and non-empty)
   */
  public void applyCalleeFieldOp(
      FieldOp fieldOp,
      int callArgIndex,
      String calleeSig,
      List<?> callerArgs,
      Map<TacOperand.TacVar, String> callerTaintedVars,
      Map<TacOperand.TacVar, List<FlowStep>> callerTaintPaths,
      int callLine,
      int callColumn,
      String receiverName,
      TacType receiverType) {
    applyCalleeFieldOp(
        fieldOp,
        callArgIndex,
        calleeSig,
        callerArgs,
        callerTaintedVars,
        callerTaintPaths,
        java.util.Collections.emptyMap(), // legacy: no v2 handles
        callLine,
        callColumn,
        receiverName,
        receiverType);
  }

  /**
   * R-804 follow-up ? overload that threads the caller's v2 {@code Trace} handle map through the
   * fold path so the field write can record a real source handle (subsequent {@code FIELDLOAD} will
   * attach a real parent to its PROPAGATE node instead of an orphan SOURCE). Pass {@code
   * java.util.Collections.emptyMap()} for legacy call sites.
   */
  public void applyCalleeFieldOp(
      FieldOp fieldOp,
      int callArgIndex,
      String calleeSig,
      List<?> callerArgs,
      Map<TacOperand.TacVar, String> callerTaintedVars,
      Map<TacOperand.TacVar, List<FlowStep>> callerTaintPaths,
      Map<TacOperand.TacVar, Integer> callerV2Handles,
      int callLine,
      int callColumn,
      String receiverName,
      TacType receiverType) {
    if (fieldOp == null) return;
    String fieldName = fieldOp.fieldName();
    if (fieldName == null || fieldName.isEmpty()) return;
    // Accept any non-empty receiver
    // name. A null or empty receiver
    // name still drops the write
    // (e.g. static call sites that
    // somehow have FieldOps).
    if (receiverName == null || receiverName.isEmpty()) return;
    // For sourceParamIndex == -1
    // (NON_PARAM_SOURCE), the source is
    // a local; we have no caller arg to
    // map to. Conservative: do NOT
    // record (subsequent FIELDLOAD sees
    // "no tracked taint" = clean). Also
    // clear any prior taint for this
    // (receiver, field) pair (clean
    // write overwrites).
    int sourceParamIndex = fieldOp.sourceParamIndex();
    if (sourceParamIndex < 0) {
      thisFields.remove(receiverName + "." + fieldName);
      // Also clear the cross-fn
      // class-field entry for the
      // declared-class-type form, so
      // a sibling getter on a different
      // instance of the same class
      // sees the clean value (not
      // stale taint).
      if (receiverType != null) {
        String recvTypeName =
            org.plumb.dfa.taint.TaintedCheckerBase.classNameOfTypeStatic(receiverType);
        if (recvTypeName != null) {
          org.plumb.dfa.taint.TaintedCheckerBase.removeCrossFnClassFieldTaint(
              recvTypeName, fieldName);
        }
      }
      return;
    }
    int argIdx = callArgIndex;
    if (argIdx < 0 || argIdx >= callerArgs.size()) return;
    Object arg = callerArgs.get(argIdx);
    // If the matching caller arg is a
    // literal (Const) or any non-TacVar
    // operand, the value is provably
    // CLEAN (a literal can't be
    // tainted), so the helper writes a
    // clean value to the field. Treat
    // as a clean write: clear prior
    // taint, do not record new taint.
    // The "clean write clears" rule
    // mirrors the standalone FIELDSTORE
    // clearField path for symmetry.
    // Also clear the cross-fn
    // declared-class entry so a sibling
    // getter doesn't see stale taint.
    if (!(arg instanceof TacOperand.TacVar av)) {
      thisFields.remove(receiverName + "." + fieldName);
      if (receiverType != null) {
        String recvTypeName =
            org.plumb.dfa.taint.TaintedCheckerBase.classNameOfTypeStatic(receiverType);
        if (recvTypeName != null) {
          org.plumb.dfa.taint.TaintedCheckerBase.removeCrossFnClassFieldTaint(
              recvTypeName, fieldName);
        }
      }
      return;
    }
    // If the matching caller arg is
    // a TacVar but not currently tainted,
    // the helper writes a clean value to
    // the field. Treat as a clean write
    // (clear prior taint, do not record
    // new taint). Also clear the cross-fn
    // declared-class entry.
    if (!callerTaintedVars.containsKey(av)) {
      thisFields.remove(receiverName + "." + fieldName);
      if (receiverType != null) {
        String recvTypeName =
            org.plumb.dfa.taint.TaintedCheckerBase.classNameOfTypeStatic(receiverType);
        if (recvTypeName != null) {
          org.plumb.dfa.taint.TaintedCheckerBase.removeCrossFnClassFieldTaint(
              recvTypeName, fieldName);
        }
      }
      return;
    }
    // Tainted caller arg. Build the
    // path: copy the source path, then
    // append a "summary-fold" step
    // recording the cross-function
    // write.
    String srcLabel = callerTaintedVars.get(av);
    List<FlowStep> srcPath = callerTaintPaths.get(av);
    List<FlowStep> newPath = new ArrayList<>();
    if (srcPath != null) newPath.addAll(srcPath);
    newPath.add(
        new FlowStep(
            "summary-fold",
            "",
            callLine,
            callColumn,
            calleeSig,
            "call " + calleeSig + " writes " + receiverName + "." + fieldName));
    // R-804 follow-up: thread the caller's v2
    // handle so a subsequent FIELDLOAD can
    // attach a real parent. Look up by
    // caller arg var; -1 if absent.
    int srcV2Handle = callerV2Handles == null ? -1 : callerV2Handles.getOrDefault(av, -1);
    thisFields.put(
        receiverName + "." + fieldName, new TrackedTaint(srcLabel, newPath, srcV2Handle));
    // Also seed the cross-function
    // class-field tracker when the
    // caller's taint is a real source
    // (NOT param-seeded). The standalone
    // FIELDSTORE write path already gates
    // on this; the cross-fn fold path
    // needs the same gate so a downstream
    // getter on a sibling instance sees
    // the taint. Without this seed, the
    // fold only writes to the
    // per-function tracker and the global
    // tracker stays empty for
    // cross-instance getter patterns.
    if (srcLabel != null && !srcLabel.startsWith("param:")) {
      org.plumb.dfa.taint.TaintedCheckerBase.seedCrossFnClassFieldTaintFromFold(
          receiverName, receiverType, fieldName, srcLabel, newPath, calleeSig, srcV2Handle);
    }
  }
}
