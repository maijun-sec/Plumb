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

    public ModuleDescriptor build() {
      return new ModuleDescriptor(this);
    }
  }
}
