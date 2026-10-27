package org.plumb.build.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One compiler option, described as data.
 *
 * <p><b>Why the options are data and not a chain of {@code if}s.</b> The original
 * implementation hard-coded a dozen option names in a switch. Every option it did not know
 * fell through to the default branch, and the default branch treated anything ending in
 * {@code .java} as a source file. So {@code --processor-path some/where.jar} was silently
 * dropped, and an option whose value happened to end in {@code .java} would be mistaken for a
 * source.
 *
 * <p>Coverity solves this the same way: {@code JavacOptionParser} keeps its option knowledge in
 * tables -- a translation table, a "only the last one counts" list, a "may repeat" list, a
 * "takes no value" list, a "not a compilation option" list, and a per-compiler list of path
 * options. This class is that idea, in a form a static analyser can use.
 *
 * <p>Adding an option is therefore <b>a data change</b>: one line in {@link #ALL}.
 */
public final class CompilerOption {

  /** How many values an option takes, and what kind. */
  public enum Value {
    /** The option takes no value: {@code --enable-preview}. */
    NONE,
    /** The option takes one value: {@code -encoding}. */
    SINGLE,
    /**
     * The option takes one value that is a list of paths, separated by the platform's path
     * separator: {@code -classpath}.
     */
    PATH_LIST,
    /**
     * The option takes one value that is a list of paths, separated by commas: {@code
     * --add-exports module/package=other/package}.
     */
    COMMA_LIST
  }

  /**
   * How repeated uses of the option combine.
   *
   * <p>This is the distinction that decides what the compile actually did. {@code -classpath} given
   * twice means the <em>last</em> one wins, not both. {@code --add-modules} given twice means both
   * module lists apply. Getting this wrong produces a classpath that never existed.
   */
  public enum Repeat {
    /** Only the last occurrence counts: {@code -classpath}. */
    LAST_WINS,
    /** Every occurrence applies: {@code --add-exports}. */
    ALL_APPLY
  }

  /**
   * Whether the option affects the compilation at all.
   *
   * <p>{@code -Xprint} and {@code -proc:only} do not produce a class file: the first prints
   * internal information, the second runs only the annotation processors. A capture that treats
   * them as compilation options would report a compile that never happened.
   */
  public enum Effect {
    /** The option changes the compilation. */
    COMPILATION,
    /** The option does not produce a class file. */
    NOT_COMPILATION
  }

  private final String name;
  private final String canonicalName;
  private final Value value;
  private final Repeat repeat;
  private final Effect effect;
  private final Compiler compiler;

  private CompilerOption(
      String name,
      String canonicalName,
      Value value,
      Repeat repeat,
      Effect effect,
      Compiler compiler) {
    this.name = name;
    this.canonicalName = canonicalName;
    this.value = value;
    this.repeat = repeat;
    this.effect = effect;
    this.compiler = compiler;
  }

  /** The option as the compiler spells it, e.g. {@code -cp}. */
  public String name() {
    return name;
  }

  /**
   * The name the option is recorded under, e.g. {@code --classpath}.
   *
   * <p>Synonyms share a canonical name: {@code -cp}, {@code -classpath} and {@code --class-path}
   * are all {@code --classpath}. That is what makes two captures comparable, and it is why the
   * compile identity does not change when a build switches between the spellings.
   */
  public String canonicalName() {
    return canonicalName;
  }

  public Value value() {
    return value;
  }

  public Repeat repeat() {
    return repeat;
  }

  public Effect effect() {
    return effect;
  }

  /** The compiler this option applies to, or {@code null} when it applies to all of them. */
  public Compiler compiler() {
    return compiler;
  }

  /** Whether the option takes a value. */
  public boolean takesValue() {
    return value != Value.NONE;
  }

  @Override
  public String toString() {
    return name;
  }

  // ------------------------------------------------------------------ the table

  /** The compilers whose options differ. */
  public enum Compiler {
    /** javac and everything built on it. */
    JAVAC,
    /** Eclipse's compiler. */
    ECJ,
    /** The sjavac server. */
    SJAVAC
  }

  /**
   * Every option, in one table.
   *
   * <p>The categories match Coverity's {@code JavacOptionParser}, so the two can be compared
   * entry by entry:
   *
   * <ul>
   *   <li>options that only the last occurrence of which counts;
   *   <li>options that may be repeated and every occurrence of which applies;
   *   <li>options that take no value;
   *   <li>options that do not produce a class file;
   *   <li>path options, whose values are lists rather than single paths.
   * </ul>
   */
  public static final List<CompilerOption> ALL = buildTable();

  /** The options by the name the compiler spells them with. */
  private static final Map<String, CompilerOption> BY_NAME = index();

  private static Map<String, CompilerOption> index() {
    Map<String, CompilerOption> out = new LinkedHashMap<>();
    for (CompilerOption option : ALL) {
      out.put(option.name(), option);
    }
    return out;
  }

  /** The option with that name, or {@code null} when the compiler does not know it. */
  public static CompilerOption byName(String name) {
    return BY_NAME.get(name);
  }

  /**
   * The option with that name, for a given compiler.
   *
   * <p><b>The compiler matters.</b> ECJ spells the classpath {@code --class-path}, and javac does
   * not know that spelling; ECJ has options javac does not have ({@code -src}, {@code -profile},
   * {@code -log}). A global lookup would give javac an option it does not have, and the value
   * would be swallowed -- the classpath would vanish.
   *
   * @param name the option as the compiler spells it
   * @param compiler which compiler is doing the parsing
   * @return the option, or {@code null} when that compiler does not know it
   */
  public static CompilerOption byName(String name, Compiler compiler) {
    CompilerOption option = BY_NAME.get(name);
    if (option == null) {
      return null;
    }
    // An option that belongs to another compiler is not this compiler's option.
    if (option.compiler() != null && option.compiler() != compiler) {
      return null;
    }
    return option;
  }

  /**
   * The options of one compiler.
   *
   * <p>The path options differ between the compilers: ECJ's {@code bootclasspath} means something
   * different from javac's, and sjavac takes its own set. The options that apply to every compiler
   * are included in each compiler's list.
   */
  public static List<CompilerOption> forCompiler(Compiler compiler) {
    List<CompilerOption> out = new ArrayList<>();
    for (CompilerOption option : ALL) {
      if (option.compiler() == null || option.compiler() == compiler) {
        out.add(option);
      }
    }
    return out;
  }

  private static List<CompilerOption> buildTable() {
    List<CompilerOption> out = new ArrayList<>();

    // ---- the output directory ----
    // javac: -d.  ECJ: -d.  sjavac: -d.
    out.add(opt("-d", "--output-dir", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--output-dir", "--output-dir", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the classpath ----
    // These are the three spellings javac accepts, and ECJ accepts the first two.
    out.add(opt("-classpath", "--classpath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--classpath", "--classpath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-cp", "--classpath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    // ECJ spells it --class-path, and only ECJ accepts that spelling.  It
    // gets its own canonical name rather than sharing --classpath's, because
    // it belongs to a different compiler: javac does not know it, and the two
    // must not be treated as one option.
    out.add(opt("--class-path", "--class-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));

    // ---- the source path ----
    out.add(opt("-sourcepath", "--sourcepath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--sourcepath", "--sourcepath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--source-path", "--sourcepath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the boot class path ----
    // javac spells it -bootclasspath and --boot-class-path; ECJ spells it -bootclasspath.
    out.add(opt("-bootclasspath", "bootclasspath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--boot-class-path", "bootclasspath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Xbootclasspath", "bootclasspath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the extension and endorsed directories ----
    out.add(opt("-extdirs", "extdirs", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Djava.ext.dirs", "extdirs", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-endorseddirs", "endorseddirs", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Djava.endorsed.dirs", "endorseddirs", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    // The -Xbootclasspath/p: and /a: forms prepend and append.
    out.add(opt("-Xbootclasspath/p", "prebootclasspath", Value.PATH_LIST, Repeat.ALL_APPLY, Effect.COMPILATION, null));
    out.add(opt("-Xbootclasspath/a", "postbootclasspath", Value.PATH_LIST, Repeat.ALL_APPLY, Effect.COMPILATION, null));

    // ---- the module paths ----
    // These are the "only the last one counts" options in Coverity's table.
    out.add(opt("--module-path", "--module-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-p", "--module-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--module-source-path", "--module-source-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--upgrade-module-path", "--upgrade-module-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the modules themselves ----
    out.add(opt("--module", "--module", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-m", "--module", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--limit-modules", "--limit-modules", Value.COMMA_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    // --add-modules may be repeated and every occurrence applies, but only the last one of a
    // repeated name is kept when the compiler squashes them.
    out.add(opt("--add-modules", "--add-modules", Value.COMMA_LIST, Repeat.ALL_APPLY, Effect.COMPILATION, null));
    out.add(opt("--add-exports", "--add-exports", Value.COMMA_LIST, Repeat.ALL_APPLY, Effect.COMPILATION, null));
    out.add(opt("--add-reads", "--add-reads", Value.COMMA_LIST, Repeat.ALL_APPLY, Effect.COMPILATION, null));
    out.add(opt("--patch-module", "--patch-module", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, null));

    // ---- the system image ----
    out.add(opt("--system", "--system", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the processor paths ----
    out.add(opt("-processorpath", "--processor-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--processor-path", "--processor-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--processor-module-path", "--processor-module-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-processor", "--processor", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the annotation processing directory ----
    out.add(opt("-s", "--annotation-processing-dir", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the language level ----
    out.add(opt("--release", "--release", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-source", "--source", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-target", "--target", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the encoding ----
    out.add(opt("-encoding", "--encoding", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the options that take no value ----
    out.add(opt("--enable-preview", "--enable-preview", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the options that do not compile anything ----
    // -Xprint prints the compiler's internal state; -proc:only runs only the processors.
    // Neither produces a class file, so a capture that counted them as compiles would report
    // a compilation that never happened.
    out.add(opt("-Xprint", "-Xprint", Value.NONE, Repeat.LAST_WINS, Effect.NOT_COMPILATION, null));
    out.add(opt("-proc:only", "-proc:only", Value.NONE, Repeat.LAST_WINS, Effect.NOT_COMPILATION, null));

    // ---- the options that are not compilation options but still take a value ----
    // ECJ's own set: these are ECJ-specific and are recorded but not interpreted.
    out.add(opt("-h", "-h", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-i", "-i", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-j", "-j", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-log", "-log", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("--module-version", "--module-version", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-profile", "-profile", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-tr", "-tr", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-x", "-x", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-Xmaxerrs", "-Xmaxerrs", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-Xmaxwarns", "-Xmaxwarns", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-Xstdout", "-Xstdout", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-copy", "-copy", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));

    // ---- the source directories ----
    // ECJ's -src names a directory of sources rather than a file.
    out.add(opt("-src", "source_files_or_dirs", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));

    // ---- the options that only affect diagnostics ----
    // These do not change the class files, so they are recorded but not interpreted.
    out.add(opt("-nowarn", "-nowarn", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-verbose", "-verbose", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-deprecation", "-deprecation", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Xlint", "-Xlint", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Werror", "-Werror", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-g", "-g", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-parameters", "-parameters", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    return List.copyOf(out);
  }

  private static CompilerOption opt(
      String name,
      String canonicalName,
      Value value,
      Repeat repeat,
      Effect effect,
      Compiler compiler) {
    return new CompilerOption(name, canonicalName, value, repeat, effect, compiler);
  }

  /**
   * The options whose values are paths, split into their entries.
   *
   * <p>The separator is platform-dependent, and the drive letter is part of the path. On Windows
   * the separator is {@code ;} and a path looks like {@code D:\cache\repo.jar}; on Unix it is
   * {@code :} and a path looks like {@code /home/u/repo.jar}. Splitting on both separators at once
   * turns {@code D:\cache\repo.jar} into {@code D} and {@code \cache\repo.jar} -- two paths that
   * are both wrong, and the first one happens to look like a valid relative path, so the breakage
   * is easy to miss.
   *
   * @param value the raw option value
   * @return the paths, empty when the value is empty
   */
  public static List<Path> splitPath(String value) {
    List<Path> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    char separator = isWindows() ? ';' : ':';
    for (String part : value.split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        try {
          out.add(Path.of(trimmed));
        } catch (RuntimeException e) {
          // Not a path -- skip it rather than failing the whole capture.
        }
      }
    }
    return out;
  }

  /**
   * The comma-separated entries of a value like {@code module/package=other/package}.
   *
   * <p>These are not paths: {@code --add-exports} takes a list of module/package pairs, separated
   * by commas. Splitting them on the path separator would be wrong on Unix, where the separator
   * is a colon and the value contains no colon at all -- but it would be right by accident on
   * Windows, which is exactly the kind of accident that hides a bug until it runs on Linux.
   */
  public static List<String> splitCommaList(String value) {
    List<String> out = new ArrayList<>();
    if (value == null || value.isBlank()) {
      return out;
    }
    for (String part : value.split(",")) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        out.add(trimmed);
      }
    }
    return out;
  }

  /** True when the platform separates classpath entries with {@code ;}. */
  public static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
  }
}
