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
 * <p><b>Why the list is this long.</b> A "javac command line" is not one method. It is eight,
 * and which one fires depends on how the compiler was reached:
 *
 * <ul>
 *   <li>The {@code javac} command-line driver reaches {@code com.sun.tools.javac.Main#compile} and
 *       then {@code com.sun.tools.javac.main.Main#compile}.
 *   <li>A build tool (Gradle, Maven) reaches the compiler through the {@code javax.tools} API:
 *       {@code JavacTool#getTask} then {@code JavacTaskImpl#call}.
 *   <li>An IDE reaches it through {@code JavacTaskImpl#generate}.
 *   <li>Eclipse's compiler (ECJ) has its own driver, {@code
 *       org.eclipse.jdt.internal.compiler.batch.Main#compile}.
 *   <li>The sjavac server has two more, {@code com.sun.tools.sjavac.client.ClientMain#run} and
 *       {@code com.sun.tools.sjavac.server.ServerMain#run}.
 * </ul>
 *
 * <p>Hooking only one of them means the capture silently misses every build that takes a
 * different path. That failure is invisible: the scan runs, and there is simply no capture.
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
   */
  public record EntryPoint(
      String typeName,
      String methodName,
      int argumentCount,
      int optionsIndex,
      int sourcesIndex,
      Kind kind) {

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
      FILE_OBJECTS
    }

    /** A human-readable description, for logs and error messages. */
    public String describe() {
      return typeName + "#" + methodName + "/" + argumentCount;
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
              "com.sun.tools.javac.Main", "compile", 1, 0, -1, EntryPoint.Kind.ARGV),
          // com.sun.tools.javac.main.Main#compile(String[]) -- instance, the options are the
          // FIRST declared parameter (the instance `this` is not a parameter).
          new EntryPoint(
              "com.sun.tools.javac.main.Main", "compile", 1, 0, -1, EntryPoint.Kind.ARGV),
          // com.sun.tools.javac.main.Main#compile(String[], Context) -- same, plus the context.
          new EntryPoint(
              "com.sun.tools.javac.main.Main", "compile", 2, 0, -1, EntryPoint.Kind.ARGV),

          // --- the javax.tools API: what Gradle, Maven and the IDEs use ---
          // JavacTool#getTask(Writer, FileManager, DiagnosticListener,
          //                  Iterable<String> options, Iterable<String> classes, Iterable<FileObject>)
          new EntryPoint(
              "com.sun.tools.javac.api.JavacTool",
              "getTask",
              6,
              3,
              5,
              EntryPoint.Kind.FILE_OBJECTS),

          // --- the old javax.tools.JavaCompiler#run API ---
          new EntryPoint(
              "javax.tools.JavaCompiler",
              "run",
              4,
              3,
              -1,
              EntryPoint.Kind.ARGV),

          // --- Eclipse's compiler ---
          new EntryPoint(
              "org.eclipse.jdt.internal.compiler.batch.Main",
              "compile",
              1,
              0,
              -1,
              EntryPoint.Kind.ARGV),

          // --- the sjavac server ---
          new EntryPoint(
              "com.sun.tools.sjavac.client.ClientMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV),
          new EntryPoint(
              "com.sun.tools.sjavac.server.ServerMain", "run", 1, 0, -1, EntryPoint.Kind.ARGV));

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
