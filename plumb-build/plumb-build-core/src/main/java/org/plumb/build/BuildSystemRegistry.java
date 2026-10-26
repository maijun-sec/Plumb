package org.plumb.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

  /**
   * The adapters consulted by {@link #detect(Path)}, in priority order.
   *
   * <p>Two adapters can claim the same project: {@code maven} and
   * {@code maven-resolver} both probe for a {@code pom.xml}. The resolver is
   * more accurate -- it computes the real transitive dependency tree -- so it
   * is consulted first. The plain {@code maven} adapter stays as the fallback
   * because it is dependency-light and cannot fail on a broken repository.
   */
  private static final List<String> DETECTION_ORDER =
      List.of("maven-resolver", "maven", "gradle", "bazel", "ant");

  private final Map<String, BuildAdapter> byName;

  private BuildSystemRegistry() {
    Map<String, BuildAdapter> map = new LinkedHashMap<>();
    // ServiceLoader discovers all META-INF/services entries.
    for (BuildAdapter a : ServiceLoader.load(BuildAdapter.class)) {
      map.put(a.systemName(), a);
    }
    // LinkedHashMap keeps the discovery order; Map.copyOf would not.
    this.byName = Collections.unmodifiableMap(map);
  }

  public static BuildSystemRegistry get() {
    return INSTANCE;
  }

  /** All registered adapters, in discovery order. */
  public List<BuildAdapter> adapters() {
    return new ArrayList<>(byName.values());
  }

  /** Look up by short name (e.g. {@code "maven"}). {@code null} if absent. */
  public BuildAdapter byName(String name) {
    return byName.get(Objects.requireNonNull(name));
  }

  /**
   * Auto-detect: returns the first adapter whose {@link BuildAdapter#probe(Path)} returns {@code
   * true}.
   *
   * <p>Adapters are consulted in {@link #DETECTION_ORDER}. Any adapter that is
   * not named there -- a third-party one, say -- is consulted afterwards, in
   * discovery order.
   */
  public BuildAdapter detect(Path projectRoot) {
    for (String name : DETECTION_ORDER) {
      BuildAdapter adapter = byName.get(name);
      if (adapter != null && adapter.probe(projectRoot)) {
        return adapter;
      }
    }
    for (BuildAdapter adapter : byName.values()) {
      if (DETECTION_ORDER.contains(adapter.systemName())) {
        continue;
      }
      if (adapter.probe(projectRoot)) {
        return adapter;
      }
    }
    return null;
  }
}
