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
package org.plumb.tac.frontend.csharp;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.SymbolTable;
import org.plumb.tac.frontend.TacFrontend;

/**
 * Shared scaffolding for the C# frontend's tests.
 *
 * <p><b>Why the frontend is created per test class.</b> The C# frontend holds a
 * sidecar PROCESS, so each test class gets its own: the process is expensive to
 * start, and sharing one across classes would make the tests order-dependent.
 *
 * <p><b>Why the toolchain's absence is an explicit skip.</b> The C# frontend
 * needs a .NET toolchain, and not every machine that builds Plumb has one. A
 * test that silently passed because it analysed nothing would be worse than no
 * test at all -- it would report "the C# frontend works" on a machine where it
 * was never exercised. So the absence is a skip with a stated reason, which the
 * test report shows.
 */
final class CSharpTestSupport implements AutoCloseable {

  /** The frontend under test. */
  private final CSharpTacFrontend frontend;

  /** The compile context. */
  private final CompileContext context = new TestContext();

  /** Whether the .NET toolchain was found. */
  private final boolean toolchainAvailable;

  /**
   * Whether the .NET toolchain was found.
   *
   * <p>It is resolved in a STATIC INITIALIZER, not in the constructor: JUnit evaluates an
   * {@code @EnabledIf} condition BEFORE the lifecycle callbacks run, so a flag set in
   * {@code @BeforeAll} would still read {@code false} and every test would be skipped.
   */
  private static final boolean TOOLCHAIN_AVAILABLE =
      CSharpSidecarClient.locateExecutable(null) != null;

  CSharpTestSupport() {
    Path executable = CSharpSidecarClient.locateExecutable(null);
    this.toolchainAvailable = executable != null;
    this.frontend = new CSharpTacFrontend(executable);
  }

  /** Whether the .NET toolchain was found. */
  boolean toolchainAvailable() {
    return toolchainAvailable;
  }

  /**
   * Whether the .NET toolchain is available, for a JUnit {@code @EnabledIf} condition.
   *
   * @return true when the sidecar was found
   */
  static boolean toolchainPresent() {
    return TOOLCHAIN_AVAILABLE;
  }

  /** The frontend under test. */
  CSharpTacFrontend frontend() {
    return frontend;
  }

  /** The compile context the frontend was built over. */
  CompileContext context() {
    return context;
  }

  /**
   * Parses a C# source.
   *
   * @param source the source text
   * @return the parse result
   */
  TacFrontend.ParseResult parse(String source) {
    return frontend.parse(source, Path.of("Test.cs"), context);
  }

  /**
   * Parses a C# source with the STRUCTURED problems.
   *
   * @param source the source text
   * @return the structured parse result
   */
  TacFrontend.StructuredParseResult parseStructured(String source) {
    return frontend.parseStructured(source, Path.of("Test.cs"), context);
  }

  @Override
  public void close() {
    frontend.close();
    context.close();
  }

  /** A minimal compile context over no source roots. */
  private static final class TestContext implements CompileContext {

    @Override
    public List<Module> modules() {
      return List.of();
    }

    @Override
    public List<Path> classpath() {
      return List.of();
    }

    @Override
    public SymbolTable symbols() {
      return new SymbolTable() {
        @Override
        public void register(String ownerType, String methodName, int arity, String signature) {}

        @Override
        public Optional<String> lookup(String ownerType, String methodName, int arity) {
          return Optional.empty();
        }

        @Override
        public List<String> lookupByName(String methodName, int arity) {
          return List.of();
        }
      };
    }

    @Override
    public <T> T computeIfAbsent(String key, java.util.function.Function<String, T> factory) {
      return factory.apply(key);
    }

    @Override
    public void close() {}
  }
}
