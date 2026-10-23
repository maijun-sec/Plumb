package org.plumb.build.gradle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.plumb.build.SourceFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R-833 P1 ? Gradle build-system adapter.
 *
 * <p>Recognises the standard marker files {@code build.gradle} (Groovy DSL), {@code
 * build.gradle.kts} (Kotlin DSL) and {@code settings.gradle}, {@code settings.gradle.kts}, plus
 * the version-catalog file {@code gradle/libs.versions.toml}. The adapter is parser-light:
 * rather than pulling in Gradle Tooling API (huge dependency, requires a running Gradle daemon) we
 * extract the project structure from {@code settings.gradle} and the per-module {@code sourceSets}
 * blocks.
 *
 * <h2>What we parse</h2>
 *
 * <ul>
 *   <li><b>project layout</b> — {@code sourceSets.main.java.srcDirs} and {@code
 *       sourceSets.test.java.srcDirs} lists per module. Defaults follow the Gradle JavaPlugin
 *       convention ({@code src/main/java}, {@code src/test/java}). Kotlin source sets ({@code
 *       src/main/kotlin}, {@code src/test/kotlin}) are also recognised.
 *   <li><b>dependencies</b> — {@code dependencies { implementation 'g:a:v' } } / {@code api(...)} /
 *       {@code compileOnly(...)} blocks. We collect the {@code group:artifact:version} triples and
 *       resolve them against the local Maven repository (Gradle re-uses {@code ~/.m2/repository}
 *       via {@code mavenLocal()}). The version-catalog {@code gradle/libs.versions.toml} is
 *       parsed for {@code alias -> g:a:v} mappings so {@code implementation libs.foo.bar}
 *       references resolve to a concrete coordinate.
 *   <li><b>version-catalog</b> — {@code gradle/libs.versions.toml}: alias names from the {@code
 *       [libraries]} section.
 * </ul>
 *
 * <h2>What we do NOT parse (out of scope for the adapter)</h2>
 *
 * <ul>
 *   <li>Plugin DSL ? we assume the Java/Kotlin JVM plugin (default {@code src/main/java} layout).
 *       Custom source-set extensions are silently ignored (we still pick up the default paths).
 *   <li>buildSrc / composite builds ? the user can pass {@code --classpath} manually for those.
 *   <li>{@code platform(...)} / {@code enforcedPlatform(...)} BOM imports ? recorded with a debug
 *       log; the user can supply the BOM jar via {@code --classpath}.
 * </ul>
 */
public final class GradleAdapter implements BuildAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(GradleAdapter.class);
  private static final String SYSTEM_NAME = "gradle";

  /**
   * Matches {@code implementation 'g:a:v'} / {@code api("g:a:v")} / {@code compileOnly group: 'g',
   * name: 'a', version: 'v'} etc. We only need the {@code g:a:v} triple; the more verbose form is
   * normalised by {@link #NORMALISE_MAP_DEP} below.
   */
  private static final Pattern SHORT_DEP =
      Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-+\\.]+)['\"]");

  /** {@code group: 'x', name: 'y', version: 'z'} maps to "x:y:z". */
  private static final Pattern NORMALISE_MAP_DEP =
      Pattern.compile(
          "group\\s*:\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*"
              + "name\\s*:\\s*['\"]([\\w.\\-]+)['\"]\\s*,\\s*"
              + "version\\s*:\\s*['\"]([\\w.\\-+\\.]+)['\"]");

  /**
   * Kotlin DSL {@code implementation("group:artifact:version")} / {@code api("group:artifact:version")}
   * and friends. Identical regex to {@link #SHORT_DEP} but kept separate for documentation /
   * future divergence.
   */
  private static final Pattern KOTLIN_DEP =
      Pattern.compile("['\"]([\\w.\\-]+):([\\w.\\-]+):([\\w.\\-+\\.]+)['\"]");

  /**
   * {@code libs.foo.bar} (with optional dot-delimited nested aliases). The {@code [a-zA-Z_]+}
   * segment keeps things conservative ? we won't accidentally swallow other identifiers.
   */
  private static final Pattern CATALOG_REF =
      Pattern.compile("\\blibs\\.([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)*)");

  /** {@code sourceSets.main.java.srcDirs = [...]} / {@code sourceSets["main"].java.srcDirs(...)}. */
  private static final Pattern SRCDIRS_BLOCK =
      Pattern.compile(
          "sourceSets?\\s*[\\.\"']?\\s*([\\w\"']+)?\\s*[\\.\"']?\\s*(?:java|kotlin)?\\s*[\\.\"']?"
              + "srcDirs?\\s*=*\\s*([^\\n]+)");

  /** {@code sourceSets.main.java { srcDirs = ['src/main/java', 'gen'] }} — block form. */
  private static final Pattern SRCDIRS_NESTED =
      Pattern.compile(
          "sourceSets?\\s*[\\.\"']?\\s*([\\w\"']+)?\\s*[\\.\"']?\\s*(?:java|kotlin)?\\s*\\{([^}]+?)\\}",
          Pattern.DOTALL);

  /** {@code java { sourceCompatibility = JavaVersion.VERSION_17 }} / {@code jvmTarget = "17"}. */
  private static final Pattern SOURCE_COMPAT =
      Pattern.compile(
          "(?:sourceCompatibility|jvmTarget|targetCompatibility|javaVersion)\\s*=*\\s*[\"']?([0-9]+|[\\w.]+)[\"']?");

  /** {@code sourceCompatibility = JavaVersion.VERSION_17} — JavaVersion constant. */
  private static final Pattern JAVA_VERSION_CONST = Pattern.compile("VERSION_(\\d+)");

  private final Path localRepo;

  public GradleAdapter() {
    this(resolveDefaultRepo());
  }

  public GradleAdapter(Path localRepo) {
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
    return Files.isRegularFile(projectRoot.resolve("build.gradle"))
        || Files.isRegularFile(projectRoot.resolve("build.gradle.kts"));
  }

  @Override
  public BuildProject discover(Path projectRoot) {
    if (!probe(projectRoot)) {
      throw new BuildAdapterException(
          "Gradle project root must contain build.gradle(.kts): " + projectRoot);
    }
    Path buildFile = firstExisting(projectRoot, "build.gradle", "build.gradle.kts");
    String text = readText(buildFile);

    List<Path> allSources = new ArrayList<>();
    List<Path> allTests = new ArrayList<>();
    LinkedHashSet<Path> allCp = new LinkedHashSet<>();
    Map<String, BuildProject.Module> modules = new LinkedHashMap<>();
    List<Path> moduleRoots = new ArrayList<>();

    // Default single-module layout. Multi-module setups have a
    // settings.gradle(.kts) with `include 'subA', 'subB'`.
    List<String> subprojects = parseIncludes(projectRoot);

    // Read the catalog once so dependency parsing can resolve libs.alias references.
    Map<String, String> catalog = parseVersionCatalog(projectRoot);

    String langLevel = detectLanguageLevel(text);

    if (subprojects.isEmpty()) {
      // Single-module project.
      List<Path> srcs = resolveSourceDirs(projectRoot, "main", text);
      List<Path> tsts = resolveSourceDirs(projectRoot, "test", text);
      List<Path> cp = resolveClasspath(text, catalog);
      allSources.addAll(srcs);
      allTests.addAll(tsts);
      allCp.addAll(cp);
      // Register the root module even when srcs is empty (e.g. a Kotlin-only project with no
      // .java files yet) so callers can address it consistently.
      modules.put(
          "root",
          new BuildProject.Module(
              "root",
              projectRoot.resolve("src/main/java"),
              srcs,
              tsts,
              cp,
              BuildProject.Scope.COMPILE,
              langLevel,
              null));
      moduleRoots.add(projectRoot);
    } else {
      // Multi-module setup: the root build.gradle may still carry its own sources (the
      // rootProject sometimes has src/main/java in addition to the included subprojects).
      // Pick those up before iterating the subs.
      {
        List<Path> srcs = resolveSourceDirs(projectRoot, "main", text);
        List<Path> tsts = resolveSourceDirs(projectRoot, "test", text);
        List<Path> cp = resolveClasspath(text, catalog);
        allSources.addAll(srcs);
        allTests.addAll(tsts);
        allCp.addAll(cp);
        modules.put(
            "root",
            new BuildProject.Module(
                "root",
                projectRoot.resolve("src/main/java"),
                srcs,
                tsts,
                cp,
                BuildProject.Scope.COMPILE,
                langLevel,
                null));
        moduleRoots.add(projectRoot);
      }
      for (String sub : subprojects) {
        // P2-#17: refuse path-traversal in `include` entries
        Path subRoot = safeResolveInside(projectRoot, sub);
        if (subRoot == null) {
          LOG.warn("Gradle: skipping include {} (path traversal)", sub);
          continue;
        }
        Path subBuild = firstExisting(subRoot, "build.gradle", "build.gradle.kts");
        String subText = subBuild != null ? readText(subBuild) : "";
        List<Path> srcs = resolveSourceDirs(subRoot, "main", subText);
        List<Path> tsts = resolveSourceDirs(subRoot, "test", subText);
        List<Path> cp = resolveClasspath(subText, catalog);
        allSources.addAll(srcs);
        allTests.addAll(tsts);
        allCp.addAll(cp);
        // After parseIncludes(), the sub is a path with '/'
        // separators ("lib/helpers"). Use the last segment.
        String leaf = sub;
        int slash = sub.lastIndexOf('/');
        if (slash >= 0) leaf = sub.substring(slash + 1);
        // Always register the sub-module so the caller has a stable handle on every include,
        // even when it has no .java sources (e.g. a pure-resource module).
        modules.put(
            leaf,
            new BuildProject.Module(
                leaf,
                subRoot.resolve("src/main/java"),
                srcs,
                tsts,
                cp,
                BuildProject.Scope.COMPILE,
                detectLanguageLevel(subText),
                null));
        moduleRoots.add(subRoot);
      }
    }

    LOG.info("Gradle: project root {} ({} modules)", projectRoot, modules.size());
    return BuildProject.builder(SYSTEM_NAME)
        .version("Gradle adapter v1")
        .sourceFiles(allSources)
        .testSourceFiles(allTests)
        .classpathJars(new ArrayList<>(allCp))
        .sourceRoots(modules.values().stream().map(BuildProject.Module::sourceRoot).toList())
        .moduleRoots(moduleRoots)
        .modules(modules)
        .languageLevel(langLevel)
        .build();
  }

  // ---------------- helpers ----------------

  private static Path firstExisting(Path dir, String... names) {
    for (String n : names) {
      Path p = dir.resolve(n);
      if (Files.isRegularFile(p)) return p;
    }
    return null;
  }

  private static String readText(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BuildAdapterException("Failed to read " + p + ": " + e.getMessage(), e);
    }
  }

  /**
   * {@code include 'a', 'b:c'} in settings.gradle. We keep the FULL relative path (with {@code :}
   * translated to {@code /}) so the caller can resolve the right module directory; the leaf id
   * (last segment) is the module id.
   */
  private static List<String> parseIncludes(Path projectRoot) {
    Path settings = firstExisting(projectRoot, "settings.gradle", "settings.gradle.kts");
    if (settings == null) return List.of();
    String text = readText(settings);
    List<String> result = new ArrayList<>();
    Pattern p = Pattern.compile("include\\s*\\(?\\s*['\"]([^'\"]+)['\"]");
    Matcher m = p.matcher(text);
    while (m.find()) {
      String inc = m.group(1);
      // "include 'a:b'" => ":b" subproject path; we translate
      // ":" to "/" so the discoverer can find build.gradle under
      // projectRoot/a/b. The leaf name is used as the module id.
      String rel = inc.replace(':', '/');
      if (!rel.isBlank()) result.add(rel);
    }
    return result;
  }

  /**
   * Resolve source directories for a {@code main} or {@code test} source set. Reads {@code
   * sourceSets.main.java.srcDirs} and the Kotlin variant. When the build doesn't override, falls
   * back to {@code src/main/java} / {@code src/main/kotlin} for main, and {@code src/test/java} /
   * {@code src/test/kotlin} for test.
   */
  private List<Path> resolveSourceDirs(Path moduleRoot, String sourceSet, String buildText) {
    // Try the explicit overrides first.
    List<Path> explicit = parseSrcDirs(buildText, sourceSet);
    if (!explicit.isEmpty()) {
      List<Path> out = new ArrayList<>();
      for (Path p : explicit) {
        if (!p.isAbsolute()) p = moduleRoot.resolve(p);
        if (Files.isDirectory(p)) {
          out.addAll(SourceFiles.walk(p, sourceExtensions()));
        }
      }
      if (!out.isEmpty()) return out;
    }
    // Defaults — Java first, then Kotlin, so a single-module project with both flavours
    // still gets its full source set picked up.
    List<Path> out = new ArrayList<>();
    for (String kind : new String[] {"java", "kotlin"}) {
      Path p = moduleRoot.resolve("src/" + sourceSet + "/" + kind);
      if (Files.isDirectory(p)) {
        out.addAll(SourceFiles.walk(p, sourceExtensions()));
      }
    }
    return out;
  }

  /**
   * Parse {@code sourceSets.main.java.srcDirs = ['src/main/java', 'gen']} (and Kotlin DSL / nested
   * variants). Returns the listed paths verbatim (caller is responsible for resolving against the
   * module root).
   */
  private static List<Path> parseSrcDirs(String buildText, String sourceSet) {
    List<Path> out = new ArrayList<>();
    // Block form first: sourceSets.main { java { srcDirs = [...] } }  OR
    // sourceSets["main"].java { srcDirs("src/main/java", "gen") }
    Pattern nested =
        Pattern.compile(
            "sourceSets?\\s*\\(?[\"']?"
                + Pattern.quote(sourceSet)
                + "[\"']?\\s*\\)?[^{]*\\{(.+?)\\}\\s*\\n",
            Pattern.DOTALL);
    Matcher nm = nested.matcher(buildText);
    while (nm.find()) {
      String body = nm.group(1);
      // srcDirs = [...]  OR  srcDirs("a","b")
      Matcher lm = Pattern.compile("srcDirs?\\s*=*\\s*([^\\n]+)").matcher(body);
      while (lm.find()) {
        out.addAll(parsePathList(lm.group(1)));
      }
    }
    // Inline form: sourceSets.main.java.srcDirs = ['src/main/java', 'gen']
    Pattern inline =
        Pattern.compile(
            "sourceSets?\\s*\\.?[\"']?"
                + Pattern.quote(sourceSet)
                + "[\"']?\\s*\\.?(?:java|kotlin)?\\s*\\.srcDirs?\\s*=*\\s*([^\\n]+)");
    Matcher im = inline.matcher(buildText);
    while (im.find()) {
      out.addAll(parsePathList(im.group(1)));
    }
    return out;
  }

  /** Parse {@code ['a', 'b']} or {@code ('a','b')} or {@code ['src/main/java', 'src/gen']}. */
  private static List<Path> parsePathList(String text) {
    List<Path> out = new ArrayList<>();
    Matcher sm = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(text);
    while (sm.find()) {
      String s = sm.group(1);
      if (!s.isBlank()) out.add(Path.of(s));
    }
    return out;
  }

  /** Detect {@code sourceCompatibility} / {@code jvmTarget} / {@code targetCompatibility}. */
  private static String detectLanguageLevel(String text) {
    Matcher m = SOURCE_COMPAT.matcher(text);
    while (m.find()) {
      String v = m.group(1).trim();
      Matcher jvm = JAVA_VERSION_CONST.matcher(v);
      if (jvm.find()) return jvm.group(1);
      if (v.matches("\\d+")) return v;
    }
    return null;
  }

  // ---------------- version catalog ----------------

  /**
   * Parse {@code gradle/libs.versions.toml} for {@code [libraries]} aliases. Each entry has the
   * form {@code alias-name = { module = "g:a", version.ref = "..." } } or {@code alias-name = {
   * module = "g:a", version = "x.y.z" } }. Returns a map from {@code libs.alias} to {@code g:a:v}.
   *
   * <p>This is a deliberately small parser: we don't pull in a TOML library (JDK has none). The
   * subset of TOML we consume is {@code name = { module = "g:a", version = "v" | version.ref =
   * "ref" }}; nested tables other than {@code [libraries]} are ignored.
   */
  static Map<String, String> parseVersionCatalog(Path projectRoot) {
    Path catalog = firstExisting(projectRoot, "gradle/libs.versions.toml");
    if (catalog == null) return Map.of();
    String text;
    try {
      text = Files.readString(catalog, StandardCharsets.UTF_8);
    } catch (IOException e) {
      LOG.debug("Gradle: failed to read {}: {}", catalog, e.getMessage());
      return Map.of();
    }
    // First, collect [versions] refs.
    Map<String, String> versions = new LinkedHashMap<>();
    Pattern verTbl =
        Pattern.compile("\\[versions\\](.+?)(?=\\n\\[|\\z)", Pattern.DOTALL);
    Matcher vm = verTbl.matcher(text);
    while (vm.find()) {
      Matcher entry = Pattern.compile("^(\\w[\\w-]*)\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE).matcher(vm.group(1));
      while (entry.find()) versions.put(entry.group(1), entry.group(2));
    }
    // Then, the [libraries] table.
    Map<String, String> libs = new LinkedHashMap<>();
    Pattern libTbl =
        Pattern.compile("\\[libraries\\](.+?)(?=\\n\\[|\\z)", Pattern.DOTALL);
    Matcher lm = libTbl.matcher(text);
    while (lm.find()) {
      Matcher entry =
          Pattern.compile(
                  "(\\w[\\w-]*)\\s*=\\s*\\{([^}]+)\\}",
                  Pattern.MULTILINE)
              .matcher(lm.group(1));
      while (entry.find()) {
        String alias = entry.group(1);
        String body = entry.group(2);
        String module = extractString(body, "module");
        String version = extractString(body, "version");
        if (module == null || version == null) continue;
        // version could be a literal or a .ref to [versions]
        if (version.startsWith(".")) {
          String ref = version.substring(1);
          version = versions.getOrDefault(ref, version);
        }
        libs.put("libs." + alias, module + ":" + version);
      }
    }
    return libs;
  }

  /** Extract {@code key = "value"} from a body string. Returns null if not found. */
  private static String extractString(String body, String key) {
    Matcher m =
        Pattern.compile(key + "\\s*=\\s*\"([^\"]+)\"").matcher(body);
    return m.find() ? m.group(1) : null;
  }

  // ---------------- dependency resolution ----------------

  /**
   * Parse the {@code dependencies { ... }} block(s) of a {@code build.gradle} file. We pick up
   * every Maven-style dependency (compile / runtime / provided / testImplementation, etc.) and
   * resolve {@code libs.xxx} references against the version catalog.
   */
  private List<Path> resolveClasspath(String buildText, Map<String, String> catalog) {
    LinkedHashSet<Path> result = new LinkedHashSet<>();
    // First, normalise the map-style dependency into "g:a:v" so the
    // SHORT_DEP regex can find it. This is a textual rewrite: we only
    // touch the inside of the dependencies block; we don't try to
    // interpret Gradle's full DSL.
    String normalised =
        NORMALISE_MAP_DEP
            .matcher(buildText)
            .replaceAll(m -> "'" + m.group(1) + ":" + m.group(2) + ":" + m.group(3) + "'");
    Matcher m = SHORT_DEP.matcher(normalised);
    while (m.find()) {
      String gid = m.group(1);
      String aid = m.group(2);
      String ver = m.group(3);
      Path jar = resolveArtifact(gid, aid, ver);
      if (jar != null) result.add(jar);
    }
    // Resolve libs.alias references from the version catalog.
    Matcher cm = CATALOG_REF.matcher(buildText);
    while (cm.find()) {
      String key = cm.group(0); // "libs.foo.bar"
      String resolved = catalog.get(key);
      if (resolved == null) continue;
      String[] parts = resolved.split(":");
      if (parts.length != 3) continue;
      Path jar = resolveArtifact(parts[0], parts[1], parts[2]);
      if (jar != null) result.add(jar);
    }
    return new ArrayList<>(result);
  }

  /**
   * Resolve {@code groupId:artifactId:version} against the local Maven repository. Returns null
   * when the jar is absent (caller may pass it via {@code --classpath}).
   */
  private Path resolveArtifact(String groupId, String artifactId, String version) {
    Path jar =
        localRepo
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactId + "-" + version + ".jar");
    if (Files.isRegularFile(jar)) return jar;
    LOG.warn("Gradle: could not resolve dependency {}:{}:{}", groupId, artifactId, version);
    return null;
  }

  // ---------------- safety ----------------

  /**
   * Resolve {@code rel} against {@code root} and verify the result is still under {@code root}
   * (no path traversal via {@code ../}). Returns null when the result escapes.
   */
  private static Path safeResolveInside(Path root, String rel) {
    if (rel == null || rel.isBlank()) return null;
    Path resolved = root.resolve(rel).normalize();
    Path rootNorm = root.toAbsolutePath().normalize();
    if (!resolved.toAbsolutePath().startsWith(rootNorm)) return null;
    return resolved;
  }

  // Suppress the unused-import warning for Locale (kept for future pluralization support).
  @SuppressWarnings("unused")
  private static final Locale UNUSED = Locale.ROOT;
}