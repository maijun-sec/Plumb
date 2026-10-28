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
package org.plumb.dfa.points;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Per-function points-to analysis.
 *
 * <p>Tracks which heap allocations each variable can refer to. The {@link AllocSite} key is the
 * (function-signature, instruction-index) pair -- a unique identifier for each {@code NEW} in the
 * program.
 *
 * <h2>Why this matters</h2>
 *
 * <p>Flow-insensitive taint analysis tracks taint per variable. When the variable is an alias of
 * another variable, the taint should propagate through the alias. Example:
 *
 * <pre>
 *   InputStream a = new FileInputStream("x");
 *   InputStream b = a;
 *   b.close();      // closes a
 *   a.read();       // use-after-free — a
 *                   // was already closed
 * </pre>
 *
 * <p>Without points-to analysis the engine would see {@code a} as still "live" and miss the
 * use-after-free. With points-to analysis, the close of {@code b} propagates to {@code a} (they
 * alias the same alloc), and the subsequent {@code a.read()} fires CWE-416.
 *
 * <h2>Algorithm</h2>
 *
 * <ol>
 *   <li>Walk all instructions in topological order (the IR is mostly straight-line within a
 *       function; the conservative join across branches is the union).
 *   <li>For each {@code NEW} / {@code NEWARRAY} / {@code ctor} call: create a fresh {@link
 *       AllocSite}, add to the dest var's points-to set.
 *   <li>For each {@code MOVE} x = y: copy y's points-to set into x (or take the union if x is
 *       already aliased).
 *   <li>For each {@code FIELDSTORE} / {@code ARRAYSTORE}: propagate the value's points-to into the
 *       field/array. The field's abstract location is keyed by (receiver-alloc, fieldName).
 *   <li>For each {@code FIELDLOAD} / {@code ARRAYLOAD}: union the field's / array's points-to into
 *       the dest.
 *   <li>For each {@code CALL} x = f(args): the dest's points-to is conservatively Object (we don't
 *       know the return type without a summary).
 * </ol>
 *
 * <p>The result is a per-function {@code Map<TacVar, Set<AllocSite>>}. Two vars {@code a} and
 * {@code b} <em>may alias</em> if their points-to sets intersect.
 */
public final class PointsToAnalysis {

  /**
   * Symbolic identifier for a heap allocation. The {@code instructionIndex} is the index in the
   * function's {@code instructions} list. The {@code type} is the simple class name (for debugging
   * / messages).
   */
  public record AllocSite(String function, int instructionIndex, String type) {
    public AllocSite {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(type, "type");
    }
  }

  private final Map<TacOperand.TacVar, Set<AllocSite>> pointsTo = new LinkedHashMap<>();

  /**
   * Field-level points-to: receiver alloc + field name → points-to set. Conservative: we union
   * across all allocs of the receiver's type (since we don't track exact receiver type per call
   * site).
   */
  private final Map<FieldKey, Set<AllocSite>> fieldPointsTo = new LinkedHashMap<>();

  /**
   * Alloc sites that have been freed (close/finalize/dispose) since being created. Any subsequent
   * access through any alias is a use-after-free.
   */
  private final Set<AllocSite> freed = new LinkedHashSet<>();

  public record FieldKey(AllocSite receiver, String fieldName) {}

  public Set<AllocSite> pointsTo(TacOperand.TacVar v) {
    return pointsTo.getOrDefault(v, Set.of());
  }

  public boolean isFreed(AllocSite site) {
    return freed.contains(site);
  }

  public boolean mayAlias(TacOperand.TacVar a, TacOperand.TacVar b) {
    Set<AllocSite> sa = pointsTo.get(a);
    Set<AllocSite> sb = pointsTo.get(b);
    if (sa == null || sb == null) return false;
    if (sa.isEmpty() || sb.isEmpty()) return false;
    for (AllocSite s : sa) if (sb.contains(s)) return true;
    return false;
  }

  public boolean isUseAfterFree(TacOperand.TacVar v) {
    Set<AllocSite> s = pointsTo.get(v);
    if (s == null) return false;
    for (AllocSite site : s) {
      if (freed.contains(site)) return true;
    }
    return false;
  }

  /**
   * Compute the points-to analysis for a single function. Call this once per function, then use
   * {@link #pointsTo} / {@link #mayAlias} / {@link #isUseAfterFree} as needed.
   *
   * <p>The function is treated as straight-line (we don't model control-flow branching for
   * points-to precision). Branch joins take the union of the two sides' points-to sets. This is
   * over-approximating but safe for the use-after-free / alias question.
   */
  public void analyse(TacFunction fn) {
    pointsTo.clear();
    fieldPointsTo.clear();
    freed.clear();
    // 1. Seed: every param and local
    // has an empty (Object) points-to
    // set. We don't track the JVM's
    // parameter promotion rules.
    for (TacOperand.TacVar p : fn.params()) {
      pointsTo.computeIfAbsent(p, k -> new LinkedHashSet<>());
    }
    for (TacOperand.TacVar l : fn.locals()) {
      pointsTo.computeIfAbsent(l, k -> new LinkedHashSet<>());
    }
    // 2. Walk instructions in order.
    final int n = fn.instructions().size();
    for (int i = 0; i < n; i++) {
      TacInstruction ins = fn.instructionAt(i);
      if (ins.dest == null || !(ins.dest instanceof TacOperand.TacVar d)) continue;
      switch (ins.kind) {
        case NEW -> {
          AllocSite site =
              new AllocSite(
                  fn.signature(), i, ins.ctorType == null ? "Object" : ins.ctorType.toString());
          pointsTo.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(site);
        }
        case NEWARRAY -> {
          AllocSite site = new AllocSite(fn.signature(), i, "array");
          pointsTo.computeIfAbsent(d, k -> new LinkedHashSet<>()).add(site);
        }
        case MOVE -> {
          if (ins.lhs1 instanceof TacOperand.TacVar src) {
            // x = y → x aliases y.
            // Use union: a variable
            // may be assigned
            // multiple times on
            // different paths.
            Set<AllocSite> srcPts = pointsTo.getOrDefault(src, Set.of());
            pointsTo.computeIfAbsent(d, k -> new LinkedHashSet<>()).addAll(srcPts);
          }
        }
        case FIELDLOAD -> {
          // x = y.f: x aliases
          // whatever y.f points
          // to. The field is
          // identified by the
          // receiver's alloc +
          // field name.
          if (ins.lhs1 instanceof TacOperand.TacVar recv) {
            Set<AllocSite> recvAllocs = pointsTo.getOrDefault(recv, Set.of());
            Set<AllocSite> merged = new LinkedHashSet<>();
            for (AllocSite recvAlloc : recvAllocs) {
              FieldKey fk = new FieldKey(recvAlloc, ins.fieldName);
              if (fieldPointsTo.containsKey(fk)) {
                merged.addAll(fieldPointsTo.get(fk));
              }
            }
            pointsTo.computeIfAbsent(d, k -> new LinkedHashSet<>()).addAll(merged);
          }
        }
        case FIELDSTORE -> {
          // y.f = x: store x's
          // points-to into y's f
          // field.
          if (ins.lhs1 instanceof TacOperand.TacVar recv
              && ins.lhs2 instanceof TacOperand.TacVar src) {
            Set<AllocSite> recvAllocs = pointsTo.getOrDefault(recv, Set.of());
            Set<AllocSite> srcPts = pointsTo.getOrDefault(src, Set.of());
            for (AllocSite recvAlloc : recvAllocs) {
              FieldKey fk = new FieldKey(recvAlloc, ins.fieldName);
              fieldPointsTo.computeIfAbsent(fk, k -> new LinkedHashSet<>()).addAll(srcPts);
            }
          }
        }
        case ARRAYLOAD -> {
          // x = y[i]: x aliases
          // whatever y's
          // contents are. We
          // don't track per-index
          // — all elements
          // share one abstract
          // location.
          if (ins.lhs1 instanceof TacOperand.TacVar recv) {
            Set<AllocSite> recvAllocs = pointsTo.getOrDefault(recv, Set.of());
            Set<AllocSite> merged = new LinkedHashSet<>();
            for (AllocSite recvAlloc : recvAllocs) {
              FieldKey fk = new FieldKey(recvAlloc, "[*]");
              if (fieldPointsTo.containsKey(fk)) {
                merged.addAll(fieldPointsTo.get(fk));
              }
            }
            pointsTo.computeIfAbsent(d, k -> new LinkedHashSet<>()).addAll(merged);
          }
        }
        case ARRAYSTORE -> {
          if (ins.lhs1 instanceof TacOperand.TacVar recv
              && ins.lhs2 instanceof TacOperand.TacVar src) {
            Set<AllocSite> recvAllocs = pointsTo.getOrDefault(recv, Set.of());
            Set<AllocSite> srcPts = pointsTo.getOrDefault(src, Set.of());
            for (AllocSite recvAlloc : recvAllocs) {
              FieldKey fk = new FieldKey(recvAlloc, "[*]");
              fieldPointsTo.computeIfAbsent(fk, k -> new LinkedHashSet<>()).addAll(srcPts);
            }
          }
        }
        case CALL -> {
          // x = f(args): the dest
          // is conservatively
          // Object. We don't
          // know the callee's
          // return type without
          // a summary.
          // (No-op for points-to.)
        }
        default -> {
          /* BINOP, RETURN, etc. — no points-to update */
        }
      }
    }
  }

  /**
   * Mark the alloc sites in {@code v}'s points-to set as freed. After this call, any subsequent
   * access through {@code v} or any alias of it is a use-after-free.
   */
  public void markFreed(TacOperand.TacVar v) {
    Set<AllocSite> pts = pointsTo.get(v);
    if (pts == null) return;
    freed.addAll(pts);
  }

  /** Reset all state (between functions). */
  public void clear() {
    pointsTo.clear();
    fieldPointsTo.clear();
    freed.clear();
  }

  /**
   * Build a points-to summary for the function. The summary records two things:
   *
   * <ul>
   *   <li>The points-to set of each {@code RETURN}-ed value, keyed by return index. The caller uses
   *       this to refine the points-to set of its dest-var when it consumes the return value.
   *   <li>The points-to set of each parameter's fields that the function reads or writes. The
   *       caller uses this to refine field reads on parameters it passed in.
   * </ul>
   *
   * <p>Conservative: every potentially-returned variable's points-to set is recorded.
   * Cross-function propagation over-approximates (the caller gets a superset of the callee's actual
   * points-to set) -- never under-approximates.
   */
  public PointsToSummary buildSummary() {
    PointsToSummary s = new PointsToSummary();
    // For each local that may be
    // returned, record its
    // points-to set. We
    // conservatively union all
    // locals' points-to sets for
    // "may-be-returned" — without a
    // return-flow analysis, we
    // can't tell which locals
    // are actually returned. The
    // caller will further filter
    // by checking which local
    // aliases its dest.
    java.util.Set<String> allAllocs = new java.util.LinkedHashSet<>();
    for (Set<AllocSite> pts : pointsTo.values()) {
      if (pts == null) continue;
      for (AllocSite site : pts) {
        // Encode as
        // "functionSig\0insnIdx\0type"
        // (NUL separator so
        // functionSig containing
        // '#' doesn't trip the
        // parser). NUL is
        // illegal in Java source
        // files so the encoding
        // is unambiguous.
        allAllocs.add(site.function() + "\0" + site.instructionIndex() + "\0" + site.type());
      }
    }
    s.returnAllocSites = allAllocs;
    // For each parameter, record
    // the points-to set of its
    // fields (if any field was
    // loaded into a local). The
    // caller's points-to query
    // can use this to refine
    // field reads.
    s.paramFieldPointsTo = new java.util.LinkedHashMap<>(fieldPointsTo);
    return s;
  }

  /**
   * A function-level points-to summary. Built by {@link PointsToAnalysis#buildSummary} after a
   * per-function analysis. Used by callers to refine their own points-to map when consuming the
   * callee's return value or re-reading a field the callee touched.
   */
  public static final class PointsToSummary {
    /**
     * Alloc sites of values the callee may return. Union of all locals' points-to sets
     * (conservative).
     */
    public java.util.Set<String> returnAllocSites = java.util.Set.of();

    /** Field-level points-to set the callee observed. Keyed by {@code (allocId, fieldName)}. */
    public java.util.Map<FieldKey, Set<AllocSite>> paramFieldPointsTo = java.util.Map.of();

    public Set<AllocSite> returnAsAllocSites() {
      // Returned allocSites are
      // stored as Strings (since
      // Operations are
      // value-typed and we don't
      // want the Operation
      // record to embed a
      // pointer to PointsToAnalysis
      // internals). The encoding
      // is
      // "functionSig\0insnIdx\0type"
      // (NUL separator so a
      // functionSig containing
      // '#' doesn't trip the
      // parser).
      Set<AllocSite> out = new java.util.LinkedHashSet<>();
      for (String enc : returnAllocSites) {
        String[] parts = enc.split("\0", 3);
        if (parts.length == 3) {
          try {
            out.add(new AllocSite(parts[0], Integer.parseInt(parts[1]), parts[2]));
          } catch (NumberFormatException ignored) {
            // malformed — skip
          }
        }
      }
      return out;
    }
  }

  /**
   * Apply a callee's {@link PointsToSummary} to the caller's points-to map. The caller's {@code
   * dest} inherits the callee's return-value alloc sites. After this call, {@code pointsTo(dest)}
   * includes everything in the callee's return set, so use-after-free detection (and similar
   * checks) correctly see the caller-side variable as pointing to the callee's allocations.
   */
  public void applyCalleeSummary(TacOperand.TacVar dest, PointsToSummary summary) {
    if (dest == null || summary == null) return;
    Set<AllocSite> sites = summary.returnAsAllocSites();
    if (sites.isEmpty()) return;
    pointsTo.computeIfAbsent(dest, k -> new LinkedHashSet<>()).addAll(sites);
  }
}
