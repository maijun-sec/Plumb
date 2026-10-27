package org.plumb.build.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * The set of compiler entry points the agent hooks.
 *
 * <p>This is a <b>declarative</b> description, in the same shape Coverity uses in its
 * {@code java-capture.properties}: a list of (class, method, argument) triples. The agent reads
 * it and asks ByteBuddy to match it, so adding a compiler or a new entry point is a data change,
 * not a code change.
 *
 * <p><b>Why the list is this long.</b> A "javac command line" is not one method. It is a dozen,
 * and which one fires depends on how the compiler was reached:
 *
 * <ul>
 *   <li>The {@code javac} command-line driver reaches {@code com.sun.tools.javac.Main#compile} and
 *       then {@code com.sun.tools.javac.main.Main#compile}.
 *   <li>A build tool (Gradle, Maven) reaches the compiler through the {@code javax.tools} API:
 *       {@code JavacTool#getTask} then {@code JavacTaskImpl#call}.
 *   <li>An IDE reaches it through {@code JavacTaskImpl#generate}.
 *   <li>A caller using the run-style API reaches {@code JavacTool#run}.
 *   <li>Eclipse's compiler (ECJ) has its own driver, {@code
 *       org.eclipse.jdt.internal.compiler.batch.Main#compile}.
 *   <li>The sjavac server has two more, {@code com.sun.tools.sjavac.client.ClientMain#run} and
 *       {@code com.sun.tools.sjavac.server.ServerMain#run}.
 * </ul>
 *
 * <p>Hooking only one of them means the capture silently misses every build that takes a
 * different path. That failure is invisible: the scan runs, and there is simply no capture.
 *
 * <p><b>The class names are the compiler's, verified against the JDK.</b> They are not guessed from
 * the documentation: {@code javax.tools.JavaCompiler} is an <em>interface</em> on a modern JDK and
 * has no {@code run} method, so a hook on it never fires. The {@code run} method lives on {@code
 * com.sun.tools.javac.api.JavacTool}.
 */
public final class CompilerEntryPoints {

  /**
   * One hooked method.
   *
   * @param typeName the fully qualified class name
   * @param methodName the method name
   * @param argumentCount how many arguments the method takes; {@code -1} means "any"
   * @param optionsIndex which argument carries the compiler options, or {@code -1} when the entry
   *     point does not take them
   * @param sourcesIndex which argument carries the source files, or {@code -1}
   * @param kind how the arguments are delivered
   * @param compiler which compiler's option table the arguments are parsed with
   */
  public record EntryPoint(
      String typeName,
      String methodName,
      int argumentCount,
      int optionsIndex,
      int sourcesIndex,
      Kind kind,
      CompilerOption.Compiler compiler) {

    /** The entry point as it is written in the table, for the gate and the logs. */
    public String describe() {
      return typeName + "#" + methodName + "/" + argumentCount;
    }

    /**
     * How the compiler arguments reach the hook.
     *
     * <p>The distinction matters: the CLI drivers take a {@code String[]}, the {@code javax.tools}
     * API takes the options as a {@code List<String>} and the sources as an {@code Iterable} of
     * {@code JavaFileObject}s.
     */
    public enum Kind {
      /** The options are a {@code String[]}. */
      ARGV,
      /** The options are an {@code Iterable} and the sources are a second {@code Iterable}. */
      FILE_OBJECTS,
      /**
       * The options and sources are given, and the method also <em>returns</em> a task.
       *
       * <p>This is {@code JavacTool#getTask}. It behaves like {@link #FILE_OBJECTS} and additionally
       * hands back the task, which is what makes the IDE path capturable: the task is remembered
       * with its options here, and looked up again when the task is driven.
       */
      FILE_OBJECTS_WITH_TASK,
      /**
       * The method takes no compiler options at all.
       *
       * <p>{@code JavacTaskImpl#call()} and {@code JavacTaskImpl#generate()} are in this
       * category: by the time they run, the options were already handed to {@code JavacTool
       * #getTask}. The IDE path goes through them, so hooking them is what makes the capture work
       * in an IDE -- but the options have to come from somewhere else.
       *
       * <p>That somewhere else is the task itself: the options were remembered when the task was
       * created, keyed by the task's identity. They cannot be read off the task later, because
       * {@code com.sun.tools.javac.api} is not exported by the {@code jdk.compiler} module.
       */
      NO_OPTIONS,
      /**
       * The method is the compiler's parse step, and its argument is the file being parsed.
       *
       * <p><b>This is where the sources the compiler really compiled are found.</b> Every source
       * the compiler parses passes through {@code JavaCompiler#parse(JavaFileObject,
       * CharSequence)}: the ones it was given, through {@code parseFiles}, and the ones it found on
       * the source path, through {@code readSourceFile}. So one hook here sees exactly the sources
       * the compiler parsed -- and a source in the source path that nobody referenced is never
       * parsed, so it never appears.
       *
       * <p>It is a separate category because the hook is different: it reads the file being parsed,
       * not the options.
       */
      PARSED_SOURCE,
      /**
       * The method is the compiler's compile step, and it carries no options.
       *
       * <p><b>This is where the capture is written.</b> The compile has run by then, so the sources
       * it really compiled are known -- and a capture written when the compile <em>starts</em>
       * cannot report them. The options are known at the start, so they are staged until the compile
       * ends.
       *
       * <p>{@code JavaCompiler#compile(Collection, Collection, Iterable, Collection)} is the one
       * compile step both paths go through: the command-line driver reaches it through {@code
       * com.sun.tools.javac.main.Main}, and the {@code javax.tools} API reaches it through {@code
       * JavacTaskImpl}. One hook therefore covers every compile.
       */
      COMPILE_END
    }
  }

  /**
   * The entry points, in the order they are probed.
   *
   * <p>Order matters only for diagnostics: the first entry point whose class is present is the
   * one that will fire for a given build. Classes that are absent (ECJ in a javac build, sjavac
   * in a plain build) are simply skipped.
   */
  private static final List<EntryPoint> ENTRY_POINTS =
      List.of(
          // --- the javac command-line driver ---
          // com.sun.tools.javac.Main#compile(String[]) -- static, the CLI's own entry point.
          new EntryPoint(
              "com.sun.tools.javac.Main", "compile", 1, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),
          // com.sun.tools.javac.Main#compile(String[], PrintWriter) -- the same, but the
          // diagnostics go to a writer the caller supplies.
          new EntryPoint(
              "com.sun.tools.javac.Main", "compile", 2, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),
          // com.sun.tools.javac.main.Main#compile(String[]) -- instance, the options are the
          // FIRST declared parameter (the instance `this` is not a parameter).
          new EntryPoint(
              "com.sun.tools.javac.main.Main", "compile", 1, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),
          // com.sun.tools.javac.main.Main#compile(String[], Context) -- same, plus the context.
          new EntryPoint(
              "com.sun.tools.javac.main.Main", "compile", 2, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),

          // --- the javax.tools API: what Gradle, Maven and the IDEs use ---
          // JavacTool#getTask(Writer, FileManager, DiagnosticListener,
          //                  Iterable<String> options, Iterable<String> classes, Iterable<FileObject>)
          //
          // This one also returns the task, so it is remembered with its options: the task is
          // what an IDE later drives with call() or generate(), and those carry no options.
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTool",
              "getTask",
              6,
              3,
              5,
              EntryPoint.Kind.FILE_OBJECTS_WITH_TASK, CompilerOption.Compiler.JAVAC),
          // JavacTool#getTask(..., Context) -- the seven-argument overload, same positions.
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTool",
              "getTask",
              7,
              3,
              5,
              EntryPoint.Kind.FILE_OBJECTS_WITH_TASK, CompilerOption.Compiler.JAVAC),

          // --- the old javax.tools.JavaCompiler#run API ---
          //
          // The `run` method is on com.sun.tools.javac.api.JavacTool, NOT on
          // javax.tools.JavaCompiler: on a modern JDK the latter is an interface with only
          // `getTask` and `getStandardFileManager`, and no `run` at all. Hooking
          // `javax.tools.JavaCompiler#run` therefore never fires -- the capture silently
          // does nothing for every caller that uses the run-style API.
          //
          // The signature is `run(InputStream, OutputStream, OutputStream, String...)`, so the
          // options are the varargs array in argument 3.
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTool", "run", 4, 3, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.JAVAC),

          // --- the IDE path: JavacTaskImpl#call() and #generate() ---
          //
          // An IDE creates a task with getTask and then drives it with call() or generate().
          // Neither takes the options -- they were set on the task when it was created -- so the
          // hook looks them up from the ones remembered at getTask. They cannot be read off the
          // task directly: com.sun.tools.javac.api is not exported by the jdk.compiler module.
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTaskImpl",
              "call",
              0,
              -1,
              -1,
              EntryPoint.Kind.NO_OPTIONS, CompilerOption.Compiler.JAVAC),
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTaskImpl",
              "generate",
              0,
              -1,
              -1,
              EntryPoint.Kind.NO_OPTIONS, CompilerOption.Compiler.JAVAC),

          // --- the compiler's own parse step: the sources it really compiled ---
          //
          // Every source the compiler parses passes through this method: the
          // ones it was given, through parseFiles, and the ones it found on the
          // source path, through readSourceFile. A source in the source path
          // that nobody referenced is never parsed, so it never appears here.
          //
          // The method is `protected`, which is fine: ByteBuddy instruments
          // methods by name and argument count, not by visibility.
          new EntryPoint(
              "com.sun.tools.javac.main.JavaCompiler",
              "parse",
              2,
              -1,
              -1,
              EntryPoint.Kind.PARSED_SOURCE,
              CompilerOption.Compiler.JAVAC),

          // --- the compiler's own compile step: where the capture is written ---
          //
          // The compile has run by then, so the sources it really compiled are
          // known. Both paths reach this method: the command line through
          // com.sun.tools.javac.main.Main, the javax.tools API through
          // JavacTaskImpl. One hook therefore covers every compile.
          new EntryPoint(
              "com.sun.tools.javac.main.JavaCompiler",
              "compile",
              4,
              -1,
              -1,
              EntryPoint.Kind.COMPILE_END,
              CompilerOption.Compiler.JAVAC),

          // The IDE's other path. `JavacTaskImpl#generate()` does NOT go
          // through `compile`: it parses, attributes and then calls
          // `JavaCompiler#generate(Queue)`. So the generate step is hooked as
          // well -- otherwise an IDE that generates code would produce no
          // capture at all.
          new EntryPoint(
              "com.sun.tools.javac.main.JavaCompiler",
              "generate",
              1,
              -1,
              -1,
              EntryPoint.Kind.COMPILE_END,
              CompilerOption.Compiler.JAVAC),

          // --- Eclipse's compiler ---
          new EntryPoint(
              "org.eclipse.jdt.internal.compiler.batch.Main",
              "compile",
              1,
              0,
              -1,
              EntryPoint.Kind.ARGV, CompilerOption.Compiler.ECJ),

          // --- the sjavac server ---
          new EntryPoint(
              "com.sun.tools.sjavac.client.ClientMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.SJAVAC),
          new EntryPoint(
              "com.sun.tools.sjavac.server.ServerMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV, CompilerOption.Compiler.SJAVAC));

  private CompilerEntryPoints() {}

  /** Every entry point the agent hooks. */
  public static List<EntryPoint> all() {
    return ENTRY_POINTS;
  }

  /** The entry points that live in a given class. */
  public static List<EntryPoint> forType(String typeName) {
    List<EntryPoint> out = new ArrayList<>();
    for (EntryPoint e : ENTRY_POINTS) {
      if (e.typeName().equals(typeName)) {
        out.add(e);
      }
    }
    return out;
  }

  /** The distinct class names the agent hooks. */
  public static List<String> types() {
    List<String> out = new ArrayList<>();
    for (EntryPoint e : ENTRY_POINTS) {
      if (!out.contains(e.typeName())) {
        out.add(e.typeName());
      }
    }
    return out;
  }
}
