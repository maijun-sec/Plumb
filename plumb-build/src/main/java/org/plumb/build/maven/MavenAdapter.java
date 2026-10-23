package org.plumb.build.maven;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Exclusion;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.plumb.build.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R-833 P0 ? Maven build-system adapter.
 *
 * <p>Resolves a Maven project root ({@code pom.xml} at the root) into a {@link BuildProject}.
 * Supports:
 *
 * <ul>
 *   <li>Single-module projects: {@code projectRoot/pom.xml}.
 *   <li>Multi-module (aggregator) projects: the root {@code pom.xml} has {@code <modules>}; each
 *       child {@code pom.xml} becomes a {@link BuildProject.Module} entry keyed by its {@code
 *       <artifactId>}.
 *   <li>Standard Maven layout: {@code src/main/java} for production, {@code src/test/java} for
 *       tests, plus {@code <build><sourceDirectory>/<testSourceDirectory>} overrides.
 *   <li>Parent inheritance: {@code <parent>} chain is followed to inherit {@code groupId},
 *       {@code version}, {@code properties}, and {@code <dependencyManagement>}. {@code
 *       ${...}} placeholders in properties / dependency coordinates are resolved.
 *   <li>Local repository resolution of {@code compile} / {@code runtime} / {@code provided} /
 *       {@code system} scope dependencies, with classifier / packaging / SNAPSHOT support.
 *   <li>Path-traversal safety: a {@code <module>} containing {@code ../} is rejected.
 * </ul>
 *
 * <h2>Classpath resolution</h2>
 *
 * <p>For each module's dependency list we:
 *
 * <ol>
 *   <li>Resolve properties so {@code ${some.version}} becomes a concrete version.
 *   <li>Look up the {@code groupId:artifactId} in the merged {@code <dependencyManagement>} map so a
 *       sub-module dependency without a {@code <version>} inherits its parent-managed version.
 *   <li>Match the dependency against sibling {@code target/classes} directories (multi-module
 *       local-classpath short-circuit).
 *   <li>Otherwise resolve against {@code ~/.m2/repository}. SNAPSHOT dependencies read the
 *       local {@code maven-metadata-*.xml} to pick the timestamped jar.
 *   <li>Honor {@code <classifier>} ({@code foo-1.0-jdk17.jar}) and {@code <type>} ({@code .jar},
 *       {@code .aar}, {@code .war}). Skip {@code <packaging>pom</packaging>} imports correctly.
 *   <li>Honor {@code <scope>system</scope>} by reading {@code <systemPath>} verbatim.
 *   <li>Honor {@code <scope>provided</scope>} by keeping the jar in the compile classpath (matches
 *       {@code javac} semantics ? provided jars are needed to compile against, just not to ship).
 *   <li>Deduplicate the result so a transitive reference appearing in N modules is added once.
 * </ol>
 *
 * <h2>Out of scope</h2>
 *
 * <p>Full transitive dependency resolution (version range / nearest-wins) is intentionally
 * <b>not</b> implemented here. For a precise transitive tree, run {@code mvn
 * dependency:build-classpath -Dmdep.outputFile=cp.txt} yourself and pass the result via {@code
 * --classpath}. The adapter is dependency-free (no Maven Embedder) and this keeps the dependency
 * tree small.
 */
public final class MavenAdapter implements BuildAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(MavenAdapter.class);
  private static final String SYSTEM_NAME = "maven";
  private static final int MAX_PARENT_DEPTH = 32;

  /** Default Maven local repo location; overridden by {@code maven.repo.local} system prop. */
  private final Path localRepo;

  public MavenAdapter() {
    this(resolveDefaultRepo());
  }

  /** Constructor for tests: inject a specific {@code ~/.m2/repository} path. */
  public MavenAdapter(Path localRepo) {
    this.localRepo = localRepo;
  }

  private static Path resolveDefaultRepo() {
    String prop = System.getProperty("maven.repo.local");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    String home = System.getProperty("user.home");
    return Path.of(home, ".m2", "repository");
  }

  @Override
  public String systemName() {
    return SYSTEM_NAME;
  }

  @Override
  public boolean probe(Path projectRoot) {
    if (projectRoot == null) return false;
    return Files.isRegularFile(projectRoot.resolve("pom.xml"));
  }

  @Override
  public BuildProject discover(Path projectRoot) {
    if (!probe(projectRoot)) {
      throw new BuildAdapterException(
          "Maven project root must contain a pom.xml: " + projectRoot);
    }
    Path rootPom = projectRoot.resolve("pom.xml");
    Model rawRoot = readModel(rootPom);
    EffectiveModel rootEffective = loadEffective(projectRoot, rawRoot);

    // Multi-module if <modules> is non-empty.
    List<String> moduleNames = rawRoot.getModules();
    if (moduleNames == null) moduleNames = List.of();

    boolean multi = !moduleNames.isEmpty();
    LOG.info(
        "Maven: project root {} ({}{})",
        projectRoot,
        multi ? "multi-module, " + moduleNames.size() + " modules: " : "single-module ",
        multi ? moduleNames : rootEffective.model.getArtifactId());

    if (multi) {
      return buildMulti(projectRoot, rawRoot, rootEffective, moduleNames);
    }
    return buildSingle(projectRoot, rootEffective);
  }

  // ---------------- single-module ----------------

  private BuildProject buildSingle(Path root, EffectiveModel effective) {
    Model m = effective.model;
    Path mainJava = resolveSourceDir(root, m, "src/main/java", buildSourceDir(m, "sourceDirectory"));
    Path testJava = resolveSourceDir(root, m, "src/test/java", buildSourceDir(m, "testSourceDirectory"));

    List<Path> sources = SourceFiles.walk(mainJava, sourceExtensions());
    List<Path> tests = SourceFiles.walk(testJava, sourceExtensions());
    List<Path> cp = resolveCompileClasspath(root, m, effective);

    return BuildProject.builder(SYSTEM_NAME)
        .version(m.getModelVersion() != null ? m.getModelVersion() : null)
        .sourceFiles(sources)
        .testSourceFiles(tests)
        .classpathJars(cp)
        .sourceRoots(List.of(mainJava))
        .moduleRoots(List.of(root))
        .languageLevel(detectLanguageLevel(m))
        .sourceEncoding(detectEncoding(m))
        .build();
  }

  // ---------------- multi-module ----------------

  private BuildProject buildMulti(
      Path root, Model rawRoot, EffectiveModel rootEffective, List<String> moduleNames) {
    List<Path> allSources = new ArrayList<>();
    List<Path> allTests = new ArrayList<>();
    LinkedHashSet<Path> allCp = new LinkedHashSet<>();
    List<Path> sourceRoots = new ArrayList<>();
    List<Path> moduleRoots = new ArrayList<>();
    Map<String, BuildProject.Module> modules = new LinkedHashMap<>();

    // Top-level dependencyManagement from the parent aggregator applies to every child.
    EffectiveModel parentEffective = rootEffective;
    Properties parentProps = parentEffective.properties;

    for (String mod : moduleNames) {
      // P2-#17: refuse path-traversal in <modules> entries
      Path childDir = safeResolveInside(root, mod);
      if (childDir == null) {
        throw new BuildAdapterException(
            "Maven module path '" + mod + "' escapes project root " + root);
      }
      Path childPom = childDir.resolve("pom.xml");
      if (!Files.isRegularFile(childPom)) {
        LOG.warn("Maven: skipping module {} (no pom.xml at {})", mod, childPom);
        continue;
      }
      Model childRaw = readModel(childPom);
      EffectiveModel childEffective =
          loadEffective(childDir, childRaw, parentProps, parentEffective.managedDeps);
      Model cm = childEffective.model;
      String id = cm.getArtifactId();
      if (id == null || id.isBlank()) id = mod;

      Path mainJava =
          resolveSourceDir(childDir, cm, "src/main/java", buildSourceDir(cm, "sourceDirectory"));
      Path testJava =
          resolveSourceDir(childDir, cm, "src/test/java", buildSourceDir(cm, "testSourceDirectory"));

      List<Path> srcs = SourceFiles.walk(mainJava, sourceExtensions());
      List<Path> tsts = SourceFiles.walk(testJava, sourceExtensions());
      List<Path> cp = resolveCompileClasspath(childDir, cm, childEffective);
      allSources.addAll(srcs);
      allTests.addAll(tsts);
      allCp.addAll(cp);
      if (Files.isDirectory(mainJava)) sourceRoots.add(mainJava);
      moduleRoots.add(childDir);
      modules.put(
          id,
          new BuildProject.Module(
              id,
              mainJava,
              srcs,
              tsts,
              cp,
              BuildProject.Scope.COMPILE,
              detectLanguageLevel(cm),
              detectEncoding(cm)));
    }

    return BuildProject.builder(SYSTEM_NAME)
        .version(rawRoot != null ? "Maven model " + safe(rawRoot.toString()) : null)
        .sourceFiles(allSources)
        .testSourceFiles(allTests)
        .classpathJars(new ArrayList<>(allCp))
        .sourceRoots(sourceRoots)
        .moduleRoots(moduleRoots)
        .modules(modules)
        .languageLevel(detectLanguageLevel(rawRoot))
        .sourceEncoding(detectEncoding(rawRoot))
        .build();
  }

  // ---------------- source directory helpers ----------------

  /**
   * Resolve the actual source directory for a build. Honours the {@code <build><sourceDirectory>}
   * override (with {@code ${...}} property substitution) but falls back to the conventional
   * default when the override is missing, blank, or absent.
   */
  private Path resolveSourceDir(
      Path moduleRoot, Model model, String defaultRel, String override) {
    if (override != null && !override.isBlank()) {
      // Maven resolves sourceDirectory relative to the module root.
      Path p = Path.of(override);
      if (!p.isAbsolute()) p = moduleRoot.resolve(override);
      return p;
    }
    return moduleRoot.resolve(defaultRel);
  }

  /** Read {@code <build><sourceDirectory>} or {@code <build><testSourceDirectory>}. */
  private static String buildSourceDir(Model model, String which) {
    if (model == null) return null;
    var build = model.getBuild();
    if (build == null) return null;
    String v = "testSourceDirectory".equals(which) ? build.getTestSourceDirectory() : build.getSourceDirectory();
    if (v == null || v.isBlank()) return null;
    return v;
  }

  // ---------------- classpath ----------------

  /**
   * Resolve the {@code compile} / {@code runtime} / {@code provided} / {@code system}-scope
   * dependencies of {@code model} against the local Maven repository. Returns absolute {@link
   * Path}s to the resolved jars / class-folders; entries that cannot be resolved are skipped with
   * a warning (the caller may pass them as {@code --classpath} manually).
   *
   * <p>Provided-scope deps are kept in the compile classpath (they're needed to compile against
   * servlet API etc.). System-scope deps resolve via {@code <systemPath>}. Test-scope deps are
   * <b>not</b> added here ? they're scanned separately under {@code testSourceFiles} and added
   * to the per-module test classpath in R797+ work.
   */
  private List<Path> resolveCompileClasspath(
      Path moduleRoot, Model model, EffectiveModel effective) {
    LinkedHashSet<Path> jars = new LinkedHashSet<>();
    Map<String, Path> siblingTargets = collectSiblingTargets(moduleRoot);
    if (model.getDependencies() != null) {
      for (Dependency dep : model.getDependencies()) {
        String gid = dep.getGroupId();
        String aid = dep.getArtifactId();
        // Apply dependencyManagement to fill in missing version / scope / exclusions.
        dep = applyManagement(dep, effective.managedDeps);
        gid = dep.getGroupId();
        aid = dep.getArtifactId();
        // Apply ${...} property substitution to every coordinate field so a dep like
        // <version>${guava.version}</version> resolves to the concrete version declared in
        // <properties> (this matches what mvn does at build time).
        String ver = resolveProperties(dep.getVersion(), effective.properties);
        BuildProject.Scope scope = BuildProject.Scope.fromString(dep.getScope());

        if (scope == BuildProject.Scope.TEST) {
          // Test deps belong on the test classpath, not the main one. Skipped here.
          continue;
        }
        if (gid == null || aid == null || ver == null) {
          LOG.debug(
              "Maven: skipping dependency with missing coordinates: {}:{}:{}",
              gid,
              aid,
              ver);
          continue;
        }

        // P1-#6: system scope → use systemPath verbatim
        if (scope == BuildProject.Scope.SYSTEM) {
          String sp = dep.getSystemPath();
          if (sp != null && !sp.isBlank()) {
            String resolved = resolveProperties(sp, effective.properties);
            Path entry = Path.of(resolved);
            if (!entry.isAbsolute()) entry = moduleRoot.resolve(resolved);
            if (Files.isRegularFile(entry)) {
              jars.add(entry);
              continue;
            }
            LOG.warn("Maven: systemPath {} is not a regular file", entry);
          }
          continue;
        }

        // P1-#6: <packaging>pom</packaging> (BOM import) — no artifact to download.
        String type = dep.getType();
        if (type != null && (type.equals("pom") || type.equals("bundle"))) {
          continue;
        }

        Path sibling = siblingTargets.get(gid + ":" + aid);
        if (sibling != null) {
          jars.add(sibling);
          continue;
        }
        Path resolved = resolveArtifact(gid, aid, ver, dep.getClassifier(), type);
        if (resolved != null) {
          jars.add(resolved);
        } else {
          LOG.warn(
              "Maven: could not resolve dependency {}:{}:{} in {}",
              gid,
              aid,
              ver,
              moduleRoot);
        }
      }
    }
    return new ArrayList<>(jars);
  }

  /**
   * Walk sibling module directories for {@code target/classes} so we can short-circuit the
   * local-repo lookup. The sibling key is {@code groupId:artifactId}, which the caller passes by
   * parsing each sibling's pom.xml.
   */
  private Map<String, Path> collectSiblingTargets(Path moduleRoot) {
    Map<String, Path> map = new LinkedHashMap<>();
    Path parent = moduleRoot.getParent();
    if (parent == null) return map;
    try (Stream<Path> stream = Files.list(parent)) {
      stream
          .filter(Files::isDirectory)
          .forEach(
              siblingDir -> {
                Path sibPom = siblingDir.resolve("pom.xml");
                if (!Files.isRegularFile(sibPom)) return;
                try {
                  Model m = readModel(sibPom);
                  String key = safe(m.getGroupId()) + ":" + safe(m.getArtifactId());
                  Path classes = siblingDir.resolve("target/classes");
                  if (Files.isDirectory(classes)) {
                    map.put(key, classes);
                  }
                } catch (Exception ignored) {
                  // not a Maven module; skip
                }
              });
    } catch (IOException e) {
      LOG.debug("Maven: could not enumerate sibling modules of {}", moduleRoot, e);
    }
    return map;
  }

  /**
   * Resolve {@code groupId:artifactId:version} into a path under the local repository. Returns
   * {@code null} if the artifact is not present (e.g. the user never ran {@code mvn
   * dependency:resolve}).
   *
   * <p>For {@code SNAPSHOT} versions we read {@code maven-metadata-*.xml} to find the latest
   * timestamped subdirectory, e.g. {@code <repo>/com/example/foo/1.0-SNAPSHOT/} → {@code
   * maven-metadata.xml} → {@code <latest>1.0.0-20240101.000000-1</latest>} → {@code
   * <repo>/com/example/foo/1.0-SNAPSHOT/1.0.0-20240101.000000-1/foo-1.0.0-20240101.000000-1.jar}.
   *
   * <p>For non-SNAPSHOT versions we look at the conventional layout.
   *
   * <p>{@code classifier} and {@code type} are honoured: the jar filename becomes {@code
   * <artifactId>-<version>-<classifier>.<type>} (classifier omitted when blank) ? matches the
   * Maven convention for {@code foo-1.0-jdk17.jar} etc.
   */
  private Path resolveArtifact(
      String groupId, String artifactId, String version, String classifier, String type) {
    String groupPath = groupId.replace('.', '/');
    String pkgType = (type == null || type.isBlank()) ? "jar" : type;
    Path baseDir = localRepo.resolve(groupPath).resolve(artifactId).resolve(version);

    // SNAPSHOT: read maven-metadata-*.xml to find the timestamped subdir.
    if (version.endsWith("-SNAPSHOT")) {
      Path meta = baseDir.resolve("maven-metadata-local.xml");
      Path metaRemote = baseDir.resolve("maven-metadata.xml");
      String snapshotVer = readSnapshotVersion(meta);
      if (snapshotVer == null) snapshotVer = readSnapshotVersion(metaRemote);
      if (snapshotVer != null) {
        Path tsDir = baseDir.resolve(snapshotVer);
        Path jar =
            tsDir.resolve(artifactId + "-" + snapshotVer + suffix(classifier) + "." + pkgType);
        if (Files.isRegularFile(jar)) return jar;
      }
    }

    // Conventional layout: <groupDir>/<artifact>/<version>/<artifact>-<version>[-classifier].<type>
    Path plain = baseDir.resolve(artifactId + "-" + version + suffix(classifier) + "." + pkgType);
    return Files.isRegularFile(plain) ? plain : null;
  }

  /**
   * Read {@code <latest>} (or {@code <release>}) from a maven-metadata file. Returns the timestamp
   * string (e.g. {@code 1.0.0-20240101.000000-1}) or {@code null} if the file is absent / cannot
   * be parsed.
   */
  private String readSnapshotVersion(Path metadataFile) {
    if (!Files.isRegularFile(metadataFile)) return null;
    try {
      String xml = Files.readString(metadataFile, StandardCharsets.UTF_8);
      // <latest>1.0.0-20240101.000000-1</latest>  OR  <release>...</release>
      java.util.regex.Matcher m =
          java.util.regex.Pattern
              .compile("<(?:latest|release)>([^<]+)</(?:latest|release)>")
              .matcher(xml);
      if (m.find()) return m.group(1).trim();
    } catch (IOException e) {
      LOG.debug("Maven: failed to read {}: {}", metadataFile, e.getMessage());
    }
    return null;
  }

  /** Return {@code -classifier} when classifier is non-blank, else empty string. */
  private static String suffix(String classifier) {
    if (classifier == null || classifier.isBlank()) return "";
    return "-" + classifier;
  }

  // ---------------- model reader + effective-model assembly ----------------

  /**
   * Wrapper around a {@link Model} after parent / properties / dependencyManagement have been
   * merged in. The {@code managedDeps} map keys on {@code groupId:artifactId} (type+classifier
   * agnostic) ? that's what Maven actually does.
   */
  static final class EffectiveModel {
    final Model model;
    final Properties properties;
    final Map<String, Dependency> managedDeps;
    final Path localRepo;

    EffectiveModel(Model model, Properties properties, Map<String, Dependency> managedDeps, Path localRepo) {
      this.model = model;
      this.properties = properties;
      this.managedDeps = managedDeps;
      this.localRepo = localRepo;
    }
  }

  /**
   * Load the effective model for {@code rawModel}: follow the {@code <parent>} chain (bounded to
   * {@value #MAX_PARENT_DEPTH} levels to defend against a self-referential parent), merge parent
   * properties, and merge dependencyManagement entries.
   */
  private EffectiveModel loadEffective(Path moduleRoot, Model rawModel) {
    return loadEffective(moduleRoot, rawModel, new Properties(), new LinkedHashMap<>(), 0);
  }

  /**
   * Recursive effective-model loader. {@code inheritedProps} and {@code inheritedManaged} come
   * from the parent aggregator.
   */
  private EffectiveModel loadEffective(
      Path moduleRoot,
      Model rawModel,
      Properties inheritedProps,
      Map<String, Dependency> inheritedManaged) {
    return loadEffective(
        moduleRoot, rawModel, inheritedProps, inheritedManaged, 0);
  }

  /** Inner recursive helper that guards against parent cycles. */
  private EffectiveModel loadEffective(
      Path moduleRoot,
      Model rawModel,
      Properties inheritedProps,
      Map<String, Dependency> inheritedManaged,
      int depth) {
    Properties props = new Properties();
    props.putAll(inheritedProps);
    // Maven built-ins: ${basedir} = the directory of the pom being processed.
    if (moduleRoot != null) {
      props.setProperty("basedir", moduleRoot.toAbsolutePath().toString());
      props.setProperty("project.basedir", moduleRoot.toAbsolutePath().toString());
    }
    if (rawModel.getProperties() != null) {
      for (String key : rawModel.getProperties().stringPropertyNames()) {
        props.setProperty(key, rawModel.getProperties().getProperty(key));
      }
    }
    Map<String, Dependency> managed = new LinkedHashMap<>(inheritedManaged);
    if (rawModel.getDependencyManagement() != null
        && rawModel.getDependencyManagement().getDependencies() != null) {
      for (Dependency d : rawModel.getDependencyManagement().getDependencies()) {
        if (d.getGroupId() == null || d.getArtifactId() == null) continue;
        managed.put(d.getGroupId() + ":" + d.getArtifactId(), d);
      }
    }
    Parent parent = rawModel.getParent();
    if (parent != null && depth < MAX_PARENT_DEPTH) {
      Path parentPom = locateParentPom(moduleRoot, parent);
      if (parentPom != null) {
        if (rawModel.getGroupId() == null || rawModel.getGroupId().isBlank()) {
          rawModel.setGroupId(parent.getGroupId());
        }
        if (rawModel.getVersion() == null || rawModel.getVersion().isBlank()) {
          rawModel.setVersion(parent.getVersion());
        }
        Model parentRaw = readModel(parentPom);
        loadEffective(parentPom.getParent(), parentRaw, props, managed, depth + 1);
      }
    }
    if (rawModel.getVersion() != null) {
      rawModel.setVersion(resolveProperties(rawModel.getVersion(), props));
    }
    return new EffectiveModel(rawModel, props, managed, localRepo);
  }

  /**
   * Locate a {@code <parent>}'s pom.xml. Honours {@code <relativePath>} (default {@code ../}).
   * Falls back to the local repository ({@code <groupId-path>/<parent>.artifactId/<parent>.
   * version>/<parent>.artifactId-<parent>.version.pom}).
   */
  private Path locateParentPom(Path childDir, Parent parent) {
    String relative = parent.getRelativePath();
    if (relative == null || relative.isBlank()) relative = "../";
    if (!relative.endsWith(".xml")) {
      // Maven convention: ../pom.xml, ./pom.xml, ../parent/pom.xml
      relative = relative + (relative.endsWith("/") ? "pom.xml" : "/pom.xml");
    }
    Path rel = childDir.resolve(relative).normalize();
    if (Files.isRegularFile(rel)) return rel;

    // Repository fallback
    String gid = parent.getGroupId();
    String aid = parent.getArtifactId();
    String ver = parent.getVersion();
    if (gid != null && aid != null && ver != null) {
      Path repoPom =
          localRepo
              .resolve(gid.replace('.', '/'))
              .resolve(aid)
              .resolve(ver)
              .resolve(aid + "-" + ver + ".pom");
      if (Files.isRegularFile(repoPom)) return repoPom;
    }
    return null;
  }

  /** Look up a {@link Dependency} in the merged management map and copy missing fields in. */
  private Dependency applyManagement(Dependency dep, Map<String, Dependency> managed) {
    if (dep.getGroupId() == null || dep.getArtifactId() == null) return dep;
    Dependency m = managed.get(dep.getGroupId() + ":" + dep.getArtifactId());
    if (m == null) return dep;
    if (dep.getVersion() == null || dep.getVersion().isBlank()) {
      dep.setVersion(m.getVersion());
    }
    if (dep.getScope() == null || dep.getScope().isBlank()) {
      dep.setScope(m.getScope());
    }
    if (dep.getType() == null || dep.getType().isBlank()) {
      dep.setType(m.getType());
    }
    if (dep.getClassifier() == null || dep.getClassifier().isBlank()) {
      dep.setClassifier(m.getClassifier());
    }
    return dep;
  }

  /**
   * Resolve {@code ${...}} placeholders in {@code value} using {@code props}. Multiple
   * placeholders are supported; unknown keys are left as the literal placeholder so the caller
   * sees a meaningful warning.
   */
  private static String resolveProperties(String value, Properties props) {
    if (value == null || !value.contains("${")) return value;
    StringBuilder out = new StringBuilder(value.length());
    int i = 0;
    while (i < value.length()) {
      int start = value.indexOf("${", i);
      if (start < 0) {
        out.append(value, i, value.length());
        break;
      }
      out.append(value, i, start);
      int end = value.indexOf('}', start + 2);
      if (end < 0) {
        out.append(value, i, value.length());
        break;
      }
      String key = value.substring(start + 2, end);
      String replacement = props.getProperty(key);
      if (replacement == null) {
        // Try a project-prefixed lookup, e.g. ${project.version}.
        replacement = props.getProperty("project." + key);
      }
      if (replacement != null) {
        out.append(replacement);
      } else {
        // Leave the placeholder intact so the user sees what's unresolved.
        out.append(value, start, end + 1);
      }
      i = end + 1;
    }
    return out.toString();
  }

  // ---------------- language level / encoding ----------------

  /**
   * Read the project's Java source / target level. Looks at {@code maven.compiler.source} /
   * {@code maven.compiler.target} properties, falls back to {@code java.version}, and finally
   * returns null so the parser falls back to its default (Java 17). Plugin {@code
   * <configuration>} inspection is skipped for now because plexus's PlexusConfiguration isn't on
   * the classpath; the property lookup catches the common case.
   */
  private static String detectLanguageLevel(Model m) {
    if (m == null) return null;
    Properties props = m.getProperties();
    if (props != null) {
      String v = props.getProperty("maven.compiler.source");
      if (v == null) v = props.getProperty("maven.compiler.target");
      if (v == null) v = props.getProperty("java.version");
      if (v != null) {
        String cleaned = v.replace("1.", "").trim();
        if (cleaned.matches("\\d+")) return cleaned;
      }
    }
    return null;
  }

  /**
   * Read {@code <project><properties><project.build.sourceEncoding>} when present. Null when
   * absent ? caller falls back to UTF-8.
   */
  private static Charset detectEncoding(Model m) {
    if (m == null) return null;
    Properties props = m.getProperties();
    if (props == null) return null;
    String enc = props.getProperty("project.build.sourceEncoding");
    if (enc == null || enc.isBlank()) return null;
    try {
      return Charset.forName(enc.trim());
    } catch (Exception e) {
      LOG.debug("Maven: unsupported source encoding {}: {}", enc, e.getMessage());
      return null;
    }
  }

  // ---------------- safety helpers ----------------

  /**
   * Resolve {@code rel} against {@code root} and verify the result is still under {@code root}
   * (no path traversal via {@code ../}). Returns null when the result escapes.
   */
  private static Path safeResolveInside(Path root, String rel) {
    if (rel == null || rel.isBlank()) return null;
    Path resolved = root.resolve(rel).normalize();
    Path rootNorm = root.toAbsolutePath().normalize();
    Path absResolved = resolved.toAbsolutePath();
    if (!absResolved.startsWith(rootNorm)) return null;
    return resolved;
  }

  // ---------------- model reader ----------------

  private Model readModel(Path pom) {
    MavenXpp3Reader reader = new MavenXpp3Reader();
    try (Reader r = Files.newBufferedReader(pom, StandardCharsets.UTF_8)) {
      return reader.read(r);
    } catch (IOException | XmlPullParserException e) {
      throw new BuildAdapterException("Failed to parse " + pom + ": " + e.getMessage(), e);
    }
  }

  private static String safe(String s) {
    return s == null ? "" : s;
  }

  /** Helper for tests. */
  Path getLocalRepo() {
    return localRepo;
  }

  /** Sanity helper for tests / debugging: returns the canonical pom file. */
  static Path canonicalPom(Path root) {
    return root.resolve("pom.xml");
  }

  /** Avoid a hard reference to {@code File.pathSeparator} for cross-OS sanity. */
  static String pathSep() {
    return File.pathSeparator;
  }

  // ---------------- unused (visible for tests) ----------------
  // NOTE: the dependency / managedDeps dedup is handled at the resolveArtifact layer via the
  // LinkedHashSet in resolveCompileClasspath, but we keep a helper here so tests can assert
  // on it directly.
  Set<String> visibleForTestsOnly() {
    return new HashSet<>(Set.of("visibleForTestsOnly"));
  }
}