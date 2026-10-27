package org.plumb.build.capture;

import net.bytebuddy.asm.Advice;
import javax.tools.JavaFileObject;

/**
 * The advice that records the sources the compiler ACTUALLY parsed.
 *
 * <p><b>Why the argv is not enough.</b> The command line names the sources the compiler was
 * <em>given</em>. The compiler also compiles the sources it finds <em>itself</em>, through the source
 * path: a class that is referenced but not named is looked up on the source path, parsed and
 * compiled. A capture that lists only the command-line sources therefore misses code the compiler
 * really compiled -- and that code is exactly what a static analyser has to look at.
 *
 * <p><b>Why the parse method.</b> {@code JavaCompiler#parse(JavaFileObject, CharSequence)} is the
 * one place where every parsed source passes through, whichever way the compiler got there:
 *
 * <ul>
 *   <li>The sources the compiler was <em>given</em> go through {@code parseFiles}, which calls
 *       {@code parse(JavaFileObject)}, which calls {@code parse(file, content)}.
 *   <li>The sources the compiler <em>found on the source path</em> go through {@code
 *       readSourceFile}, which also calls {@code parse(file, content)}.
 * </ul>
 *
 * <p>So one hook on that method sees exactly the sources the compiler parsed -- neither more nor
 * fewer. A source in the source path that nobody referenced is never parsed, so it never appears.
 *
 * <p><b>Why not the file manager's content cache.</b> That looks like the obvious place -- it is
 * the compiler's own record of the files it read -- and it was the first thing tried. It does not
 * work: the compiler reads a source file's content through the file object, not through the
 * manager's cache, so the cache is <em>empty</em> after a normal compile. That was measured, not
 * assumed: a real compile of one explicit source plus one source-path source left the cache with
 * zero entries. A cache that never fills cannot answer the question.
 *
 * <p><b>Why the reading happens HERE.</b> ByteBuddy's {@code Advice} is <em>inlined</em> into the
 * target method, and the inlined code runs inside the {@code jdk.compiler} module. The parsed
 * file is the compiler's own object, and {@code javax.tools} is visible there -- {@code jdk.compiler
 * } requires {@code java.compiler} transitively -- so the advice can name the type directly.
 *
 * <p>What the advice may <em>not</em> do is call a class in the agent's own class loader: a named
 * module does not read the unnamed module, the call fails with a linkage error, and the advice's
 * {@code suppress = Throwable} swallows it silently. So the advice reads what it can and hands it
 * to {@link CompiledSourcesBridge}, which lives on the bootstrap loader.
 */
public final class CompiledSourcesAdvice {

  private CompiledSourcesAdvice() {}

  /**
   * The advice on the compiler's parse method.
   *
   * <p>The argument is the file the compiler is about to parse. Its kind tells a source from a
   * class file, and its name is the path -- an absolute one for both the explicit sources and the
   * ones found on the source path.
   */
  public static final class ParsedSource {

    private ParsedSource() {}

    /**
     * Record the source the compiler parsed.
     *
     * <p>Only sources are recorded. The compiler also parses class files when it reads them, and a
     * class file is not a source the analyser has to look at.
     */
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Argument(0) JavaFileObject file) {
      if (file == null) {
        return;
      }
      if (file.getKind() != JavaFileObject.Kind.SOURCE) {
        return;
      }
      CompiledSourcesBridge.recordParsed(file.getName());
    }
  }
}
