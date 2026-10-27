package org.plumb.build.maven;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The compile-time facts about one Maven module, as observed either statically
 * (from {@code pom.xml}) or dynamically (from a captured javac command line).
 *
 * <p>This is the portable shape of what the original {@code
 * maven-project-inspect} called a {@code JavaOptionBean}: the source roots,
 * the output directories, the dependency jars, the language level and the
 * encoding.
 */
public final class ModuleDescriptor {

  private final String id;
  private final Set<Path> sourceRoots;
  private final Path testSourceRoot;
  private final Path outputDirectory;
  private final Path testOutputDirectory;
  private final List<Path> classpath;
  private final List<String> dependencyCoordinates;
  private final String languageLevel;
  private final Charset encoding;
  private final List<String> compilerArgs;
  private final List<String> annotationProcessorPaths;

  private ModuleDescriptor(Builder b) {
    this.id = b.id;
    this.sourceRoots = Set.copyOf(b.sourceRoots);
    this.testSourceRoot = b.testSourceRoot;
    this.outputDirectory = b.outputDirectory;
    this.testOutputDirectory = b.testOutputDirectory;
    this.classpath = List.copyOf(b.classpath);
    this.dependencyCoordinates = List.copyOf(b.dependencyCoordinates);
    this.languageLevel = b.languageLevel;
    this.encoding = b.encoding;
    this.compilerArgs = List.copyOf(b.compilerArgs);
    this.annotationProcessorPaths = List.copyOf(b.annotationProcessorPaths);
  }

  public String id() {
    return id;
  }

  public Set<Path> sourceRoots() {
    return sourceRoots;
  }

  public Path testSourceRoot() {
    return testSourceRoot;
  }

  public Path outputDirectory() {
    return outputDirectory;
  }

  public Path testOutputDirectory() {
    return testOutputDirectory;
  }

  public List<Path> classpath() {
    return classpath;
  }

  /** The coordinates this module declares as direct dependencies. */
  public List<String> dependencyCoordinates() {
    return dependencyCoordinates;
  }

  public String languageLevel() {
    return languageLevel;
  }

  public Charset encoding() {
    return encoding;
  }

  /**
   * The extra arguments the compiler plugin passes to the compiler.
   *
   * <p>Empty when the pom declares none. The arguments are the plugin's {@code <compilerArgs>},
   * passed to the compiler verbatim.
   */
  public List<String> compilerArgs() {
    return compilerArgs;
  }

  /**
   * The annotation processor paths the compiler plugin declares.
   *
   * <p>Empty when the pom declares none. Each entry is a {@code groupId:artifactId:version}
   * coordinate: the pom declares a dependency, and turning it into a jar is the resolver's job.
   */
  public List<String> annotationProcessorPaths() {
    return annotationProcessorPaths;
  }

  public static Builder builder(String id) {
    return new Builder(id);
  }

  /** Builder for {@link ModuleDescriptor}. */
  public static final class Builder {
    private final String id;
    private Set<Path> sourceRoots = Set.of();
    private Path testSourceRoot;
    private Path outputDirectory;
    private Path testOutputDirectory;
    private List<Path> classpath = List.of();
    private List<String> dependencyCoordinates = List.of();
    private String languageLevel;
    private Charset encoding;
    private List<String> compilerArgs = List.of();
    private List<String> annotationProcessorPaths = List.of();

    private Builder(String id) {
      this.id = id;
    }

    public Builder sourceRoots(Set<Path> v) {
      this.sourceRoots = new LinkedHashSet<>(v);
      return this;
    }

    public Builder testSourceRoot(Path v) {
      this.testSourceRoot = v;
      return this;
    }

    public Builder outputDirectory(Path v) {
      this.outputDirectory = v;
      return this;
    }

    public Builder testOutputDirectory(Path v) {
      this.testOutputDirectory = v;
      return this;
    }

    public Builder classpath(List<Path> v) {
      this.classpath = v;
      return this;
    }

    public Builder dependencyCoordinates(List<String> v) {
      this.dependencyCoordinates = v;
      return this;
    }

    public Builder languageLevel(String v) {
      this.languageLevel = v;
      return this;
    }

    public Builder encoding(Charset v) {
      this.encoding = v;
      return this;
    }

    public Builder compilerArgs(List<String> v) {
      this.compilerArgs = v == null ? List.of() : new java.util.ArrayList<>(v);
      return this;
    }

    public Builder annotationProcessorPaths(List<String> v) {
      this.annotationProcessorPaths = v == null ? List.of() : new java.util.ArrayList<>(v);
      return this;
    }

    public ModuleDescriptor build() {
      return new ModuleDescriptor(this);
    }
  }
}
