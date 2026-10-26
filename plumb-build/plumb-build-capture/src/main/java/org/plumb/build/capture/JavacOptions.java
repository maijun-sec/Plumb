package org.plumb.build.capture;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The compile options recovered from a captured javac command line.
 *
 * <p>This carries the same information the original {@code java-capture} project
 * recorded in its {@code JavaOption} bean: the source encoding, the language
 * level, the classpath, the source path, the output directory and the source
 * files.
 */
public final class JavacOptions {

  /** javac {@code -d}. */
  private static final String OPT_D = "-d";

  /** javac {@code -classpath}. */
  private static final String OPT_CLASSPATH = "-classpath";

  /** javac {@code -cp} -- a synonym for {@code -classpath}. */
  private static final String OPT_CP = "-cp";

  /** javac {@code -encoding}. */
  private static final String OPT_ENCODING = "-encoding";

  /** javac {@code -source}. */
  private static final String OPT_SOURCE = "-source";

  /** javac {@code -target}. */
  private static final String OPT_TARGET = "-target";

  /** javac {@code --release}. */
  private static final String OPT_RELEASE = "--release";

  /** javac {@code -sourcepath}. */
  private static final String OPT_SOURCEPATH = "-sourcepath";

  /** javac {@code -bootclasspath}. */
  private static final String OPT_BOOTCLASSPATH = "-bootclasspath";

  /** javac {@code -extdirs}. */
  private static final String OPT_EXTDIRS = "-extdirs";

  /** javac {@code --module-path}. */
  private static final String OPT_MODULEPATH = "--module-path";

  /** javac {@code -p}. */
  private static final String OPT_MODULE_SOURCE_PATH = "-p";

  private final List<String> rawArguments;
  private final Charset encoding;
  private final String source;
  private final String target;
  private final List<Path> classpath;
  private final List<Path> sourcePath;
  private final List<Path> modulePath;
  private final Path outputDirectory;
  private final List<Path> sourceFiles;

  private JavacOptions(Builder b) {
    this.rawArguments = List.copyOf(b.rawArguments);
    this.encoding = b.encoding;
    this.source = b.source;
    this.target = b.target;
    this.classpath = List.copyOf(b.classpath);
    this.sourcePath = List.copyOf(b.sourcePath);
    this.modulePath = List.copyOf(b.modulePath);
    this.outputDirectory = b.outputDirectory;
    this.sourceFiles = List.copyOf(b.sourceFiles);
  }

  /** The raw argv, exactly as javac received it. */
  public List<String> rawArguments() {
    return rawArguments;
  }

  public Charset encoding() {
    return encoding;
  }

  /** The {@code -source} level, or {@code null} when javac was not told. */
  public String source() {
    return source;
  }

  /** The {@code -target} level, or {@code null} when javac was not told. */
  public String target() {
    return target;
  }

  public List<Path> classpath() {
    return classpath;
  }

  public List<Path> sourcePath() {
    return sourcePath;
  }

  public List<Path> modulePath() {
    return modulePath;
  }

  /** The {@code -d} output directory, or {@code null}. */
  public Path outputDirectory() {
    return outputDirectory;
  }

  public List<Path> sourceFiles() {
    return sourceFiles;
  }

  /**
   * The language level to analyse at.
   *
   * <p>{@code --release} wins over {@code -source}, because it pins both the
   * language level and the platform API. Falls back to {@code -source}, then
   * to {@code -target}.
   */
  public String languageLevel() {
    if (source != null) {
      return source;
    }
    return target;
  }

  /**
   * Parse a captured javac argv into structured options.
   *
   * @param arguments the argv javac received
   * @return the parsed options
   */
  public static JavacOptions parse(List<String> arguments) {
    Builder b = new Builder();
    if (arguments == null || arguments.isEmpty()) {
      return b.build();
    }
    b.rawArguments = arguments;

    for (int i = 0; i < arguments.size(); i++) {
      String arg = arguments.get(i);
      if (arg == null) {
        continue;
      }
      switch (arg) {
        case OPT_D -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.outputDirectory = Path.of(v);
          }
        }
        case OPT_ENCODING -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.encoding = charset(v);
          }
        }
        case OPT_SOURCE -> {
          b.source = valueAt(arguments, ++i);
        }
        case OPT_TARGET -> {
          b.target = valueAt(arguments, ++i);
        }
        case OPT_RELEASE -> {
          b.source = valueAt(arguments, ++i);
          b.target = b.source;
        }
        case OPT_CLASSPATH, OPT_CP -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.classpath.addAll(splitPath(v));
          }
        }
        case OPT_SOURCEPATH -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.sourcePath.addAll(splitPath(v));
          }
        }
        case OPT_MODULEPATH -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.modulePath.addAll(splitPath(v));
          }
        }
        case OPT_MODULE_SOURCE_PATH -> {
          String v = valueAt(arguments, ++i);
          if (v != null) {
            b.sourcePath.addAll(splitPath(v));
          }
        }
        case OPT_BOOTCLASSPATH, OPT_EXTDIRS -> {
          // Recorded in the raw argv but not modelled separately.
          i++;
        }
        default -> {
          if (arg.endsWith(".java")) {
            b.sourceFiles.add(Path.of(arg));
          }
        }
      }
    }
    return b.build();
  }

  private static String valueAt(List<String> args, int index) {
    return index < args.size() ? args.get(index) : null;
  }

  /**
   * Split a classpath string into entries.
   *
   * <p>Both {@code :} and {@code ;} are accepted as separators, because the
   * separator is platform-dependent and a capture taken on one machine may be
   * analysed on another.
   */
  private static List<Path> splitPath(String value) {
    List<Path> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split("[:;]")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(Path.of(trimmed));
      }
    }
    return out;
  }

  private static Charset charset(String name) {
    try {
      return Charset.forName(name);
    } catch (Exception e) {
      return StandardCharsets.UTF_8;
    }
  }

  /** Builder for {@link JavacOptions}. */
  private static final class Builder {
    List<String> rawArguments = List.of();
    Charset encoding = StandardCharsets.UTF_8;
    String source;
    String target;
    List<Path> classpath = new ArrayList<>();
    List<Path> sourcePath = new ArrayList<>();
    List<Path> modulePath = new ArrayList<>();
    Path outputDirectory;
    Set<Path> sourceFiles = new LinkedHashSet<>();

    JavacOptions build() {
      return new JavacOptions(this);
    }
  }
}
