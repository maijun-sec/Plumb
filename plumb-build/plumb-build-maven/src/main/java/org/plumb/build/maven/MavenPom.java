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
import org.apache.maven.model.PluginExecution;
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

  /**
   * The artifact id of the plugin that ADDS source roots.
   *
   * <p>{@code build-helper-maven-plugin} with the {@code add-source} goal puts extra source
   * directories into the compile, and they are part of the module's source roots. A build that
   * uses it and is described without those roots is described wrong: the analyser sees fewer
   * sources than the compiler compiles.
   */
  private static final String BUILD_HELPER_PLUGIN_ARTIFACT_ID = "build-helper-maven-plugin";

  /** The goal of the build-helper plugin that adds a source root. */
  private static final String ADD_SOURCE_GOAL = "add-source";

  private final Model model;
  private final Properties properties;
  private final List<String> moduleNames;
  private final Map<String, Dependency> managed;

  /**
   * The compiler plugin's configuration, from the module and its parents.
   *
   * <p><b>Why the parents are included.</b> A multi-module project almost never configures the
   * compiler plugin in every module: the parent declares it once, and the children inherit it. A
   * lookup that only reads the module's own {@code <build>} therefore reports "no language level,
   * no encoding" for every child of a well-configured parent -- and the analyser then falls back
   * to a guess.
   *
   * <p>The module's own configuration wins, because that is what Maven itself does: the effective
   * POM merges the parent into the child, with the child on top.
   */
  private final List<Xpp3Dom> compilerConfigurations;

  /**
   * The build-helper plugin's {@code add-source} executions, from the module and its parents.
   *
   * <p>Same ordering rule as the compiler configuration: the module's own first.
   */
  private final List<Xpp3Dom> additionalSourceConfigurations;

  private MavenPom(
      Model model,
      Properties properties,
      List<String> moduleNames,
      Map<String, Dependency> managed,
      List<Xpp3Dom> compilerConfigurations,
      List<Xpp3Dom> additionalSourceConfigurations) {
    this.model = model;
    this.properties = properties;
    this.moduleNames = List.copyOf(moduleNames);
    this.managed = managed;
    this.compilerConfigurations = List.copyOf(compilerConfigurations);
    this.additionalSourceConfigurations = List.copyOf(additionalSourceConfigurations);
  }

  /** Read {@code pomFile} and follow its parent chain. */
  static MavenPom read(File pomFile) {
    Model raw = readModel(pomFile);
    if (raw == null) {
      return new MavenPom(
          new Model(), new Properties(), List.of(), Map.of(), List.of(), List.of());
    }
    Properties props = new Properties();
    List<String> modules = raw.getModules() == null ? List.of() : new ArrayList<>(raw.getModules());

    // The managed versions are collected along the parent chain, nearest
    // parent first, so a child may override what its parent manages.
    Map<String, Dependency> managed = new LinkedHashMap<>();

    // The compiler plugin's configuration is collected the same way: the
    // module's own first, then each parent.  The module's own wins, so the
    // list is searched in that order.
    List<Xpp3Dom> compilerConfigurations = new ArrayList<>();
    List<Xpp3Dom> additionalSourceConfigurations = new ArrayList<>();
    addCompilerConfiguration(compilerConfigurations, raw);
    addAdditionalSourceConfigurations(additionalSourceConfigurations, raw);

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
      addCompilerConfiguration(compilerConfigurations, parentModel);
      addAdditionalSourceConfigurations(additionalSourceConfigurations, parentModel);
      current = parentModel;
    }
    mergeInto(props, raw.getProperties());
    collectManaged(managed, raw.getDependencyManagement());

    // The module's OWN model is what describes it. The parent supplies
    // properties and managed versions, not the module's dependencies.
    return new MavenPom(
        raw, props, modules, managed, compilerConfigurations, additionalSourceConfigurations);
  }

  /**
   * The compiler plugin's configuration in one pom, if it declares one.
   *
   * <p>The plugin is looked up by its artifact id, so another plugin's {@code <configuration>} is
   * never mistaken for the compiler's.
   *
   * @param target the list to add to
   * @param model the pom to read
   */
  private static void addCompilerConfiguration(List<Xpp3Dom> target, Model model) {
    var build = model.getBuild();
    if (build == null || build.getPlugins() == null) {
      return;
    }
    for (Plugin plugin : build.getPlugins()) {
      if (!COMPILER_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
        continue;
      }
      if (plugin.getConfiguration() instanceof Xpp3Dom dom) {
        target.add(dom);
      }
    }
  }

  /**
   * The {@code add-source} executions of the build-helper plugin in one pom.
   *
   * <p><b>Why the EXECUTIONS and not the plugin's own configuration.</b> The build-helper plugin
   * is configured per execution: each {@code <execution>} with the {@code add-source} goal
   * carries its own {@code <sources>} list. A lookup that only read the plugin's top-level
   * {@code <configuration>} would find nothing, because that is where a plugin that is
   * configured globally puts its settings -- and the build-helper plugin is not.
   *
   * <p>The goal is checked too, because the same plugin has other goals -- {@code
   * attach-artifact}, {@code reserve-network-port} -- whose configuration is not a source root.
   */
  private static void addAdditionalSourceConfigurations(List<Xpp3Dom> target, Model model) {
    var build = model.getBuild();
    if (build == null || build.getPlugins() == null) {
      return;
    }
    for (Plugin plugin : build.getPlugins()) {
      if (!BUILD_HELPER_PLUGIN_ARTIFACT_ID.equals(plugin.getArtifactId())) {
        continue;
      }
      if (plugin.getExecutions() == null) {
        continue;
      }
      for (PluginExecution execution : plugin.getExecutions()) {
        if (execution.getGoals() == null
            || !execution.getGoals().contains(ADD_SOURCE_GOAL)) {
          continue;
        }
        if (execution.getConfiguration() instanceof Xpp3Dom dom) {
          target.add(dom);
        }
      }
    }
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
   * The declared class-file target level, or {@code null} when unspecified.
   *
   * <p>Read the same way as the language level: the compiler plugin's configuration first, then
   * the {@code maven.compiler.target} property.
   *
   * <p><b>Why it is separate from the language level.</b> {@code <release>} pins the language level
   * AND the platform API, and it is the level the class files were produced for. {@code <source>}
   * is the language level alone, and {@code <target>} is the class-file version alone -- a build may
   * compile Java 8 syntax into Java 8 class files, or into Java 11 class files, and the two
   * answers are different questions.
   */
  String targetLevel() {
    String plugin = pluginConfiguration("target");
    if (plugin != null) {
      return plugin;
    }
    String property = properties.getProperty("maven.compiler.target");
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
   * <p>The module's own configuration is searched first, then each parent's, so a child inherits
   * what the parent declared and can still override it.
   *
   * @param name the configuration element name, e.g. {@code release}
   * @return the resolved value, or {@code null} when the plugin does not declare it
   */
  private String pluginConfiguration(String name) {
    for (Xpp3Dom dom : compilerConfigurations) {
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

  /**
   * The extra arguments the compiler plugin passes to the compiler.
   *
   * <p><b>What this is.</b> The {@code <compilerArgs>} element of the compiler plugin's
   * configuration holds arguments that are passed to the compiler verbatim, and they are not
   * options this code knows about: {@code -Xlint:all}, {@code -parameters}, {@code -Werror}. The
   * plugin's own {@code <compilerArgs>} element takes one argument per {@code <arg>} child.
   *
   * <p><b>Why they matter.</b> An argument like {@code -proc:none} turns annotation processing
   * off, and {@code -parameters} changes the class files. Neither can be reconstructed from the
   * pom's other settings, so without them the described compile is not the performed one.
   *
   * @return the arguments, in the order the pom declares them
   */
  List<String> compilerArgs() {
    List<String> out = new ArrayList<>();
    for (Xpp3Dom dom : compilerConfigurations) {
      Xpp3Dom args = dom.getChild("compilerArgs");
      if (args == null) {
        continue;
      }
      for (Xpp3Dom arg : args.getChildren()) {
        String value = arg.getValue();
        if (value == null || value.isBlank()) {
          continue;
        }
        String resolved = resolve(value);
        if (resolved != null && !resolved.isBlank() && !out.contains(resolved)) {
          out.add(resolved);
        }
      }
    }
    return out;
  }

  /**
   * The annotation processor paths the compiler plugin declares.
   *
   * <p><b>What this is.</b> The {@code <annotationProcessorPaths>} element of the compiler
   * plugin's configuration names the jars that hold the annotation processors. They are not part
   * of the compile classpath -- the processor path is a path of its own -- and they are the
   * jars that decide which annotations are processed at all.
   *
   * <p><b>Why the coordinates are reported and not the jars.</b> A path element declares a
   * dependency: a group id, an artifact id and a version. Turning that into a jar means resolving
   * it, and the resolution is the resolver's job, not this class's. What this class reports is
   * what the pom declared, so the two sides can be compared.
   *
   * @return the processor path's coordinates, in the order the pom declares them
   */
  List<String> annotationProcessorPaths() {
    List<String> out = new ArrayList<>();
    for (Xpp3Dom dom : compilerConfigurations) {
      Xpp3Dom paths = dom.getChild("annotationProcessorPaths");
      if (paths == null) {
        continue;
      }
      for (Xpp3Dom path : paths.getChildren()) {
        if (!"path".equals(path.getName())) {
          continue;
        }
        String groupId = childValue(path, "groupId");
        String artifactId = childValue(path, "artifactId");
        String version = childValue(path, "version");
        if (groupId == null || artifactId == null) {
          // A path without coordinates cannot be resolved; skip it rather
          // than report half a dependency.
          continue;
        }
        String coordinate = groupId + ":" + artifactId + ":" + (version == null ? "" : version);
        if (!out.contains(coordinate)) {
          out.add(coordinate);
        }
      }
    }
    return out;
  }

  /**
   * The value of a child element, resolved against the effective properties.
   *
   * @param parent the element to look in
   * @param name the child element's name
   * @return the resolved value, or {@code null} when the child is absent or empty
   */
  private String childValue(Xpp3Dom parent, String name) {
    Xpp3Dom child = parent.getChild(name);
    if (child == null) {
      return null;
    }
    String value = child.getValue();
    if (value == null || value.isBlank()) {
      return null;
    }
    return resolve(value);
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

  /**
   * The set of source roots declared for this module.
   *
   * <p>Read from the pom's {@code <build><sourceDirectory>}, so a project that puts its sources
   * somewhere else is described correctly. The default is Maven's own {@code src/main/java}.
   *
   * <p>The roots are relative to the module's directory, and the caller resolves them.
   */
  Set<String> sourceRoots(File moduleRoot) {
    Set<String> roots = new LinkedHashSet<>();
    var build = model.getBuild();
    roots.add(
        buildDirectory(
            build == null ? null : build.getSourceDirectory(), "src/main/java"));
    for (String extra : additionalSourceRoots()) {
      if (extra == null || extra.isBlank()) {
        continue;
      }
      roots.add(extra);
    }
    return roots;
  }

  /**
   * The source directories the build-helper plugin adds, relative to the module's directory.
   *
   * <p>Read from each {@code add-source} execution's {@code <sources><source>} list. A value
   * that is a {@code ${...}} placeholder is resolved against the properties, because that is
   * what Maven does; an unresolvable one is left alone rather than becoming a directory named
   * after the placeholder.
   */
  private List<String> additionalSourceRoots() {
    List<String> out = new ArrayList<>();
    for (Xpp3Dom dom : additionalSourceConfigurations) {
      Xpp3Dom sources = dom.getChild("sources");
      if (sources == null) {
        continue;
      }
      for (Xpp3Dom source : sources.getChildren("source")) {
        String value = source.getValue();
        if (value == null || value.isBlank()) {
          continue;
        }
        out.add(resolve(value));
      }
    }
    return out;
  }

  /** The declared test source root, relative to the module's directory. */
  String testSourceRoot() {
    var build = model.getBuild();
    return buildDirectory(
        build == null ? null : build.getTestSourceDirectory(), "src/test/java");
  }

  /**
   * The declared output directory, relative to the module's directory.
   *
   * <p>Maven's own default is {@code target/classes}. A pom that declares another one is honoured,
   * because a hard-coded value is a confident wrong answer for a project that declared a
   * different one.
   */
  String outputDirectory() {
    var build = model.getBuild();
    return buildDirectory(
        build == null ? null : build.getOutputDirectory(), "target/classes");
  }

  /** The declared test output directory, relative to the module's directory. */
  String testOutputDirectory() {
    var build = model.getBuild();
    return buildDirectory(
        build == null ? null : build.getTestOutputDirectory(), "target/test-classes");
  }

  /**
   * A directory from the pom's {@code <build>}, or the given default.
   *
   * <p>The Maven model exposes the four directories as typed getters, and it has already applied
   * its own defaults, so an undeclared directory comes back as Maven's default rather than as
   * null.
   *
   * @param value the value the model reports
   * @param fallback the value to use when the pom declares nothing
   */
  private static String buildDirectory(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
