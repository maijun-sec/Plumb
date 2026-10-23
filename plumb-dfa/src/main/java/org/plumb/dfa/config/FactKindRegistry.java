package org.plumb.dfa.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link FactKind}s, indexed by kind name. Thread-safe; supports both YAML-loaded
 * defaults and runtime registration / deregistration.
 */
public final class FactKindRegistry {

  private final Map<String, FactKind> byName;

  public FactKindRegistry(Map<String, FactKind> byName) {
    this.byName = new LinkedHashMap<>(byName);
  }

  public static FactKindRegistry empty() {
    return new FactKindRegistry(Map.of());
  }

  public Optional<FactKind> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public synchronized FactKind register(FactKind k) {
    Objects.requireNonNull(k, "k");
    return byName.put(k.name(), k);
  }

  public synchronized FactKind deregister(String name) {
    return byName.remove(name);
  }

  public java.util.Collection<FactKind> all() {
    return Collections.unmodifiableCollection(byName.values());
  }
}
