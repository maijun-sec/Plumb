package org.plumb.build.maven;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
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
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.Xpp3Dom;
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

  /**
   * The artifact id of the plugin that compiles the sources.
   *
   * <p>The language level and the encoding are read from this plugin's configuration, so the
   * lookup is by artifact id: another plugin's {@code <configuration>} must not be mistaken for
   * the compiler's.
   */
  private static final String COMPILER_PLUGIN_ARTIFACT_ID = "maven-compiler-plugin";

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

  /**
   * The declared Java language level, or {@code null} when unspecified.
   *
   * <p><b>Where the answer comes from.</b> There are two ways to say it, and a project may use
   * either:
   *
   * <ol>
   *   <li>the {@code maven.compiler.source} / {@code maven.compiler.release} properties;
   *   <li>the {@code maven-compiler-plugin}'s own {@code <configuration>}.
   * </ol>
   *
   * <p>The plugin's configuration is checked FIRST, because it is the more specific answer: it is
   * the value the compiler plugin actually used. A project that declares {@code
   * maven.compiler.source} of 8 and then pins {@code <release>17</release>} in the plugin is
   * compiled at 17, and reporting 8 would be wrong.
   *
   * <p>Within the plugin's configuration, {@code <release>} beats {@code <source>}: the release
   * pins the language level <em>and</em> the platform API, so it is the level the class files
   * were actually produced for.
   *
   * <p>Nothing is guessed. The upstream project hard-coded {@code "1.8"} with a TODO next to
   * it, so every project was reported as if it compiled at 1.8 -- a confident wrong answer, which
   * is worse than an absent one because the analyser cannot tell it is a guess.
   */
  String languageLevel() {
    String plugin = pluginConfiguration("release");
    if (plugin == null) {
      plugin = pluginConfiguration("source");
    }
    if (plugin != null) {
      return plugin;
    }
    String property = properties.getProperty("maven.compiler.release");
    if (property == null) {
      property = properties.getProperty("maven.compiler.source");
    }
    return property == null ? null : resolve(property);
  }

  /**
   * The declared source encoding, or {@code null} when unspecified.
   *
   * <p>Read the same way as the language level: the compiler plugin's configuration first, then
   * the {@code project.build.sourceEncoding} property. The upstream hard-coded {@code "UTF-8"},
   * so a project compiled in another encoding was reported as UTF-8 -- and a source file read
   * with the wrong encoding is a different program.
   */
  Charset encoding() {
    String plugin = pluginConfiguration("encoding");
    if (plugin == null) {
      String property = properties.getProperty("project.build.sourceEncoding");
      if (property == null) {
        return null;
      }
      plugin = resolve(property);
    }
    if (plugin == null || plugin.isBlank()) {
      return null;
    }
    try {
      return Charset.forName(plugin);
    } catch (Exception e) {
      LOG.debug("Maven: the declared encoding {} is not a known charset", plugin);
      return null;
    }
  }

  /**
   * A value from the {@code maven-compiler-plugin}'s configuration.
   *
   * <p>The plugin is looked up by its artifact id, so a different plugin's configuration cannot be
   * mistaken for the compiler's. The value is resolved against the effective properties, because
   * the usual way to write it is a placeholder: {@code <release>${java.version}</release>}.
   *
   * @param name the configuration element name, e.g. {@code release}
   * @return the resolved value, or {@code null} when the plugin does not declare it
   */
  private String pluginConfiguration(String name) {
    var build = model.getBuild();
    if (build == null || build.getPlugins() == null) {
      return null;
    }
    for (Plugin plugin : build.getPlugins()) {
      if (!COMPILER_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
        continue;
      }
      Object configuration = plugin.getConfiguration();
      if (!(configuration instanceof Xpp3Dom dom)) {
        // The configuration is not DOM-shaped (an unusual pom); there is
        // nothing to read.
        continue;
      }
      Xpp3Dom child = dom.getChild(name);
      if (child == null) {
        continue;
      }
      String value = child.getValue();
      if (value == null || value.isBlank()) {
        continue;
      }
      return resolve(value);
    }
    return null;
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
