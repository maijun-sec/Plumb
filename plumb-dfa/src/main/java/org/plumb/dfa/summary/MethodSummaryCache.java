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
package org.plumb.dfa.summary;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.plumb.dfa.cg.FunctionSignature;
import org.plumb.dfa.resolve.StaticFieldResolver;
import org.plumb.tac.ir.TacFunction;

/**
 * Per-engine cache of {@link MethodSummary} objects, keyed by {@link FunctionSignature}.
 *
 * <p>The cache is <b>context-insensitive</b>: one summary per signature, shared across all call
 * sites. When the engine needs a context-sensitive view, the {@link #get(FunctionSignature)} method
 * can be wrapped in a per-call-site layer.
 *
 * <p>Cache size is bounded by LRU eviction. A summary is built lazily on first miss; subsequent
 * queries return the same instance. The cache is thread-safe via a single monitor -- summary
 * building is relatively cheap (a single linear pass over the IR), so the lock is unlikely to
 * become a bottleneck.
 *
 * <p>When the engine cannot find a body for a callee (the callee lives in a library, or the call
 * graph failed to resolve it), the cache installs a sentinel {@link MethodSummary} with {@link
 * ReturnShape.Kind#UNKNOWN} and a pure {@link SideEffect}. The call site then sees the return value
 * as TOP and state is preserved. This is the conservative fallback that keeps the engine silent on
 * out-of-project callees while still propagating "any" as a possible return value.
 */
public final class MethodSummaryCache {

  private final StaticFieldResolver staticFields;
  private final int maxEntries;
  private final Map<FunctionSignature, MethodSummary> entries;

  public MethodSummaryCache() {
    this(null, 4096);
  }

  public MethodSummaryCache(StaticFieldResolver staticFields) {
    this(staticFields, 4096);
  }

  public MethodSummaryCache(StaticFieldResolver staticFields, int maxEntries) {
    this.staticFields = staticFields;
    // Honour the
    // caller's request
    // — small caches are
    // valid in tests.
    // Floor at 1 so we
    // never accidentally
    // build an "infinite"
    // cache.
    this.maxEntries = Math.max(1, maxEntries);
    this.entries = new BoundedLru<>(this.maxEntries);
  }

  /**
   * Bounded LRU map. A named subclass (rather than an anonymous inner class) so the {@code
   * removeEldestEntry} override has a clean, isolated reference to the bound capacity field. Tested
   * independently of the rest of the cache.
   */
  static final class BoundedLru<K, V> extends LinkedHashMap<K, V> {
    private final int maxEntries;

    BoundedLru(int maxEntries) {
      super(16, 0.75f, true);
      this.maxEntries = maxEntries;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > maxEntries;
    }
  }

  /**
   * Look up a summary by signature. If the signature is not cached, return a UNKNOWN sentinel
   * without doing any work -- the engine is expected to call {@link #put(FunctionSignature,
   * TacFunction)} after it has resolved the callee body. This split keeps the cache usable for
   * unresolvable callees (library, JDK, unknown) without forcing every cache miss to wait for a
   * body that may never arrive.
   */
  public synchronized MethodSummary get(FunctionSignature sig) {
    MethodSummary s = entries.get(sig);
    if (s != null) return s;
    // Fall back to a
    // return-type-agnostic lookup.
    // The caller-side applier
    // (SummaryApplier) uses a
    // shape-only FunctionSignature
    // with returnType="?" for the
    // Class.method/arity call-sig
    // form, while the engine's
    // parseFnSignature fallback
    // preserves the function's
    // actual return type in the
    // cache key. Try a "?" lookup
    // so the cross-TU fold can
    // find the pre-computed summary
    // regardless of which side
    // produced the key.
    if (sig != null && sig.returnType() != null && !"?".equals(sig.returnType())) {
      FunctionSignature loose =
          new FunctionSignature(
              sig.packageName(), sig.className(), sig.method(), sig.paramTypes(), "?");
      s = entries.get(loose);
      if (s != null) return s;
    }
    return unknownSentinel(sig);
  }

  /**
   * Look up a summary by canonical signature string (the form produced by {@link
   * FunctionSignature#toString()}). Equivalent to {@code get(FunctionSignature.parse(s))}.
   */
  public synchronized MethodSummary getByCanonicalString(String sig) {
    if (sig == null) {
      return null;
    }
    try {
      return get(FunctionSignature.parse(sig));
    } catch (RuntimeException re) {
      return null;
    }
  }

  /**
   * Build (or rebuild) the summary for a signature. The returned summary is stored in the cache and
   * returned.
   */
  public synchronized MethodSummary put(FunctionSignature sig, TacFunction fn) {
    MethodSummary s = new MethodSummaryBuilder(staticFields).build(fn);
    entries.put(sig, s);
    return s;
  }

  /**
   * Install a summary directly, without going through the builder. Useful for pre-seeding the cache
   * with library-stub summaries.
   */
  public synchronized void install(MethodSummary summary) {
    Objects.requireNonNull(summary);
    entries.put(summary.signature, summary);
    // Also store under a
    // return-type-agnostic "?"
    // key so the caller-side
    // applier (which uses "?" for
    // Class.method/arity call-sig
    // forms) can find this
    // summary even though the
    // builder's canonical key
    // preserves the function's
    // actual return type. The
    // "?" key is a strict alias
    // — both keys point to the
    // same MethodSummary instance.
    if (summary.signature != null
        && summary.signature.returnType() != null
        && !"?".equals(summary.signature.returnType())) {
      FunctionSignature loose =
          new FunctionSignature(
              summary.signature.packageName(),
              summary.signature.className(),
              summary.signature.method(),
              summary.signature.paramTypes(),
              "?");
      if (!entries.containsKey(loose)) {
        entries.put(loose, summary);
      }
    }
  }

  /** Number of summaries currently in the cache. */
  public synchronized int size() {
    return entries.size();
  }

  /** Drop all cached summaries. */
  public synchronized void clear() {
    entries.clear();
  }

  private static MethodSummary unknownSentinel(FunctionSignature sig) {
    ReturnShape shape = ReturnShape.unknown("no body resolved for " + sig);
    return new MethodSummary(sig, shape, SideEffect.pure());
  }
}
