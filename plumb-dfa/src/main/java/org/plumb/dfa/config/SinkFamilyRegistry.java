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
package org.plumb.dfa.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Registry of {@link SinkFamily}s, indexed by family name. Thread-safe; supports both YAML-loaded
 * defaults and runtime registration / deregistration.
 *
 * <p>Used by the unified taint walk to classify call sites: when a tainted value flows into a call
 * site whose callee signature matches a family pattern, the finding is tagged with the family's
 * CWE.
 *
 * <p>Pattern: a single taint walk produces one finding per (source, sink) pair; the sink's family
 * is determined at walk time by looking up {@link #lookup(String, String)}.
 */
public final class SinkFamilyRegistry {

  private final Map<String, SinkFamily> byName;

  public SinkFamilyRegistry(Map<String, SinkFamily> byName) {
    this.byName = new LinkedHashMap<>(byName);
  }

  public static SinkFamilyRegistry empty() {
    return new SinkFamilyRegistry(Map.of());
  }

  public Optional<SinkFamily> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(byName.keySet());
  }

  /** Programmatically register or replace a family. Returns the previous family (if any). */
  public synchronized SinkFamily register(SinkFamily family) {
    Objects.requireNonNull(family, "family");
    SinkFamily prev = byName.put(family.name(), family);
    // Invalidate the lookupAll
    // cache because new family might
    // match previously-uncached sigs.
    lookupAllCache.clear();
    sourceMatcherCache.set(null);
    return prev;
  }

  /** Programmatically remove a family. Returns the removed family (if any). */
  public synchronized SinkFamily deregister(String name) {
    SinkFamily prev = byName.remove(name);
    // Same as register: cache
    // invalidation.
    lookupAllCache.clear();
    sourceMatcherCache.set(null);
    return prev;
  }

  /**
   * Look up which family (if any) a callee signature belongs to. Returns the first family whose
   * pattern matches. Sink family patterns are checked in registration order (which is insertion
   * order, preserved by {@link LinkedHashMap}).
   */
  public Optional<SinkFamily> lookup(String calleeSignature) {
    for (SinkFamily f : byName.values()) {
      if (f.matches(calleeSignature)) return Optional.of(f);
    }
    return Optional.empty();
  }

  /**
   * Return every family whose pattern matches the given callee signature, in registration order.
   * Used by checkers whose rule may apply to multiple families (e.g. the PII leak rule watches
   * {@code pii.exfil.sinks}, but a {@code Logger#info} call also matches the earlier-registered
   * {@code sensitive.exfil.sinks} and the operator wants both CWEs reported independently).
   *
   * <p>Callers that only care about the first match (e.g. for the cross-function Sink summary)
   * should keep using {@link #lookup}; callers that emit per-family findings should use {@code
   * lookupAll}.
   */
  /**
   * Cache for {@link #lookupAll}. The same {@code calleeSignature} can be looked up thousands of
   * times per analysis (once per CALL instruction in every function). Caching by signature gives
   * O(1) repeat lookups; the cache is invalidated whenever a new family is registered /
   * deregistered.
   */
  private final java.util.concurrent.ConcurrentHashMap<String, List<SinkFamily>> lookupAllCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The matcher for this catalogue's taint SOURCE families.
   *
   * <p>A source family is one with no CWE, and the matcher groups them by language so a
   * language-restricted family is not blamed for another language's call. The matcher caches its
   * own answers, so it is built once per registry rather than once per lookup.
   *
   * <p>It lives HERE rather than in a static map for two reasons. A static map keyed by the
   * registry's identity would keep every registry the process ever loaded alive, and a long-running
   * server loads a new one per configuration. And a registry can change at runtime -- {@link
   * #register} and {@link #deregister} mutate it -- so a matcher derived from it has to be
   * invalidated exactly where the other cache is.
   *
   * <p>Invalidated by {@link #register} and {@link #deregister}, alongside {@link #lookupAllCache}.
   */
  private final java.util.concurrent.atomic.AtomicReference<org.plumb.dfa.taint.SourceFamilyMatcher>
      sourceMatcherCache = new java.util.concurrent.atomic.AtomicReference<>();

  /**
   * The matcher for this catalogue's taint source families.
   *
   * <p>Built on first use and rebuilt whenever the catalogue changes.
   *
   * @return the matcher
   */
  public org.plumb.dfa.taint.SourceFamilyMatcher sourceFamilyMatcher() {
    org.plumb.dfa.taint.SourceFamilyMatcher cached = sourceMatcherCache.get();
    if (cached != null) return cached;
    org.plumb.dfa.taint.SourceFamilyMatcher created =
        new org.plumb.dfa.taint.SourceFamilyMatcher(this);
    return sourceMatcherCache.compareAndSet(null, created) ? created : sourceMatcherCache.get();
  }

  public List<SinkFamily> lookupAll(String calleeSignature) {
    if (calleeSignature == null) return List.of();
    // Fast path: cache hit.
    List<SinkFamily> cached = lookupAllCache.get(calleeSignature);
    if (cached != null) return cached;
    java.util.ArrayList<SinkFamily> out = new java.util.ArrayList<>();
    for (SinkFamily f : byName.values()) {
      if (f.matches(calleeSignature)) out.add(f);
    }
    // Empty result is the most common
    // case (most CALLs aren't sinks) —
    // share a single empty list to
    // reduce memory pressure.
    List<SinkFamily> result = out.isEmpty() ? List.of() : List.copyOf(out);
    lookupAllCache.put(calleeSignature, result);
    return result;
  }

  /**
   * Return every family that matches the callee signature <b>and</b> applies to the given language.
   *
   * <p>This is the language-aware counterpart of {@link #lookupAll(String)}. A family with a
   * language restriction (say a Kotlin-only {@code kotlin.io.readLine} source) is excluded when the
   * analysed code is not in that language.
   *
   * <p><b>Why this is not simply a filter inside the checker.</b> A checker that watches a family
   * key would still receive the family and have to decide what to do with it. Deciding at lookup
   * time means the "does this apply here" question has exactly one answer, in one place.
   *
   * <p>The cache is keyed by signature and language together, because the answer differs per
   * language.
   */
  public List<SinkFamily> lookupAll(
      String calleeSignature, org.plumb.tac.ir.SourceLanguage language) {
    if (calleeSignature == null) return List.of();
    if (language == null) return lookupAll(calleeSignature);
    String key = calleeSignature + "\0" + language.name();
    List<SinkFamily> cached = lookupAllCache.get(key);
    if (cached != null) return cached;
    java.util.ArrayList<SinkFamily> out = new java.util.ArrayList<>();
    for (SinkFamily f : byName.values()) {
      if (!f.appliesTo(language)) continue;
      if (f.matches(calleeSignature, language)) out.add(f);
    }
    List<SinkFamily> result = out.isEmpty() ? List.of() : List.copyOf(out);
    lookupAllCache.put(key, result);
    return result;
  }

  /**
   * Look up a family by its short name suffix in a finding key. E.g. "SensitiveDataLeak_sql" →
   * lookup family whose name ends with "sql".
   */
  public Optional<SinkFamily> lookupBySuffix(String suffix) {
    for (SinkFamily f : byName.values()) {
      if (f.name().endsWith(suffix)) return Optional.of(f);
    }
    return Optional.empty();
  }

  public List<SinkFamily> all() {
    return List.copyOf(byName.values());
  }
}
