package org.plumb.dfa.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of {@link CheckerConfig}s. Thread-safe; supports both YAML-loaded defaults and runtime
 * registration / deregistration.
 */
public final class CheckerRegistry {

  private final Map<String, CheckerConfig> byName;

  public CheckerRegistry(Map<String, CheckerConfig> byName) {
    this.byName = new LinkedHashMap<>(byName);
  }

  public static CheckerRegistry empty() {
    return new CheckerRegistry(Map.of());
  }

  public Optional<CheckerConfig> byName(String name) {
    return Optional.ofNullable(byName.get(name));
  }

  public synchronized CheckerConfig register(CheckerConfig c) {
    Objects.requireNonNull(c, "c");
    return byName.put(c.name(), c);
  }

  public synchronized CheckerConfig deregister(String name) {
    return byName.remove(name);
  }

  public java.util.Collection<CheckerConfig> all() {
    return Collections.unmodifiableCollection(byName.values());
  }

  public java.util.List<CheckerConfig> enabled() {
    return byName.values().stream().filter(CheckerConfig::enabled).toList();
  }
}
