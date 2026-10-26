package org.plumb.build.maven;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A statically read {@code pom.xml}.
 *
 * <p>Only the facts needed to reconstruct a compile are extracted: the module
 * list, the dependency coordinates, the language level and the encoding. The
 * {@code <parent>} chain is followed so that a {@code ${...}} property or a
 * managed dependency version declared in the parent resolves correctly.
 *
 * <p>The pom's OWN {@code <dependencies>} are what a module compiles against;
 * the parent's {@code <dependencyManagement>} only supplies versions for
 * dependencies that are declared. Both are needed, and confusing the two is
 * how a module ends up with an empty classpath.
 */
final class MavenPom {

  private static final Logger LOG = LoggerFactory.getLogger(MavenPom.class);

  /** Bound on the {@code <parent>} chain, to survive a self-referential pom. */
  private static final int MAX_PARENT_DEPTH = 32;

  private final Model model;
  private final Properties properties;
  private final List<String> moduleNames;
  private final Map<String, Dependency> managed;

  private MavenPom(
      Model model, Properties properties, List<String> moduleNames, Map<String, Dependency> managed) {
    this.model = model;
    this.properties = properties;
    this.moduleNames = List.copyOf(moduleNames);
    this.managed = managed;
  }

  /** Read {@code pomFile} and follow its parent chain. */
  static MavenPom read(File pomFile) {
    Model raw = readModel(pomFile);
    if (raw == null) {
      return new MavenPom(new Model(), new Properties(), List.of(), Map.of());
    }
    Properties props = new Properties();
    List<String> modules = raw.getModules() == null ? List.of() : new ArrayList<>(raw.getModules());

    // The managed versions are collected along the parent chain, nearest
    // parent first, so a child may override what its parent manages.
    Map<String, Dependency> managed = new LinkedHashMap<>();
    Model current = raw;
    int depth = 0;
    while (current.getParent() != null && depth++ < MAX_PARENT_DEPTH) {
      Parent parent = current.getParent();
      File parentFile = locateParent(pomFile, parent);
      if (parentFile == null) {
        LOG.debug("Maven: parent pom {} not found next to {}", parent.getArtifactId(), pomFile);
        break;
      }
      Model parentModel = readModel(parentFile);
      if (parentModel == null) {
        break;
      }
      mergeInto(props, parentModel.getProperties());
      collectManaged(managed, parentModel.getDependencyManagement());
      current = parentModel;
    }
    mergeInto(props, raw.getProperties());
    collectManaged(managed, raw.getDependencyManagement());

    // The module's OWN model is what describes it. The parent supplies
    // properties and managed versions, not the module's dependencies.
    return new MavenPom(raw, props, modules, managed);
  }

  /** Add the managed dependencies of one pom, keeping the first definition. */
  private static void collectManaged(Map<String, Dependency> target, DependencyManagement dm) {
    if (dm == null || dm.getDependencies() == null) {
      return;
    }
    for (Dependency d : dm.getDependencies()) {
      String key = key(d);
      if (key != null) {
        target.putIfAbsent(key, d);
      }
    }
  }

  /** The {@code groupId:artifactId} key of a dependency, or {@code null}. */
  private static String key(Dependency d) {
    String gid = d.getGroupId();
    String aid = d.getArtifactId();
    if (gid == null || aid == null) {
      return null;
    }
    return gid + ":" + aid;
  }

  /** Locate a parent pom relative to the child pom's directory. */
  private static File locateParent(File childPom, Parent parent) {
    String rel = parent.getRelativePath();
    if (rel != null && !rel.isBlank()) {
      File candidate = new File(childPom.getParentFile(), rel);
      if (candidate.isDirectory()) {
        candidate = new File(candidate, "pom.xml");
      }
      if (candidate.isFile()) {
        return candidate;
      }
    }
    // Fall back to the standard local-repository layout.
    String groupPath = parent.getGroupId() == null ? "" : parent.getGroupId().replace('.', File.separatorChar);
    File repo =
        new File(
            MavenSettings.defaultLocalRepository().toFile(),
            groupPath
                + File.separator
                + parent.getArtifactId()
                + File.separator
                + parent.getVersion()
                + File.separator
                + parent.getArtifactId()
                + "-"
                + parent.getVersion()
                + ".pom");
    return repo.isFile() ? repo : null;
  }

  private static void mergeInto(Properties target, Properties source) {
    if (source != null) {
      target.putAll(source);
    }
  }

  private static Model readModel(File pomFile) {
    try (Reader reader = new FileReader(pomFile)) {
      return new MavenXpp3Reader().read(reader);
    } catch (Exception e) {
      LOG.warn("Maven: could not read {}: {}", pomFile, e.getMessage());
      return null;
    }
  }

  // ------------------------------------------------------------------
  // accessors
  // ------------------------------------------------------------------

  String artifactId() {
    String id = model.getArtifactId();
    return id == null || id.isBlank() ? "module" : id;
  }

  List<String> moduleNames() {
    return moduleNames;
  }

  /**
   * The declared dependency coordinates, with {@code ${...}} placeholders
   * resolved against the effective properties.
   *
   * <p>A dependency without a version inherits one from the merged
   * {@code <dependencyManagement>}; without that, it cannot be resolved and
   * is skipped.
   */
  List<String> dependencyCoordinates() {
    List<String> out = new ArrayList<>();
    if (model.getDependencies() == null) {
      return out;
    }
    for (Dependency dep : model.getDependencies()) {
      String groupId = resolve(dep.getGroupId());
      String artifactId = resolve(dep.getArtifactId());
      String version = resolve(dep.getVersion());

      if (version == null) {
        // No version of its own: take the managed one.
        Dependency managedDep = managed.get(groupId + ":" + artifactId);
        if (managedDep != null) {
          version = resolve(managedDep.getVersion());
        }
      }
      if (groupId == null || artifactId == null || version == null) {
        LOG.debug("Maven: skipping dependency with unresolved coordinates: {}:{}:{}",
            groupId, artifactId, version);
        continue;
      }
      String scope = dep.getScope();
      if ("test".equals(scope) || "provided".equals(scope) || "system".equals(scope)) {
        // Not part of the compile classpath.
        continue;
      }
      out.add(groupId + ":" + artifactId + ":" + version);
    }
    return out;
  }

  /** The declared Java language level, or {@code null} when unspecified. */
  String languageLevel() {
    String source = properties.getProperty("maven.compiler.source");
    if (source == null) {
      source = properties.getProperty("maven.compiler.release");
    }
    return source == null ? null : resolve(source);
  }

  /** The declared source encoding, or {@code null} when unspecified. */
  Charset encoding() {
    String enc = properties.getProperty("project.build.sourceEncoding");
    if (enc == null) {
      return null;
    }
    try {
      return Charset.forName(resolve(enc));
    } catch (Exception e) {
      return StandardCharsets.UTF_8;
    }
  }

  /** Substitute {@code ${...}} placeholders from the effective properties. */
  private String resolve(String value) {
    if (value == null) {
      return null;
    }
    String result = value;
    // Bound the substitution loop so a self-referential property cannot hang.
    for (int i = 0; i < 10 && result.contains("${"); i++) {
      StringBuilder sb = new StringBuilder();
      int idx = 0;
      while (true) {
        int start = result.indexOf("${", idx);
        if (start < 0) {
          sb.append(result, idx, result.length());
          break;
        }
        int end = result.indexOf('}', start);
        if (end < 0) {
          sb.append(result, idx, result.length());
          break;
        }
        sb.append(result, idx, start);
        String key = result.substring(start + 2, end);
        String replacement = properties.getProperty(key);
        sb.append(replacement != null ? replacement : result.substring(start, end + 1));
        idx = end + 1;
      }
      String next = sb.toString();
      if (next.equals(result)) {
        break;
      }
      result = next;
    }
    return result;
  }

  /** The set of source roots declared for this module. */
  Set<String> sourceRoots(File moduleRoot) {
    Set<String> roots = new LinkedHashSet<>();
    var build = model.getBuild();
    String dir = build == null ? null : build.getSourceDirectory();
    roots.add(dir == null || dir.isBlank() ? "src/main/java" : dir);
    return roots;
  }
}
