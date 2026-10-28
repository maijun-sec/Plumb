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
package org.plumb.parser;

import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One parsed Java source file: the {@link CompilationUnit} plus any problems reported by JavaParser
 * and a few convenience fields (top-level type name, declared package).
 *
 * <p><b>Type/symbol resolution.</b> {@link ParsedSource} carries an optional {@link TypeSolver}
 * supplied by {@link JavaSourceParser}. Callers that need to resolve a type / symbol can read
 * {@link #typeSolver()} and call {@code symbolSolver.resolveType(...)} on the returned solver. The
 * JavaParser's symbol resolution path ({@code node.resolve()}, {@code
 * node.calculateResolvedType()}) works through the same solver because the {@code JavaSymbolSolver}
 * was attached to the {@code ParserConfiguration} at parse time.
 */
public final class ParsedSource {

  private final Path path;
  private final String source;
  private final CompilationUnit unit;
  private final List<Problem> problems;
  private final TypeSolver typeSolver;

  public ParsedSource(Path path, String source, CompilationUnit unit, List<Problem> problems) {
    this(path, source, unit, problems, null);
  }

  public ParsedSource(
      Path path,
      String source,
      CompilationUnit unit,
      List<Problem> problems,
      TypeSolver typeSolver) {
    this.path = Objects.requireNonNull(path, "path");
    this.source = Objects.requireNonNull(source, "source");
    this.unit = Objects.requireNonNull(unit, "unit");
    this.problems = List.copyOf(problems);
    this.typeSolver = typeSolver;
  }

  public Path path() {
    return path;
  }

  public String source() {
    return source;
  }

  public CompilationUnit unit() {
    return unit;
  }

  public List<Problem> problems() {
    return Collections.unmodifiableList(problems);
  }

  public boolean hasProblems() {
    return !problems.isEmpty();
  }

  /**
   * the type solver attached to the parser, or {@code null} if symbol resolution was not enabled.
   */
  public TypeSolver typeSolver() {
    return typeSolver;
  }

  public String packageName() {
    return unit.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
  }

  /** Simple "{@code pkg.TypeName}" or just "{@code TypeName}" for default package. */
  public String primaryTypeName() {
    return unit.getTypes().stream()
        .findFirst()
        .map(
            t -> {
              String pkg = packageName();
              String name = t.getNameAsString();
              return pkg.isEmpty() ? name : pkg + "." + name;
            })
        .orElse("<unknown>");
  }
}
