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
package org.plumb.dfa.cg;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A cross-TU registry of {@link TacFunction}s.
 *
 * <p>Mirror of Fathom's {@code GlobalTACStore}: a lookup-by-signature index that aggregates every
 * {@code TacFunction} seen across every {@link TacProgram} (translation unit = one source file).
 * The store is the single source of truth that {@link GlobalCallGraphBuilder} and {@link
 * org.plumb.dfa.engine.DfaEngine} query when they need to resolve a callee signature to a real
 * function body.
 *
 * <h2>Identity</h2>
 *
 * <p>Each registered function is identified by its canonical signature string (the same string that
 * {@code TacFunction.signature()} returns and that {@code TacInstruction.calleeSig} carries). Two
 * functions with the same signature are deduplicated (last write wins, with a warning); this is the
 * desired behavior when the same source file is parsed twice, e.g. by a {@code --rerun} workflow.
 *
 * <h2>Thread safety</h2>
 *
 * <p>Registration is synchronized. Lookups are unsynchronized and read from a stable view once
 * {@link #freeze()} has been called. The typical lifecycle is:
 *
 * <ol>
 *   <li>Bulk-register every TU via {@link #addProgram(TacProgram)}.
 *   <li>Call {@link #freeze()} to disable further registration and to publish the stable view.
 *   <li>Hand the store to {@link GlobalCallGraphBuilder} and the DFA engine.
 * </ol>
 *
 * <h2>Path bookkeeping</h2>
 *
 * <p>The store also tracks which TUs are loaded ({@link #allSourceFiles()}). This is useful for the
 * cross-TU cache key: the cache file name includes both the strategy and a hash of the loaded
 * source-file set, so a project change that adds a new .java file invalidates the cached call
 * graph.
 */
public final class GlobalTACStore {

  private static final Logger LOG = LoggerFactory.getLogger(GlobalTACStore.class);

  private final Map<String, TacFunction> bySignature = new LinkedHashMap<>();

  /**
   * secondary index by merged key (enclosingClass :: signature) so that functions with identical
   * signatures across different enclosing classes are all retained. Without this, {@code
   * bySignature} silently drops the duplicates.
   */
  private final Map<String, TacFunction> byMergedKey = new LinkedHashMap<>();

  /**
   * The functions of each enclosing class, in registration order.
   *
   * <p>Resolving a call to a method of the caller's own class only has to look at THAT class's
   * methods. Scanning every function in the store for each call site is O(calls x functions), which
   * on a large corpus is the difference between finishing and not finishing.
   */
  private final Map<String, List<TacFunction>> byEnclosingClass = new LinkedHashMap<>();

  /**
   * The functions declared by one enclosing class, WITHOUT copying them.
   *
   * <p>Returns an empty list for an unknown class, so a caller can iterate it without a null check.
   */
  public java.util.List<TacFunction> functionsOfClass(String enclosingClass) {
    if (enclosingClass == null || enclosingClass.isEmpty()) {
      return java.util.Collections.emptyList();
    }
    java.util.List<TacFunction> out = byEnclosingClass.get(enclosingClass);
    return out == null ? java.util.Collections.emptyList() : out;
  }

  /** TU path -> list of signatures declared in that TU. */
  private final Map<Path, List<String>> signaturesBySource = new LinkedHashMap<>();

  /** Function signature -> source path. Reverses the above. */
  private final Map<String, Path> sourceBySignature = new LinkedHashMap<>();

  private volatile boolean frozen = false;

  /** Construct an empty store. */
  public GlobalTACStore() {}

  /**
   * Register a single function. If a function with the same signature is already registered, the
   * new one replaces the old one (with a WARN log to surface the collision). Returns {@code this}
   * for chaining.
   */
  public synchronized GlobalTACStore register(TacFunction fn) {
    Objects.requireNonNull(fn, "fn");
    if (frozen) {
      throw new IllegalStateException("GlobalTACStore is frozen; cannot register after freeze()");
    }
    String sig = fn.signature();
    if (sig == null || sig.isEmpty()) {
      LOG.warn("Skipping function with empty signature: {}", fn.name());
      return this;
    }
    if (bySignature.containsKey(sig)) {
      LOG.warn("Signature collision; replacing: {}", sig);
    }
    bySignature.put(sig, fn);
    // index by merged key too so cross-file duplicates aren't lost
    String mk = mergedKey(fn);
    if (mk != null && !mk.isEmpty()) {
      byMergedKey.put(mk, fn);
    }
    indexByEnclosingClass(fn);
    return this;
  }

  /**
   * Bulk-register every function in {@code program}. The program's source file is recorded so the
   * store can later report {@link #allSourceFiles()}. Returns {@code this} for chaining.
   */
  public synchronized GlobalTACStore addProgram(TacProgram program) {
    if (frozen) {
      throw new IllegalStateException("GlobalTACStore is frozen; cannot addProgram after freeze()");
    }
    Objects.requireNonNull(program, "program");
    Path sourceFile = program.sourcePath();
    List<String> sigs = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      String sig = fn.signature();
      if (sig == null || sig.isEmpty()) continue;
      bySignature.put(sig, fn);
      // keep a separate index by merged key (enclosingClass :: sig)
      // so cross-file duplicates are not silently dropped.
      String mk = mergedKey(fn);
      if (mk != null && !mk.isEmpty()) {
        byMergedKey.put(mk, fn);
      }
      sourceBySignature.put(sig, sourceFile);
      sigs.add(sig);
      indexByEnclosingClass(fn);
    }
    signaturesBySource.put(sourceFile, sigs);
    return this;
  }

  /**
   * Record a function under its enclosing class, so a call to a method of the caller's own class
   * can be resolved by looking at that class alone.
   */
  private void indexByEnclosingClass(TacFunction fn) {
    String enc = fn.enclosingClass();
    if (enc == null || enc.isEmpty()) return;
    byEnclosingClass.computeIfAbsent(enc, k -> new ArrayList<>()).add(fn);
  }

  /**
   * Disable further registration and publish a stable view. After this call the store behaves as an
   * immutable snapshot -- concurrent lookups are safe and reflect every function registered up to
   * the freeze.
   */
  public synchronized GlobalTACStore freeze() {
    this.frozen = true;
    LOG.info(
        "GlobalTACStore frozen: {} functions across {} source files",
        bySignature.size(),
        signaturesBySource.size());
    return this;
  }

  /** Look up a function by its canonical signature. */
  public Optional<TacFunction> functionBySignature(String sig) {
    if (sig == null) return Optional.empty();
    return Optional.ofNullable(bySignature.get(sig));
  }

  /** Look up the source file a function was loaded from. */
  public Optional<Path> sourceOf(String sig) {
    if (sig == null) return Optional.empty();
    return Optional.ofNullable(sourceBySignature.get(sig));
  }

  /**
   * All registered function signatures, in registration order. returns signatures from the {@link
   * #byMergedKey} index (which is superset of {@link #bySignature} for duplicate sigs) so consumers
   * see every unique merged-key function.
   */
  public List<String> allSignatures() {
    return List.copyOf(byMergedKey.keySet());
  }

  /**
   * All registered functions, in registration order. returns functions from the {@link
   * #byMergedKey} index so cross-file duplicates aren't dropped (bug that should have happened
   * here). Use {@link #functionBySignature(String)} if you specifically need the signature-keyed
   * view.
   */
  public List<TacFunction> allFunctions() {
    return List.copyOf(byMergedKey.values());
  }

  /**
   * The registered functions, WITHOUT copying them.
   *
   * <p>{@link #allFunctions()} is deliberately defensive -- it hands back an immutable copy so a
   * caller cannot mutate the store. That copy is O(n) in the number of functions, and this store
   * holds one function per method of every file in the scan.
   *
   * <p>The call-graph builder calls this once per call site while resolving a callee. On Juliet's
   * CWE-190 (7,047 files, ~50,000 functions) that is ~50,000 copies of a 50,000-element list: the
   * resolution stops being a lookup and becomes the dominant cost of the whole scan.
   *
   * <p>So the hot path uses this view. It is unmodifiable, so the same guarantee holds; the only
   * difference is that it is a VIEW, so a caller must not hold it across a mutation of the store.
   * The store is frozen before the call graph is built.
   */
  public java.util.Collection<TacFunction> allFunctionsView() {
    return java.util.Collections.unmodifiableCollection(byMergedKey.values());
  }

  /**
   * build a MergedProgram-style merged key for a {@link TacFunction}: {@code enclosingClass + " ::
   * " + signature}. Returns empty string if enclosing class is missing.
   */
  private static String mergedKey(TacFunction fn) {
    String enc = fn.enclosingClass();
    if (enc == null || enc.isEmpty()) {
      return "";
    }
    return enc + " :: " + fn.signature();
  }

  /** All source files that have been added via {@link #addProgram}. */
  public Set<Path> allSourceFiles() {
    return Collections.unmodifiableSet(new LinkedHashSet<>(signaturesBySource.keySet()));
  }

  /** Signatures declared in {@code sourceFile}, in declaration order. */
  public List<String> signaturesInSource(Path sourceFile) {
    return List.copyOf(signaturesBySource.getOrDefault(sourceFile, List.of()));
  }

  /** Number of registered functions. */
  public int size() {
    return bySignature.size();
  }

  /** Whether the store has been frozen. */
  public boolean isFrozen() {
    return frozen;
  }

  // ---- Bulk constructor ----

  /**
   * Construct a pre-populated store from a list of programs. Equivalent to {@code new
   * GlobalTACStore().addPrograms(ps).freeze()}.
   */
  public static GlobalTACStore of(Collection<TacProgram> programs) {
    GlobalTACStore s = new GlobalTACStore();
    for (TacProgram p : programs) s.addProgram(p);
    s.freeze();
    return s;
  }
}
