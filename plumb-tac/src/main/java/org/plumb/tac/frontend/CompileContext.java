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
package org.plumb.tac.frontend;

import java.nio.file.Path;
import java.util.List;

/**
 * Session-scoped compile context shared by every frontend in one analysis run.
 *
 * <p><b>Why this is session-scoped and not file-scoped.</b> Symbol resolution, type inference and
 * module dependency graphs must be shared across the whole run. If each file got its own context,
 * cross-file resolution would degrade into N full re-analyses -- quadratic, and it would also lose
 * the cross-file edges that the call graph is built from.
 *
 * <p>The context therefore owns:
 *
 * <ul>
 *   <li>the module structure (which source roots belong to which module, and their dependencies);
 *   <li>the classpath (for resolving types against binaries);
 *   <li>the symbol cache (so a symbol resolved once is resolved once);
 *   <li>the cross-language symbol table, which is what lets a Java call site resolve to a Kotlin
 *       definition and vice versa.
 * </ul>
 *
 * <p>A context is created once per analysis run and closed at the end. Implementations are expected
 * to be usable from multiple threads for read-only work.
 */
public interface CompileContext extends AutoCloseable {

  /** The modules this compilation covers, in dependency order. */
  List<Module> modules();

  /** The classpath entries (jars and class directories) available for type resolution. */
  List<Path> classpath();

  /**
   * The cross-language symbol table.
   *
   * <p>A frontend registers the symbols it declares here during a first pass, and consults the
   * table during the translation pass. That two-pass shape is what makes cross-language call
   * resolution work: a Java call site can find a Kotlin callee, and a Kotlin call site can find a
   * Java callee, without either frontend needing to understand the other's syntax.
   */
  SymbolTable symbols();

  /** Memoises a value under {@code key}. Used for parse results and resolved symbols. */
  <T> T computeIfAbsent(String key, java.util.function.Function<String, T> factory);

  /**
   * The module a source file belongs to, or {@code null} when there is no module boundary.
   *
   * <p>The answer is the module whose source root contains the file. It is what lets the engine
   * analyse ONE module of a multi-module project without breaking the call graph: the other modules
   * are still read and still resolved, so the edges across the boundary survive, and the module a
   * function came from is what decides whether its findings are reported.
   *
   * <p>The default implementation returns {@code null}, which means "no module boundary" -- the
   * single-file case.
   *
   * @param file the source file
   * @return the module's name, or {@code null}
   */
  default String moduleOf(Path file) {
    return null;
  }

  @Override
  void close();

  /**
   * One compilation module: a source root plus its classpath and its dependencies.
   *
   * @param name the module's name
   * @param sourceRoot the root directory holding the module's sources
   * @param sourceFiles the module's source files
   * @param classpath the module's compile classpath
   * @param dependsOn the modules this module depends on
   * @param languageLevel the language / language level this module is compiled at, as written in
   *     the build description (e.g. {@code "17"}, {@code "21"}, {@code "1.9"}, {@code "3.10"}). The
   *     frontend decides how to interpret it; {@code null} means "use the frontend's default".
   */
  record Module(
      String name,
      Path sourceRoot,
      List<Path> sourceFiles,
      List<Path> classpath,
      List<String> dependsOn,
      String languageLevel) {

    public Module {
      sourceFiles = sourceFiles == null ? List.of() : List.copyOf(sourceFiles);
      classpath = classpath == null ? List.of() : List.copyOf(classpath);
      dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
  }
}
