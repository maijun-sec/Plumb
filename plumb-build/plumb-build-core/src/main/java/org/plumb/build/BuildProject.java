package org.plumb.build;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Uniform project model produced by every {@link BuildAdapter}.
 *
 * <p>One {@code BuildProject} corresponds to the result of resolving one {@code projectRoot} (which
 * may be a multi-module Maven/Gradle/Bazel tree). The fields carry enough information to feed
 * {@code JavaSourceParser}'s {@code TypeSolver}:
 *
 * <ul>
 *   <li>{@link #sourceFiles} -- every {@code .java} source that the user wrote (NOT generated, NOT
 *       test, by default). One entry per file.
 *   <li>{@link #testSourceFiles} -- same, but under {@code src/test/java}. Optional: the caller
 *       decides whether to scan these.
 *   <li>{@link #classpathJars} -- third-party dependency jars (and exploded class folders) that the
 *       user source compiles against. These feed the project's TypeSolver but never the TAC
 *       pipeline. See the {@code plumb-main} --classpath semantics.
 *   <li>{@link #sourceRoots} -- root directories the user source lives under. May be repeated for
 *       multi-module projects; each entry becomes one {@code --source-root} on the {@code SastMain}
 *       CLI.
 *   <li>{@link #moduleRoots} -- same as source roots, but with module semantics. Empty for a
 *       single-module project.
 *   <li>{@link #modules} -- sub-modules keyed by module id (Maven {@code <artifactId>}, Gradle
 *       project path, Bazel label suffix, Ant target name). Each value is the sub-module's own
 *       source / classpath so the caller can scope scans per module.
 *   <li>{@link #systemName} -- the build-system id, for logging.
 *   <li>{@link #version} -- the build-system version we resolved against (e.g. {@code "Maven
 *       3.9.9"}, {@code "Gradle 8.7"}). {@code null} when the adapter could not determine the
 *       version.
 *   <li>{@link #languageLevel} -- the Java source level declared by the project (e.g. {@code "8"},
 *       {@code "11"}, {@code "17"}). May be {@code null} when the adapter could not determine the
 *       level -- the parser then falls back to its default.
 *   <li>{@link #sourceEncoding} -- the source-file charset declared by the project (typical {@code
 *       UTF-8}, occasionally {@code ISO-8859-1} on legacy European codebases). May be {@code null}
 *       when the adapter could not determine the encoding -- the parser falls back to UTF-8.
 * </ul>
 */
public final class BuildProject {

  /**
   * Module scope -- controls whether the module contributes its source to the main scan and which
   * classpath is consulted. Maven/Gradle models expose {@code compile}, {@code test}, {@code
   * runtime}; we surface them so downstream code can filter appropriately.
   */
  public enum Scope {
    COMPILE,
    TEST,
    RUNTIME,
    PROVIDED,
    SYSTEM;

    /**
     * Lenient parser -- accepts the canonical Maven scopes, plus the Gradle aliases {@code
     * implementation} / {@code api} / {@code testImplementation} / {@code runtimeOnly} / {@code
     * compileOnly}. Anything unknown is mapped to {@link #COMPILE} (matches the legacy behaviour
     * where unscoped deps contributed to compile).
     */
    public static Scope fromString(String s) {
      if (s == null || s.isBlank()) return COMPILE;
      String lower = s.trim().toLowerCase();
      switch (lower) {
        case "compile":
        case "implementation":
        case "api":
        case "classpath":
          return COMPILE;
        case "test":
        case "testimplementation":
        case "testcompile":
          return TEST;
        case "runtime":
        case "runtimeonly":
          return RUNTIME;
        case "provided":
        case "compileonly":
          return PROVIDED;
        case "system":
          return SYSTEM;
        default:
          return COMPILE;
      }
    }

    /** Maven / Gradle canonical spelling used by adapters. */
    public String canonical() {
      switch (this) {
        case COMPILE:
          return "compile";
        case TEST:
          return "test";
        case RUNTIME:
          return "runtime";
        case PROVIDED:
          return "provided";
        case SYSTEM:
          return "system";
      }
      return "compile";
    }
  }

  private final String systemName;
  private final String version;
  private final List<Path> sourceFiles;
  private final List<Path> testSourceFiles;
  private final List<Path> classpathJars;
  private final List<Path> sourceRoots;
  private final List<Path> moduleRoots;
  private final Map<String, Module> modules;
  private final String languageLevel;
  private final Charset sourceEncoding;

  private BuildProject(Builder b) {
    this.systemName = Objects.requireNonNull(b.systemName, "systemName");
    this.version = b.version;
    this.sourceFiles = List.copyOf(b.sourceFiles);
    this.testSourceFiles = List.copyOf(b.testSourceFiles);
    this.classpathJars = List.copyOf(b.classpathJars);
    this.sourceRoots = List.copyOf(b.sourceRoots);
    this.moduleRoots = List.copyOf(b.moduleRoots);
    this.modules = Map.copyOf(b.modules);
    this.languageLevel = b.languageLevel;
    this.sourceEncoding = b.sourceEncoding;
  }

  public String systemName() {
    return systemName;
  }

  public String version() {
    return version;
  }

  public List<Path> sourceFiles() {
    return sourceFiles;
  }

  public List<Path> testSourceFiles() {
    return testSourceFiles;
  }

  public List<Path> classpathJars() {
    return classpathJars;
  }

  public List<Path> sourceRoots() {
    return sourceRoots;
  }

  public List<Path> moduleRoots() {
    return moduleRoots;
  }

  public Map<String, Module> modules() {
    return modules;
  }

  /** Java source level ({@code "8"} / {@code "11"} / ...) or {@code null} if unknown. */
  public String languageLevel() {
    return languageLevel;
  }

  /** Source-file charset or {@code null} if unknown (parser falls back to UTF-8). */
  public Charset sourceEncoding() {
    return sourceEncoding;
  }

  /**
   * One sub-module inside a {@link BuildProject}. Multi-module projects have one entry per leaf
   * module; single-module projects may have one entry for the only module, or none if the caller
   * treats the project as a flat tree.
   *
   * <p>The legacy 5-arg constructor is preserved for backwards compatibility with existing
   * adapters; new code should use the full constructor that carries {@link Scope} / {@link
   * #languageLevel} / {@link #sourceEncoding}.
   */
  public static final class Module {
    private final String id;
    private final Path sourceRoot;
    private final List<Path> sources;
    private final List<Path> testSources;
    private final List<Path> classpathJars;
    private final Scope scope;
    private final String languageLevel;
    private final Charset sourceEncoding;

    public Module(
        String id,
        Path sourceRoot,
        List<Path> sources,
        List<Path> testSources,
        List<Path> classpathJars) {
      this(id, sourceRoot, sources, testSources, classpathJars, Scope.COMPILE, null, null);
    }

    public Module(
        String id,
        Path sourceRoot,
        List<Path> sources,
        List<Path> testSources,
        List<Path> classpathJars,
        Scope scope,
        String languageLevel,
        Charset sourceEncoding) {
      this.id = Objects.requireNonNull(id);
      this.sourceRoot = sourceRoot;
      this.sources = List.copyOf(sources);
      this.testSources = List.copyOf(testSources);
      this.classpathJars = List.copyOf(classpathJars);
      this.scope = scope == null ? Scope.COMPILE : scope;
      this.languageLevel = languageLevel;
      this.sourceEncoding = sourceEncoding;
    }

    public String id() {
      return id;
    }

    public Path sourceRoot() {
      return sourceRoot;
    }

    public List<Path> sources() {
      return sources;
    }

    public List<Path> testSources() {
      return testSources;
    }

    public List<Path> classpathJars() {
      return classpathJars;
    }

    public Scope scope() {
      return scope;
    }

    public String languageLevel() {
      return languageLevel;
    }

    public Charset sourceEncoding() {
      return sourceEncoding;
    }
  }

  public static Builder builder(String systemName) {
    return new Builder(systemName);
  }

  public static final class Builder {
    private final String systemName;
    private String version;
    private List<Path> sourceFiles = List.of();
    private List<Path> testSourceFiles = List.of();
    private List<Path> classpathJars = List.of();
    private List<Path> sourceRoots = List.of();
    private List<Path> moduleRoots = List.of();
    private Map<String, Module> modules = Map.of();
    private String languageLevel;
    private Charset sourceEncoding;

    private Builder(String systemName) {
      this.systemName = systemName;
    }

    public Builder version(String v) {
      this.version = v;
      return this;
    }

    public Builder sourceFiles(List<Path> l) {
      this.sourceFiles = l;
      return this;
    }

    public Builder testSourceFiles(List<Path> l) {
      this.testSourceFiles = l;
      return this;
    }

    public Builder classpathJars(List<Path> l) {
      this.classpathJars = l;
      return this;
    }

    public Builder sourceRoots(List<Path> l) {
      this.sourceRoots = l;
      return this;
    }

    public Builder moduleRoots(List<Path> l) {
      this.moduleRoots = l;
      return this;
    }

    public Builder modules(Map<String, Module> m) {
      this.modules = m;
      return this;
    }

    public Builder languageLevel(String v) {
      this.languageLevel = v;
      return this;
    }

    public Builder sourceEncoding(Charset v) {
      this.sourceEncoding = v;
      return this;
    }

    public BuildProject build() {
      return new BuildProject(this);
    }
  }
}
