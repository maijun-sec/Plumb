package org.plumb.dfa.taint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.reporter.FlowStep;
import org.plumb.tac.ir.TacOperand;

/**
 * Per-function tracker for per-element taint propagation through arrays ({@code a[i] = v}, {@code x
 * = a[i]}).
 *
 * <p>R694 task T1: addresses ARRAY-INSENSITIVITY (where the taint framework previously treated
 * whole arrays as either tainted or clean via a single {@code propagateMOVE} on {@code ARRAYLOAD}).
 * The tracker keeps a per-array {@code Map<IndexKey, TaintedEntry>}, allowing taint to flow through
 * {@code a[0] = tainted; x = a[0]} without contaminating unrelated indices.
 *
 * <h2>Index keys</h2>
 *
 * <p>The {@link IndexKey} distinguishes three flavours:
 *
 * <ul>
 *   <li>{@code CONSTANT n} ? literal integer {@code n}. Stored as-is, so {@code a[0]} and {@code
 *       a[1]} can carry independent taint.
 *   <li>{@code VAR aTacVar} ? symbolic index (e.g. {@code a[i]} where {@code i} is a local). Two
 *       {@code ARRAYLOAD}s with the same symbolic index share taint state. If the index is itself
 *       tainted, the tracker maps the symbolic index to the {@code ANY} marker, so any subsequent
 *       load through any index is treated as tainted.
 *   <li>{@code ANY} — "taint on any index". A {@code ARRAYSTORE} with a tainted index promotes the
 *       array to {@code ANY}: every subsequent {@code ARRAYLOAD} is tainted.
 * </ul>
 *
 * <h2>Clearing</h2>
 *
 * <p>The tracker is per-function; {@link #clear()} is called from {@code
 * TaintedCheckerBase.initFunction} at every function boundary. The tracker does NOT persist across
 * functions. The cross-function flow goes through {@link org.plumb.dfa.summary.FunctionSummary},
 * which records {@link org.plumb.dfa.summary.StringOp} passthroughs and {@link
 * org.plumb.dfa.summary.FieldOp} field writes -- but NOT array accesses: there is no array-op
 * summary, so an array element's taint does not currently cross a function boundary.
 *
 * <h2>Soundness / false-positive control</h2>
 *
 * <p>When {@code a[i] = tainted} and {@code i} is itself tainted, the tracker marks the array
 * {@code ANY}-tainted (one tainted write touches all subsequent reads). This is sound
 * (last-write-wins overapproximation) and matches what whole-array tracking would give, but with
 * per-element precision for the common case where the index is constant or untainted.
 */
public final class ArrayTaintTracker {

  /**
   * A snapshot of an array element's taint state.
   *
   * <p><b>R-804 follow-up</b> — {@code v2Handle} is the v2 {@code Trace} node id of the source this
   * array store propagated from. When a subsequent {@code ARRAYLOAD} reads the array, the loader
   * attaches a real parent to the new PROPAGATE node via {@code addPropagate(parents=[v2Handle],
   * ...)} instead of treating the load as a fresh orphan SOURCE. {@code v2Handle=-1} means no
   * recovered handle (legacy / non-tracked) ? loaders fall back to the orphan-SOURCE shape from
   * R-804.
   */
  public record TaintedEntry(String label, List<FlowStep> path, int v2Handle) {
    public TaintedEntry {
      Objects.requireNonNull(label, "label");
      path = path == null ? List.of() : List.copyOf(path);
    }

    /** Convenience for callers that don't have a v2 handle — defaults to {@code -1} (unknown). */
    public TaintedEntry(String label, List<FlowStep> path) {
      this(label, path, -1);
    }
  }

  /** Discriminator for an array index. */
  public sealed interface IndexKey {
    /** Integer literal index. */
    record Constant(long value) implements IndexKey {}

    /**
     * Symbolic index identified by the TacVar's name. We use the name (not the SSA version) because
     * the array-tracker's lifetime is local to one function; SSA versions don't cross basic-block
     * boundaries for the same source-level variable.
     */
    record Var(String name) implements IndexKey {}

    /** "Tainted index" marker — any read through any index returns tainted. */
    record Any() implements IndexKey {}
  }

  /** Singleton for the {@link IndexKey.Any} case. */
  public static final IndexKey ANY = new IndexKey.Any();

  /** Per-function map: {@code arrayVar} → {indexKey → taint}. */
  private final Map<TacOperand.TacVar, Map<IndexKey, TaintedEntry>> arrays = new HashMap<>();

  /**
   * Resolve the {@link IndexKey} for a {@link TacOperand}. Returns {@link IndexKey#Any} for null /
   * non-TacVar (treats the index as opaque → if the stored value is tainted, every subsequent load
   * is tainted).
   */
  public static IndexKey indexKeyFor(TacOperand op) {
    if (op == null) return ANY;
    if (op instanceof TacOperand.TacVar tv) {
      return new IndexKey.Var(tv.name());
    }
    // Non-TacVar (literal int) — extract the value
    // if possible. The IR builder usually represents
    // integer literals as Const; if we can't extract,
    // fall back to ANY so the taint is conservative.
    if (op instanceof TacOperand.Const c) {
      Object v = c.value();
      if (v instanceof Integer i) return new IndexKey.Constant(i);
      if (v instanceof Long l) return new IndexKey.Constant(l);
    }
    return ANY;
  }

  /**
   * Record a store {@code arr[index] = value} where {@code value} is currently tainted with the
   * given label/path. The write OVERWRITES any prior taint for the same {@code (arr, index)} pair
   * (last-write-wins, which is sound for path-insensitive intra-function analysis).
   *
   * <p>Side effect: if {@code indexOp} is currently tainted in the caller's taint map, the array is
   * promoted to {@link IndexKey#Any} so subsequent loads through any index return tainted.
   *
   * @param arr the array TacVar
   * @param indexOp the index operand
   * @param label taint label of the stored value
   * @param path taint path of the stored value
   * @param indexTainted true if the index itself is currently tainted (causes the ANY promotion)
   */
  public void recordStore(
      TacOperand.TacVar arr,
      TacOperand indexOp,
      String label,
      List<FlowStep> path,
      boolean indexTainted) {
    recordStore(arr, indexOp, label, path, indexTainted, -1);
  }

  /**
   * R-804 follow-up ? overload that records the v2 {@code Trace} handle of the source this array
   * store propagated from. The handle is later recovered by {@link #readLoad} consumers (notably
   * {@code TaintedCheckerBase}'s {@code restoreArrayTaintOnLoad} site) so the load can attach a
   * real parent to the v2 PROPAGATE node instead of a fresh orphan SOURCE. Pass {@code -1} if the
   * source is unknown.
   */
  public void recordStore(
      TacOperand.TacVar arr,
      TacOperand indexOp,
      String label,
      List<FlowStep> path,
      boolean indexTainted,
      int v2Handle) {
    if (arr == null || label == null) return;
    Map<IndexKey, TaintedEntry> idx = arrays.computeIfAbsent(arr, k -> new HashMap<>());
    if (indexTainted || indexOp == null) {
      // ANY promotion: store taint under the ANY
      // marker so subsequent loads (regardless of
      // index) return tainted.
      idx.put(
          ANY, new TaintedEntry(label, path == null ? List.of() : new ArrayList<>(path), v2Handle));
      // Also erase any per-index entries — they
      // can no longer be soundly distinguished.
      idx.remove(new IndexKey.Var(indexOp instanceof TacOperand.TacVar tv ? tv.name() : ""));
      return;
    }
    IndexKey k = indexKeyFor(indexOp);
    idx.put(k, new TaintedEntry(label, path == null ? List.of() : new ArrayList<>(path), v2Handle));
    // If we're writing under ANY, no further work
    // needed (already overwrites). Otherwise, if we
    // previously had an ANY entry, it stays (taint
    // from any index is still seen).
  }

  /**
   * Read {@code arr[index]}'s taint state. Returns {@code null} when no taint state matches either:
   *
   * <ul>
   *   <li>The {@code (arr, index)} pair has no entry AND the array has no {@link IndexKey#Any}
   *       entry.
   * </ul>
   *
   * <p>Lookup order: {@link IndexKey#Any} first (conservative ? any-tainted array → every load is
   * tainted), then exact key.
   */
  public TaintedEntry readLoad(TacOperand.TacVar arr, TacOperand indexOp) {
    if (arr == null) return null;
    Map<IndexKey, TaintedEntry> idx = arrays.get(arr);
    if (idx == null) return null;
    TaintedEntry any = idx.get(ANY);
    if (any != null) return any;
    if (indexOp == null) return null;
    return idx.get(indexKeyFor(indexOp));
  }

  /**
   * Whether any taint has been recorded for the given array. Useful for diagnostics and for the
   * summary-collection code that wants to know if an array element has been written tainted.
   */
  public boolean hasArray(TacOperand.TacVar arr) {
    if (arr == null) return false;
    return arrays.containsKey(arr);
  }

  /** Clear all state. Called at every function boundary. */
  public void clear() {
    arrays.clear();
  }
}
