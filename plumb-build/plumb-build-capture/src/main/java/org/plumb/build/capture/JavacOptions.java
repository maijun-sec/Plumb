package org.plumb.build.capture;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The compile options recovered from a captured javac command line.
 *
 * <p>This carries the same information the original {@code java-capture} project recorded in its
 * {@code JavaOption} bean: the source encoding, the language level, the classpath, the source
 * path, the output directory and the source files.
 *
 * <p><b>The option knowledge lives in {@link CompilerOption}.</b> This class only walks the argv
 * and applies the table. That matters because the previous implementation had a dozen hard-coded
 * option names in a switch, and everything it did not know fell through to a default branch that
 * treated any argument ending in {@code .java} as a source file. So {@code --processor-path
 * some/dir.jar} was silently dropped, and an unknown option whose value ended in {@code .java}
 * was mistaken for a source file.
 */
public final class JavacOptions {

  private final List<String> rawArguments;
  private final Charset encoding;
  private final String encodingName;
  private final String source;
  private final String target;
  private final String release;
  private final List<Path> classpath;
  private final List<Path> sourcePath;
  private final List<Path> modulePath;
  private final List<Path> moduleSourcePath;
  private final List<Path> processorPath;
  private final List<Path> bootClassPath;
  private final Path outputDirectory;
  private final Path annotationProcessingDirectory;
  private final List<Path> sourceFiles;
  private final List<String> addModules;
  private final List<String> addExports;
  private final List<String> addReads;
  private final boolean preview;
  private final boolean compilation;

  private JavacOptions(Builder b) {
    this.rawArguments = List.copyOf(b.rawArguments);
    this.encoding = b.encoding;
    this.encodingName = b.encodingName;
    this.source = b.source;
    this.target = b.target;
    this.release = b.release;
    this.classpath = List.copyOf(b.classpath);
    this.sourcePath = List.copyOf(b.sourcePath);
    this.modulePath = List.copyOf(b.modulePath);
    this.moduleSourcePath = List.copyOf(b.moduleSourcePath);
    this.processorPath = List.copyOf(b.processorPath);
    this.bootClassPath = List.copyOf(b.bootClassPath);
    this.outputDirectory = b.outputDirectory;
    this.annotationProcessingDirectory = b.annotationProcessingDirectory;
    this.sourceFiles = List.copyOf(b.sourceFiles);
    this.addModules = List.copyOf(b.addModules);
    this.addExports = List.copyOf(b.addExports);
    this.addReads = List.copyOf(b.addReads);
    this.preview = b.preview;
    this.compilation = b.compilation;
  }

  /** The raw argv, exactly as javac received it. */
  public List<String> rawArguments() {
    return rawArguments;
  }

  public Charset encoding() {
    return encoding;
  }

  /**
   * The encoding as javac was told, e.g. {@code UTF-8}.
   *
   * <p>Null when the command line did not say, which means the platform default applied.
   */
  public String encodingName() {
    return encodingName;
  }

  /** The {@code -source} level, or {@code null} when javac was not told. */
  public String source() {
    return source;
  }

  /** The {@code -target} level, or {@code null} when javac was not told. */
  public String target() {
    return target;
  }

  /** The {@code --release} level, or {@code null} when javac was not told. */
  public String release() {
    return release;
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

  /** The {@code --module-source-path}, or empty. */
  public List<Path> moduleSourcePath() {
    return moduleSourcePath;
  }

  /** The {@code --processor-path}, or empty. */
  public List<Path> processorPath() {
    return processorPath;
  }

  /** The boot class path, or empty. */
  public List<Path> bootClassPath() {
    return bootClassPath;
  }

  /** The {@code -d} output directory, or {@code null}. */
  public Path outputDirectory() {
    return outputDirectory;
  }

  /** The {@code -s} annotation processing directory, or {@code null}. */
  public Path annotationProcessingDirectory() {
    return annotationProcessingDirectory;
  }

  public List<Path> sourceFiles() {
    return sourceFiles;
  }

  /** The {@code --add-modules} entries, in order. */
  public List<String> addModules() {
    return addModules;
  }

  /** The {@code --add-exports} entries, in order. */
  public List<String> addExports() {
    return addExports;
  }

  /** The {@code --add-reads} entries, in order. */
  public List<String> addReads() {
    return addReads;
  }

  /** Whether {@code --enable-preview} was given. */
  public boolean preview() {
    return preview;
  }

  /**
   * Whether this command line actually produced class files.
   *
   * <p>False for {@code -Xprint} and {@code -proc:only}: the first prints the compiler's internal
   * state, the second runs only the annotation processors. Neither writes a class file, so a
   * capture that counted them as compiles would report a compilation that never happened.
   */
  public boolean isCompilation() {
    return compilation;
  }

  /**
   * The language level to analyse at.
   *
   * <p>{@code --release} wins over {@code -source}, because it pins both the language level and the
   * platform API. Falls back to {@code -source}, then to {@code -target}.
   */
  public String languageLevel() {
    if (release != null) {
      return release;
    }
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
    return parse(arguments, CompilerOption.Compiler.JAVAC);
  }

  /**
   * Parse a captured argv into structured options, for a given compiler.
   *
   * <p>The compiler matters because the options differ: ECJ spells the classpath {@code
   * --class-path}, and sjavac has its own set. Parsing ECJ's arguments with javac's table would
   * drop the classpath.
   *
   * @param arguments the argv the compiler received
   * @param compiler which compiler received it
   * @return the parsed options
   */
  public static JavacOptions parse(List<String> arguments, CompilerOption.Compiler compiler) {
    Builder b = new Builder();
    if (arguments == null || arguments.isEmpty()) {
      return b.build();
    }
    b.rawArguments = arguments;
    arguments = expandArgumentFiles(arguments);

    // The values seen per canonical option name, so that "only the last one counts" and "every
    // occurrence applies" can be applied without hard-coding either.
    Map<String, List<String>> values = new LinkedHashMap<>();

    for (int i = 0; i < arguments.size(); i++) {
      String arg = arguments.get(i);
      if (arg == null || arg.isEmpty()) {
        continue;
      }

      // An option and its value may be attached: "-encodingUTF-8" or "-dout".
      CompilerOption option = CompilerOption.byName(arg, compiler);
      String attached = null;
      if (option == null) {
        // Try the attached form: find the longest option name this argument starts with,
        // where the option takes a value.
        for (CompilerOption candidate : CompilerOption.forCompiler(compiler)) {
          if (candidate.value() == CompilerOption.Value.NONE) {
            continue;
          }
          if (arg.length() > candidate.name().length()
              && arg.startsWith(candidate.name())
              && candidate.name().startsWith("-")) {
            option = candidate;
            attached = arg.substring(candidate.name().length());
            break;
          }
        }
      }

      if (option == null) {
        // Not an option: it is a source file.
        //
        // A jar is NOT a source file, even though javac accepts one on the
        // source path.  Recording it as one put a jar into the source list,
        // and the source list is part of the compile identity -- so two
        // genuinely different compiles could collide, and the analyser would
        // try to parse an archive as Java source.
        //
        // The upstream project made the opposite mistake in the other
        // direction: it treated ANY argument ending in ".java" as a source,
        // including the VALUE of an option it did not know.  So
        // "--processor-path some/where.jar" was dropped, and an unknown
        // option whose value ended in ".java" was mistaken for a source.
        // Here the option table consumes the values, so only a positional
        // argument reaches this branch.
        if (arg.endsWith(".java")) {
          b.sourceFiles.add(Path.of(arg));
        }
        continue;
      }

      String value = attached;
      if (value == null && option.takesValue()) {
        if (i + 1 < arguments.size()) {
          value = arguments.get(++i);
        } else {
          value = "";
        }
      }

      if (option.effect() == CompilerOption.Effect.NOT_COMPILATION) {
        b.compilation = false;
        continue;
      }

      List<String> seen = values.computeIfAbsent(option.canonicalName(), k -> new ArrayList<>());
      if (option.repeat() == CompilerOption.Repeat.LAST_WINS) {
        seen.clear();
      }
      if (value != null) {
        seen.add(value);
      }

      apply(b, option, value);
    }

    return b.build();
  }

  /**
   * Replace an argument file with the arguments it holds.
   *
   * <p><b>Why this matters.</b> javac reads its options from a file when an argument starts with
   * {@code @}. A build that puts its classpath in such a file has told javac everything through
   * the file, and the command line itself carries almost nothing. A capture that did not open the
   * file recorded a compile with an empty classpath and an unknown language level -- confidently
   * wrong, which is worse than nothing.
   *
   * <p><b>The format, measured against javac.</b> The file is split on WHITESPACE, and the quotes
   * around a token are stripped -- but they do NOT group it. So {@code -d "C:\a"} is the option
   * {@code -d} with the value {@code C:\a}, while {@code -d "C:\a b"} is the option {@code -d}
   * with the value {@code C:\a} followed by a stray {@code b"} that javac treats as a source
   * file.
   *
   * <p>A file that cannot be read is left as it was: the argument stays, and the compile is
   * recorded with whatever else the command line said. A missing argument file is a broken build,
   * and the capture reports what it can rather than nothing.
   *
   * <p>The expansion is bounded, so an argument file that refers to itself cannot loop.
   *
   * @param arguments the argv, possibly containing argument files
   * @return the argv with the argument files expanded
   */
  static List<String> expandArgumentFiles(List<String> arguments) {
    List<String> out = new ArrayList<>();
    expandArgumentFiles(arguments, out, 0);
    return out;
  }

  /** Bound on how deep an argument file may refer to another one. */
  private static final int MAX_ARGUMENT_FILE_DEPTH = 8;

  private static void expandArgumentFiles(
      List<String> arguments, List<String> out, int depth) {
    for (String argument : arguments) {
      if (argument == null || argument.isEmpty() || argument.charAt(0) != '@' || depth >= MAX_ARGUMENT_FILE_DEPTH) {
        out.add(argument);
        continue;
      }
      String fileName = argument.substring(1);
      if (fileName.isEmpty()) {
        out.add(argument);
        continue;
      }
      List<String> contents = readArgumentFile(Path.of(fileName));
      if (contents == null) {
        // The file cannot be read.  The argument stays, so the record shows
        // that the compile was driven by an argument file.
        out.add(argument);
        continue;
      }
      expandArgumentFiles(contents, out, depth + 1);
    }
  }

  /**
   * The arguments in an argument file, or {@code null} when the file cannot be read.
   *
   * <p>The file is read as UTF-8, which is what javac does for an argument file on a modern JDK.
   *
   * <p><b>The format, measured against javac.</b> The file is split on WHITESPACE, and the quotes
   * around a token are stripped -- but they do NOT group it. So {@code -d "C:\a"} is the option
   * {@code -d} with the value {@code C:\a}, while {@code -d "C:\a b"} is the option {@code -d}
   * with the value {@code C:\a} followed by a stray {@code b"} -- which javac then treats as a
   * source file. A quoted value with a space in it is therefore two tokens, exactly as javac
   * sees them.
   */
  private static List<String> readArgumentFile(Path file) {
    try {
      String text = Files.readString(file, StandardCharsets.UTF_8);
      List<String> out = new ArrayList<>();
      for (String token : text.split("\\s+")) {
        String stripped = stripQuotes(token);
        if (!stripped.isEmpty()) {
          out.add(stripped);
        }
      }
      return out;
    } catch (IOException | RuntimeException e) {
      return null;
    }
  }

  /**
   * The quotes around a token, removed.
   *
   * <p>Only a matching pair at the two ends is removed: a token that merely starts with a quote --
   * which is what a split value with a space inside it looks like -- keeps it, because that is
   * what javac sees.
   */
  private static String stripQuotes(String token) {
    if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) {
      return token.substring(1, token.length() - 1);
    }
    return token;
  }

  /** Fold one option's value into the builder. */
  private static void apply(Builder b, CompilerOption option, String value) {
    String canonical = option.canonicalName();
    switch (canonical) {
      case "--output-dir" -> {
        if (value != null && !value.isBlank()) {
          b.outputDirectory = Path.of(value);
        }
      }
      case "--classpath" -> b.classpath = CompilerOption.splitPath(value);
      case "--sourcepath" -> b.sourcePath = CompilerOption.splitPath(value);
      case "--module-path" -> b.modulePath = CompilerOption.splitPath(value);
      case "--module-source-path" -> b.moduleSourcePath = CompilerOption.splitPath(value);
      case "--processor-path" -> b.processorPath = CompilerOption.splitPath(value);
      case "bootclasspath" -> b.bootClassPath = CompilerOption.splitPath(value);
      case "--annotation-processing-dir" -> {
        if (value != null && !value.isBlank()) {
          b.annotationProcessingDirectory = Path.of(value);
        }
      }
      case "--encoding" -> {
        if (value != null && !value.isBlank()) {
          b.encoding = charset(value);
          b.encodingName = value;
        }
      }
      case "--source" -> b.source = value;
      case "--target" -> b.target = value;
      case "--release" -> b.release = value;
      case "--add-modules" -> b.addModules.addAll(CompilerOption.splitCommaList(value));
      case "--add-exports" -> b.addExports.addAll(CompilerOption.splitCommaList(value));
      case "--add-reads" -> b.addReads.addAll(CompilerOption.splitCommaList(value));
      case "--enable-preview" -> b.preview = true;
      default -> {
        // An option we record but do not interpret, e.g. --system or --patch-module.
      }
    }
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
    String encodingName;
    String source;
    String target;
    String release;
    List<Path> classpath = new ArrayList<>();
    List<Path> sourcePath = new ArrayList<>();
    List<Path> modulePath = new ArrayList<>();
    List<Path> moduleSourcePath = new ArrayList<>();
    List<Path> processorPath = new ArrayList<>();
    List<Path> bootClassPath = new ArrayList<>();
    Path outputDirectory;
    Path annotationProcessingDirectory;
    Set<Path> sourceFiles = new LinkedHashSet<>();
    List<String> addModules = new ArrayList<>();
    List<String> addExports = new ArrayList<>();
    List<String> addReads = new ArrayList<>();
    boolean preview;
    boolean compilation = true;

    JavacOptions build() {
      return new JavacOptions(this);
    }
  }
}
