package org.plumb.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Registry of {@link BuildAdapter}s discovered via {@link ServiceLoader} (Java's standard {@code
 * META-INF/services} mechanism).
 *
 * <p>Built-in adapters (Maven / Gradle / Bazel / Ant) are listed explicitly in the {@code
 * META-INF/services/org.plumb.build.BuildAdapter} file under {@code
 * plumb-build/src/main/resources/} so they are found by {@code
 * ServiceLoader.load(BuildAdapter.class)} at runtime. Third-party adapters can be added by dropping
 * a jar on the classpath.
 *
 * <p>The registry is a singleton; call {@link #get()} from the CLI / test code. It is safe to share
 * across threads.
 */
public final class BuildSystemRegistry {

  private static final BuildSystemRegistry INSTANCE = new BuildSystemRegistry();

  private final Map<String, BuildAdapter> byName;

  private BuildSystemRegistry() {
    Map<String, BuildAdapter> map = new LinkedHashMap<>();
    // ServiceLoader discovers all META-INF/services entries.
    for (BuildAdapter a : ServiceLoader.load(BuildAdapter.class)) {
      map.put(a.systemName(), a);
    }
    this.byName = Map.copyOf(map);
  }

  public static BuildSystemRegistry get() {
    return INSTANCE;
  }

  /** All registered adapters, in insertion order. */
  public List<BuildAdapter> adapters() {
    return new ArrayList<>(byName.values());
  }

  /** Look up by short name (e.g. {@code "maven"}). {@code null} if absent. */
  public BuildAdapter byName(String name) {
    return byName.get(Objects.requireNonNull(name));
  }

  /**
   * Auto-detect: returns the FIRST adapter whose {@link BuildAdapter#probe(Path)} returns {@code
   * true}. Order is the ServiceLoader iteration order, so adapters register themselves with the
   * right priority via the {@code META-INF/services} file.
   */
  public BuildAdapter detect(Path projectRoot) {
    for (BuildAdapter a : byName.values()) {
      if (a.probe(projectRoot)) {
        return a;
      }
    }
    return null;
  }
}
