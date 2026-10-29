/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.build.capture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One compiler option, described as data.
 *
 * <p><b>Why the options are data and not a chain of {@code if}s.</b> The original implementation
 * hard-coded a dozen option names in a switch. Every option it did not know fell through to the
 * default branch, and the default branch treated anything ending in {@code .java} as a source file.
 * So {@code --processor-path some/where.jar} was silently dropped, and an option whose value
 * happened to end in {@code .java} would be mistaken for a source.
 *
 * <p>The reference implementation solves this the same way: {@code JavacOptionParser} keeps its
 * option knowledge in tables -- a translation table, a "only the last one counts" list, a "may
 * repeat" list, a "takes no value" list, a "not a compilation option" list, and a per-compiler list
 * of path options. This class is that idea, in a form a static analyser can use.
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
     * The option takes one value that is a list of paths, separated by commas: {@code --add-exports
     * module/package=other/package}.
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
   * <p>The categories match the reference's {@code JavacOptionParser}, so the two can be compared
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

  /**
   * The options by the name the compiler spells them with, and the compiler they belong to.
   *
   * <p><b>Why the index is keyed by the name AND the compiler.</b> Two compilers can spell an
   * option the same way and still mean different things by it. ECJ and sjavac both have {@code
   * -src}, {@code -copy}, {@code -x}, {@code -i}, {@code -tr} and {@code -j} -- and sjavac's {@code
   * -j} is repeatable while ECJ's is not. An index keyed by the name alone holds ONE option per
   * name, so the second entry silently overwrote the first and the ECJ lookup rejected all six: the
   * option was dropped, and the value that followed it was read as a source file.
   *
   * <p>So the index holds one option per (name, compiler) pair. The name alone is still ambiguous,
   * and {@link #byName(String, Compiler)} is the lookup that resolves it.
   */
  private static final Map<String, Map<Compiler, CompilerOption>> BY_NAME = index();

  private static Map<String, Map<Compiler, CompilerOption>> index() {
    Map<String, Map<Compiler, CompilerOption>> out = new LinkedHashMap<>();
    for (CompilerOption option : ALL) {
      Map<Compiler, CompilerOption> perCompiler =
          out.computeIfAbsent(option.name(), k -> new LinkedHashMap<>());
      // An option that belongs to no compiler in particular is shared, and it
      // is the fallback for a compiler that has an option of its own.
      perCompiler.put(option.compiler(), option);
    }
    return out;
  }

  /**
   * The option with that name, or {@code null} when the compiler does not know it.
   *
   * <p>Without a compiler this is ambiguous for a name two compilers share, so the option that
   * belongs to no particular compiler is preferred and otherwise the first entry wins. The
   * compiler-aware lookup is the one to use when the compiler is known.
   */
  public static CompilerOption byName(String name) {
    Map<Compiler, CompilerOption> perCompiler = BY_NAME.get(name);
    if (perCompiler == null) {
      return null;
    }
    CompilerOption shared = perCompiler.get(null);
    if (shared != null) {
      return shared;
    }
    return perCompiler.values().iterator().next();
  }

  /**
   * The option with that name, for a given compiler.
   *
   * <p><b>The compiler matters.</b> ECJ has options javac does not have ({@code -src}, {@code
   * -profile}, {@code -log}). A global lookup would give javac an option it does not have, and the
   * value would be swallowed -- the option would vanish.
   *
   * <p>Where the two compilers agree -- on the classpath's three spellings, for instance -- the
   * option belongs to neither one and both lookups find it.
   *
   * <p>And where they spell an option the same way but mean different things by it, each compiler
   * gets its own entry: {@code -src} and {@code -j} are ECJ's and sjavac's separately.
   *
   * @param name the option as the compiler spells it
   * @param compiler which compiler is doing the parsing
   * @return the option, or {@code null} when that compiler does not know it
   */
  public static CompilerOption byName(String name, Compiler compiler) {
    Map<Compiler, CompilerOption> perCompiler = BY_NAME.get(name);
    if (perCompiler == null) {
      return null;
    }
    // The compiler's own option wins over a shared one.
    CompilerOption own = perCompiler.get(compiler);
    if (own != null) {
      return own;
    }
    // An option that belongs to no compiler in particular applies to all of them.
    return perCompiler.get(null);
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
    out.add(
        opt(
            "--output-dir",
            "--output-dir",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the classpath ----
    // These are the three spellings javac accepts, and ECJ accepts the first two.
    out.add(
        opt(
            "-classpath",
            "--classpath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--classpath",
            "--classpath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(opt("-cp", "--classpath", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    // javac's own help lists "--class-path <path>, -classpath <path>, -cp
    // <path>" -- so the double-dash spelling belongs to javac too, and not
    // only to ECJ.  It was missing, so `javac --class-path ...` lost the
    // classpath: the option was unknown, and the value that followed it was
    // not a source file either, so it vanished entirely.
    //
    // ECJ accepts the same spelling and it means the same thing, so it is
    // ONE option shared by both compilers rather than one per compiler.  A
    // per-compiler copy would need the index to hold two options under one
    // name, and the index holds one.
    out.add(
        opt(
            "--class-path",
            "--classpath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the source path ----
    out.add(
        opt(
            "-sourcepath",
            "--sourcepath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--sourcepath",
            "--sourcepath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--source-path",
            "--sourcepath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the boot class path ----
    // javac spells it -bootclasspath and --boot-class-path; ECJ spells it -bootclasspath.
    out.add(
        opt(
            "-bootclasspath",
            "bootclasspath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--boot-class-path",
            "bootclasspath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "-Xbootclasspath",
            "bootclasspath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the extension and endorsed directories ----
    out.add(
        opt("-extdirs", "extdirs", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt(
            "-Djava.ext.dirs",
            "extdirs",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "-endorseddirs",
            "endorseddirs",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "-Djava.endorsed.dirs",
            "endorseddirs",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    // The -Xbootclasspath/p: and /a: forms prepend and append.
    out.add(
        opt(
            "-Xbootclasspath/p",
            "prebootclasspath",
            Value.PATH_LIST,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "-Xbootclasspath/a",
            "postbootclasspath",
            Value.PATH_LIST,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));

    // ---- the module paths ----
    // These are the "only the last one counts" options in the reference table.
    out.add(
        opt(
            "--module-path",
            "--module-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt("-p", "--module-path", Value.PATH_LIST, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt(
            "--module-source-path",
            "--module-source-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--upgrade-module-path",
            "--upgrade-module-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the modules themselves ----
    out.add(opt("--module", "--module", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-m", "--module", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt(
            "--limit-modules",
            "--limit-modules",
            Value.COMMA_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    // --add-modules may be repeated and every occurrence applies, but only the last one of a
    // repeated name is kept when the compiler squashes them.
    out.add(
        opt(
            "--add-modules",
            "--add-modules",
            Value.COMMA_LIST,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--add-exports",
            "--add-exports",
            Value.COMMA_LIST,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--add-reads",
            "--add-reads",
            Value.COMMA_LIST,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--patch-module",
            "--patch-module",
            Value.SINGLE,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            null));

    // ---- the system image ----
    out.add(opt("--system", "--system", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the processor paths ----
    out.add(
        opt(
            "-processorpath",
            "--processor-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--processor-path",
            "--processor-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt(
            "--processor-module-path",
            "--processor-module-path",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt("-processor", "--processor", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the annotation processing directory ----
    out.add(
        opt(
            "-s",
            "--annotation-processing-dir",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the language level ----
    out.add(
        opt("--release", "--release", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-source", "--source", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--source", "--source", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-target", "--target", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("--target", "--target", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the encoding ----
    out.add(
        opt("-encoding", "--encoding", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the options that take no value ----
    out.add(
        opt(
            "--enable-preview",
            "--enable-preview",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the options that do not compile anything ----
    // -Xprint prints the compiler's internal state; -proc:only runs only the processors.
    // Neither produces a class file, so a capture that counted them as compiles would report
    // a compilation that never happened.
    out.add(opt("-Xprint", "-Xprint", Value.NONE, Repeat.LAST_WINS, Effect.NOT_COMPILATION, null));
    out.add(
        opt(
            "-proc:only",
            "-proc:only",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.NOT_COMPILATION,
            null));

    // ---- the annotation processing switch ----
    // javac spells it -proc:{none,only,full}.  Only -proc:only was in the
    // table, so -proc:none was an unknown option -- and an unknown option
    // is one the parser cannot account for.
    out.add(
        opt("-proc:none", "-proc:none", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt("-proc:full", "-proc:full", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the implicit compilation switch ----
    // -implicit:{none,class} decides whether a class file is written for a
    // source file that was found on the source path rather than named on the
    // command line.  It changes what the compile PRODUCES, so it is a
    // compilation option and not a diagnostic one.
    out.add(
        opt("-implicit:none", "-implicit", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt(
            "-implicit:class",
            "-implicit",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // sjavac spells the same switch differently: `-implicit:` with the value
    // in the NEXT token, so `-implicit: none` and not `-implicit:none`.  The
    // measured sjavac accepts both the joined and the separated form, and it
    // is the separated form that is its own spelling.  The option is the same
    // one -- the policy for implicitly referenced sources -- so it is recorded
    // under the same canonical name.
    out.add(
        opt(
            "-implicit:",
            "--implicit",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));

    // ---- the module of the files that are created ----
    // --default-module-for-created-files names the module a generated class
    // file belongs to.  It is what a modular build sets, and it is how a
    // class file ends up in a module it would not otherwise be in.
    out.add(
        opt(
            "--default-module-for-created-files",
            "--default-module-for-created-files",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));

    // ---- the annotation processor options ----
    // -Akey[=value] passes an option to the annotation processors.  The
    // value is optional, and the key may stand alone.
    out.add(opt("-A", "-A", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, null));

    // ---- the line comments ----
    // --disable-line-doc-comments turns off the doc comments on a line, and
    // --help-lint lists the lint checks.  Neither changes the class files,
    // but both are options javac accepts, and an option javac accepts that
    // the table does not know is an option whose value is lost.
    out.add(
        opt(
            "--disable-line-doc-comments",
            "--disable-line-doc-comments",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(
        opt("--help-lint", "--help-lint", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the debug-info switch ----
    // javac spells it -g:{lines,vars,source} and -g:none.  Only the bare -g
    // was in the table.
    out.add(opt("-g:none", "-g", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-g:lines", "-g", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-g:vars", "-g", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-g:source", "-g", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

    // ---- the options that are not compilation options but still take a value ----
    // ECJ's own set: these are ECJ-specific and are recorded but not interpreted.
    // -h is the header directory.  It was ECJ's alone, but the real sjavac
    // has it too -- the measured sjavac's option enum lists "-h  Directory for
    // header files" -- so the option belongs to both compilers.
    out.add(opt("-h", "-h", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-i", "-i", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-j", "-j", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-log", "-log", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(
        opt(
            "--module-version",
            "--module-version",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(
        opt(
            "-profile",
            "-profile",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(opt("-tr", "-tr", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(opt("-x", "-x", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));
    out.add(
        opt(
            "-Xmaxerrs",
            "-Xmaxerrs",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(
        opt(
            "-Xmaxwarns",
            "-Xmaxwarns",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(
        opt(
            "-Xstdout",
            "-Xstdout",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(
        opt("-copy", "-copy", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.ECJ));

    // ---- the source directories ----
    // ECJ's -src names a directory of sources rather than a file.
    out.add(
        opt(
            "-src",
            "source_files_or_dirs",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));

    // ---- the options measured against the real ECJ ----
    //
    // These were found by running ECJ's own `-help` and comparing it with
    // this table.  ECJ 3.33.0 lists them and the table did not know them.
    //
    // `-annotationpath` is the one that matters: it names the external
    // annotations that support annotation-based null analysis, so it changes
    // what the compiler checks.  Without it in the table the option was
    // unknown AND the path that followed it was not a source file either --
    // so both the option and its value vanished, exactly like the missing
    // `--class-path` cost javac its whole classpath.
    out.add(
        opt(
            "-annotationpath",
            "annotationpath",
            Value.PATH_LIST,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));

    // ECJ's spelling of the warning switch.  javac spells it `-nowarn`; ECJ
    // spells it `-warn:none`.  The option is the same one -- the warnings the
    // compiler reports -- but the CANONICAL name has to be ECJ's own, because
    // the shared `-nowarn` is a different entry and the index holds one
    // option per (name, compiler).
    out.add(
        opt(
            "-warn:none",
            "-warn:none",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));

    // The advanced warning options.  They only change what the compiler
    // prints, and they take a value that is a warning list rather than a
    // path.
    out.add(
        opt(
            "-?:warn",
            "-?:warn",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));
    out.add(
        opt(
            "-help:warn",
            "-help:warn",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.ECJ));

    // ---- the options measured against the real sjavac ----
    //
    // sjavac was removed from the JDK after Java 11, so these were measured
    // against the real `com.sun.tools.sjavac.options.Option` enum of a JDK 11
    // (Temurin 11.0.32.1+1).  The enum IS the compiler's option table: every
    // constant carries the spelling the compiler accepts.
    //
    // THE KEY FACT, and it is not a detail: sjavac has its OWN option table and
    // it CONSUMES those options itself.  Everything sjavac does not recognise
    // is forwarded to javac.  Measured on the real compiler:
    //
    //   sjavac consumes: -src --source-path -sourcepath --module-path -p
    //                    --class-path -classpath -cp -x -i -tr -copy -j
    //                    --server: --startserver: -implicit: --log= -verbose
    //                    --permit-artifact= --permit-unidentified-artifacts
    //                    --permit-sources-without-package
    //                    --compare-found-sources -d -s -h --state-dir=
    //
    //   sjavac forwards to javac: -encoding -source -target --release -g
    //                    -nowarn -proc:none -processor -processorpath
    //                    -bootclasspath --add-modules -Xlint -parameters -A
    //
    // That is why the sjavac options below are attributed to SJAVAC and the
    // javac ones are not: an option sjavac forwards is a javac option, and
    // attributing it to sjavac would make javac's own lookup reject it.
    //
    // A THIRD fact, also measured: the value of an sjavac option is taken from
    // the NEXT argument, not from the same token.  `-j 2` works and `-j=2` is
    // REJECTED; the same holds for `--server:`, `--startserver:` and
    // `--state-dir=`.  The options spelled with a trailing `=` or `:` still
    // take the value in the next token -- sjavac's spelling says where the
    // value MAY go, not that it must be joined.
    //
    // sjavac's own source and class paths are PATH_LISTs separated by the
    // platform separator, the same as javac's.
    out.add(
        opt(
            "-src",
            "source_files_or_dirs",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(opt("-x", "-x", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, Compiler.SJAVAC));
    out.add(opt("-i", "-i", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, Compiler.SJAVAC));
    out.add(opt("-tr", "-tr", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, Compiler.SJAVAC));
    out.add(
        opt("-copy", "-copy", Value.SINGLE, Repeat.ALL_APPLY, Effect.COMPILATION, Compiler.SJAVAC));
    // The number of cores.  It does not change the class files, but it IS a
    // value-taking option, so it has to be recognised -- otherwise the number
    // after it is read as a source file.
    out.add(opt("-j", "-j", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, Compiler.SJAVAC));
    out.add(
        opt(
            "--server:",
            "--server",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--startserver:",
            "--startserver",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--permit-artifact=",
            "--permit-artifact",
            Value.SINGLE,
            Repeat.ALL_APPLY,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--permit-unidentified-artifacts",
            "--permit-unidentified-artifacts",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--permit-sources-without-package",
            "--permit-sources-without-package",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--compare-found-sources",
            "--compare-found-sources",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));
    out.add(
        opt(
            "--state-dir=",
            "--state-dir",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));

    // The logging level.  sjavac spells it with a trailing "=", but it takes
    // the value from the NEXT token -- the measured compiler accepts
    // `--log= info` and rejects nothing here -- so it is a single-valued
    // option like the others.
    out.add(
        opt(
            "--log=",
            "--log",
            Value.SINGLE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            Compiler.SJAVAC));

    // ---- the options that only affect diagnostics ----
    // These do not change the class files, so they are recorded but not interpreted.
    out.add(opt("-nowarn", "-nowarn", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-verbose", "-verbose", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt(
            "-deprecation",
            "-deprecation",
            Value.NONE,
            Repeat.LAST_WINS,
            Effect.COMPILATION,
            null));
    out.add(opt("-Xlint", "-Xlint", Value.SINGLE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(opt("-Werror", "-Werror", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    // The value is IN the spelling -- `-g:lines` names what to include -- so
    // the switch takes no separate argument.  `javac -g` needs nothing after
    // it, which is what Value.NONE says.
    out.add(opt("-g", "-g", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));
    out.add(
        opt("-parameters", "-parameters", Value.NONE, Repeat.LAST_WINS, Effect.COMPILATION, null));

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
   * by commas. Splitting them on the path separator would be wrong on Unix, where the separator is
   * a colon and the value contains no colon at all -- but it would be right by accident on Windows,
   * which is exactly the kind of accident that hides a bug until it runs on Linux.
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
