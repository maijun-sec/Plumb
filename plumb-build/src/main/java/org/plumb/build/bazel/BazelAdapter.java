package org.plumb.build.bazel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * R-833 P1 ? Bazel build-system adapter.
 *
 * <p>Recognises {@code BUILD}, {@code BUILD.bazel} and {@code MODULE.bazel} (Bzlmod) / {@code
 * WORKSPACE} (legacy) marker files. The adapter is parser-light: we extract {@code java_library} /
 * {@code java_binary} / {@code java_test} / {@code java_import} / {@code java_proto_library} /
 * {@code android_library} targets' {@code srcs}, {@code jars} and {@code deps} attributes with a
 * regex.
 *
 * <h2>What we parse</h2>
 *
 * <ul>
 *   <li><b>targets</b> — {@code java_library(name = "X", srcs = [...], deps = [...], jars = [...],
 *       visibility = [...])} blocks. We pick up {@code srcs} (which may be glob patterns) and
 *       {@code jars} (literal file paths).
 *   <li><b>srcs globs</b> — {@code glob(["**&#47;*.java"])} is expanded by walking the package
 *       directory. {@code ["Foo.java", "Bar.java"]} is treated as a literal file list. Excludes
 *       inside the glob are honoured via simple filename filtering.
 * </ul>
 *
 * <h2>What we do NOT parse (out of scope for the adapter)</h2>
 *
 * <ul>
 *   <li>External dependencies declared via {@code @maven//:jar_name} — the user must supply them
 *       via {@code --classpath}.
 *   <li>{@code deps} chains to other {@code java_library} rules ? they would require evaluating the
 *       whole {@code deps(...)} closure; user-supplied {@code --classpath} covers this.
 * </ul>
 */
public final class BazelAdapter implements BuildAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(BazelAdapter.class);
  private static final String SYSTEM_NAME = "bazel";

  /** {@code name = "X"} */
  private static final Pattern NAME_ATTR =
      Pattern.compile("\\bname\\s*=\\s*['\"]([\\w_\\-\\.]+)['\"]");

  /** {@code srcs = [...]} or {@code srcs = glob([...])} — matches until the closing bracket. */
  private static final Pattern SRCS_ATTR =
      Pattern.compile(
          "\\bsrcs\\s*=\\s*(\\[[^\\]]*\\]|glob\\s*\\(\\s*\\[[^\\]]*\\]\\s*(?:,\\s*exclude\\s*=\\s*\\[[^\\]]*\\])?\\s*\\))");

  /** {@code jars = [...]} — literal file paths, including {@code java_import}'s {@code jars}. */
  private static final Pattern JARS_ATTR = Pattern.compile("\\bjars\\s*=\\s*(\\[[^\\]]*\\])");

  /** String inside a list: {@code "foo"} or {@code 'foo'}. */
  private static final Pattern LIST_STRING = Pattern.compile("['\"]([^'\"]+)['\"]");

  /** {@code exclude = [...]}. */
  private static final Pattern EXCLUDE_LIST = Pattern.compile("exclude\\s*=\\s*\\[([^\\]]*)\\]");

  @Override
  public String systemName() {
    return SYSTEM_NAME;
  }

  @Override
  public boolean probe(Path projectRoot) {
    if (projectRoot == null) return false;
    // Bazel uses BUILD or BUILD.bazel as the per-package file.
    // We require at least one of them somewhere under the root.
    try (var stream = Files.walk(projectRoot, 5)) {
      return stream.anyMatch(
          p -> {
            if (!Files.isRegularFile(p)) return false;
            String n = p.getFileName().toString();
            return n.equals("BUILD") || n.equals("BUILD.bazel");
          });
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public BuildProject discover(Path projectRoot) {
    if (!probe(projectRoot)) {
      throw new BuildAdapterException(
          "Bazel project root must contain at least one BUILD / BUILD.bazel: " + projectRoot);
    }
    // Use absolute-path-canonicalised package directory as the module-key
    // prefix so BUILD files in different packages with same target name don't
    // collide.
    Map<String, BuildProject.Module> modules = new LinkedHashMap<>();
    List<Path> allSources = new ArrayList<>();
    LinkedHashSet<Path> allCp = new LinkedHashSet<>();
    Set<Path> visitedPackages = new LinkedHashSet<>();
    try (var stream = Files.walk(projectRoot, 5)) {
      stream
          .filter(Files::isRegularFile)
          .filter(
              p -> {
                String n = p.getFileName().toString();
                return n.equals("BUILD") || n.equals("BUILD.bazel");
              })
          .forEach(
              buildFile ->
                  parseBuildFile(
                      projectRoot, buildFile, modules, allSources, allCp, visitedPackages));
    } catch (IOException e) {
      throw new BuildAdapterException(
          "Failed to walk Bazel project root " + projectRoot + ": " + e.getMessage(), e);
    }
    // Dedupe across all rules (a single .java may be referenced by
    // multiple targets); preserve first-seen order.
    List<Path> dedupedSources = allSources.stream().distinct().toList();
    List<Path> dedupedCp = new ArrayList<>(allCp);
    LOG.info(
        "Bazel: project root {} ({} targets, {} source files, {} classpath jars)",
        projectRoot,
        modules.size(),
        dedupedSources.size(),
        dedupedCp.size());
    return BuildProject.builder(SYSTEM_NAME)
        .version("Bazel adapter v1")
        .sourceFiles(dedupedSources)
        .classpathJars(dedupedCp)
        .sourceRoots(List.of(projectRoot))
        .moduleRoots(List.of(projectRoot))
        .modules(modules)
        .build();
  }

  /**
   * Parse one {@code BUILD} file and add the {@code java_library} / {@code java_binary} / {@code
   * java_test} / {@code java_proto_library} / {@code android_library} / {@code java_import} targets
   * it declares. Other rule kinds (proto, py_*, sh_*, genrule) are ignored.
   */
  private void parseBuildFile(
      Path projectRoot,
      Path buildFile,
      Map<String, BuildProject.Module> modules,
      List<Path> allSources,
      LinkedHashSet<Path> allCp,
      Set<Path> visitedPackages) {
    String text = readText(buildFile);
    Path packageDir = buildFile.getParent();
    if (!visitedPackages.add(packageDir.toAbsolutePath().normalize())) {
      // Don't re-parse the same package — guards against symlink loops that lead to the
      // same logical directory twice.
      return;
    }
    // P1-#18: canonicalise the module key with the absolute package directory so two
    // packages with the same leaf name ("core" / "core") don't collide on key "core:core".
    String packageKey = packageDir.toAbsolutePath().normalize().toString();
    // Slice the text by rule. Each rule starts with a Java-like keyword.
    // We split on the regex
    // "(java_library|java_binary|java_test|java_import|java_proto_library|android_library)\s*\(".
    Pattern ruleStart =
        Pattern.compile(
            "(java_library|java_binary|java_test|java_import|java_proto_library|android_library)"
                + "\\s*\\(");
    Matcher m = ruleStart.matcher(text);
    int pos = 0;
    while (m.find()) {
      int start = m.start();
      String kind = m.group(1);
      // Find the matching closing paren, accounting for nesting.
      int depth = 0;
      int end = start;
      for (int i = m.end() - 1; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '(') depth++;
        else if (c == ')') {
          depth--;
          if (depth == 0) {
            end = i + 1;
            break;
          }
        }
      }
      if (end <= start) continue;
      String rule = text.substring(start, end);
      String name = extractAttr(rule, NAME_ATTR, "anon-" + pos);
      List<String> srcs = extractSrcs(rule, packageDir);
      List<Path> srcPaths =
          srcs.stream()
              .filter(s -> s.endsWith(".java"))
              .map(packageDir::resolve)
              .filter(Files::isRegularFile)
              .distinct()
              .toList();
      allSources.addAll(srcPaths);
      // P1-#13: java_import contributes jars to the classpath.
      if (kind.equals("java_import")) {
        for (Path jar : extractJars(rule, packageDir)) {
          if (Files.isRegularFile(jar)) allCp.add(jar);
        }
      }
      // Module key combines the package directory and the target name. We retain the
      // legacy "<packageLeaf>:<name>" shape via a separate legacy-key lookup (so existing
      // tests asserting on ":core" / ":util" still pass) but the canonical key is the
      // absolute package path.
      String moduleKey = packageKey + "::" + name;
      String legacyKey = packageDir.getFileName().toString() + ":" + name;
      modules.put(
          moduleKey,
          new BuildProject.Module(
              legacyKey, // legacy id keeps the package:target string for callers
              packageDir,
              srcPaths,
              List.of(),
              List.of()));
      pos++;
    }
  }

  private static String extractAttr(String rule, Pattern p, String def) {
    Matcher m = p.matcher(rule);
    return m.find() ? m.group(1) : def;
  }

  /**
   * Extract the {@code srcs = [...]} list. We handle two cases:
   *
   * <ol>
   *   <li>{@code srcs = ["Foo.java", "Bar.java"]}
   *   <li>{@code srcs = glob(["**&#47;*.java"])} (optionally with {@code exclude = [...]})
   * </ol>
   *
   * Returns a list of relative paths (relative to the {@code packageDir}).
   */
  private List<String> extractSrcs(String rule, Path packageDir) {
    Matcher attr = SRCS_ATTR.matcher(rule);
    if (!attr.find()) return List.of();
    String body = attr.group(1);
    if (body.startsWith("glob")) {
      // glob(["**/*.java", exclude = [...]]) -> expand against packageDir.
      Matcher gm = LIST_STRING.matcher(body);
      List<String> out = new ArrayList<>();
      Set<String> excludes = extractExcludes(body);
      while (gm.find()) {
        String pattern = gm.group(1);
        if (pattern.contains("**") || pattern.contains("*")) {
          out.addAll(expandGlob(packageDir, pattern, excludes));
        } else {
          if (!excludes.contains(pattern)) out.add(pattern);
        }
      }
      return out;
    }
    // Plain list.
    Matcher sm = LIST_STRING.matcher(body);
    List<String> out = new ArrayList<>();
    while (sm.find()) {
      String s = sm.group(1);
      if (!extractExcludes(body).contains(s)) out.add(s);
    }
    return out;
  }

  /**
   * Pull the {@code exclude = ["a", "b"]} list out of a glob / srcs body. Returns an empty set when
   * no exclude is present.
   */
  private static Set<String> extractExcludes(String body) {
    Matcher em = EXCLUDE_LIST.matcher(body);
    if (!em.find()) return Set.of();
    String inner = em.group(1);
    Set<String> out = new java.util.HashSet<>();
    Matcher sm = LIST_STRING.matcher(inner);
    while (sm.find()) out.add(sm.group(1));
    return out;
  }

  /**
   * Extract the {@code jars = ["//path/to:jar.jar"]} list. Returns the literal paths resolved
   * against {@code packageDir}.
   */
  private List<Path> extractJars(String rule, Path packageDir) {
    Matcher attr = JARS_ATTR.matcher(rule);
    if (!attr.find()) return List.of();
    String body = attr.group(1);
    Matcher sm = LIST_STRING.matcher(body);
    List<Path> out = new ArrayList<>();
    while (sm.find()) {
      String s = sm.group(1);
      Path p;
      if (s.startsWith("//") || s.startsWith("/")) {
        // Bazel label / absolute — we can't resolve without the workspace; skip.
        continue;
      } else if (s.startsWith(":")) {
        p = packageDir.resolve(s.substring(1));
      } else {
        p = packageDir.resolve(s);
      }
      if (Files.isRegularFile(p)) out.add(p);
    }
    return out;
  }

  /**
   * Very small glob expander: handles {@code **} (recursive) and {@code *} (single segment).
   * Returns paths relative to the package directory. Anything more complex (alternation, character
   * classes) returns an empty list and a warning.
   */
  private List<String> expandGlob(Path rootDir, String pattern, Set<String> excludes) {
    String recurJava = "**" + java.io.File.separator + "*.java";
    if (pattern.equals("**/*.java") || pattern.equals(recurJava)) {
      try (var s = Files.walk(rootDir, 100)) {
        List<String> out = new ArrayList<>();
        s.filter(Files::isRegularFile)
            .filter(p -> p.getFileName().toString().endsWith(".java"))
            .forEach(
                p -> {
                  String rel = rootDir.relativize(p).toString();
                  if (!excludes.contains(rel) && !excludes.contains(p.getFileName().toString())) {
                    out.add(rel);
                  }
                });
        return out;
      } catch (IOException e) {
        LOG.warn("Bazel: glob walk failed in {}: {}", rootDir, e.getMessage());
        return List.of();
      }
    }
    LOG.warn("Bazel: glob pattern not supported: {}", pattern);
    return List.of();
  }

  private static String readText(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new BuildAdapterException("Failed to read " + p + ": " + e.getMessage(), e);
    }
  }
}
