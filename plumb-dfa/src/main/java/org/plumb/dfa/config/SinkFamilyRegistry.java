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
    return prev;
  }

  /** Programmatically remove a family. Returns the removed family (if any). */
  public synchronized SinkFamily deregister(String name) {
    SinkFamily prev = byName.remove(name);
    // Same as register: cache
    // invalidation.
    lookupAllCache.clear();
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
