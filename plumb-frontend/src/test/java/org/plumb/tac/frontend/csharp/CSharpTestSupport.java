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

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.SymbolTable;
import org.plumb.tac.frontend.TacFrontend;

/**
 * Shared scaffolding for the C# frontend's tests.
 *
 * <p><b>Why the frontend is created per test class.</b> The C# frontend holds a sidecar PROCESS, so
 * each test class gets its own: the process is expensive to start, and sharing one across classes
 * would make the tests order-dependent.
 *
 * <p><b>Why the toolchain's absence is an explicit skip.</b> The C# frontend needs a .NET
 * toolchain, and not every machine that builds Plumb has one. A test that silently passed because
 * it analysed nothing would be worse than no test at all -- it would report "the C# frontend works"
 * on a machine where it was never exercised. So the absence is a skip with a stated reason, which
 * the test report shows.
 */
final class CSharpTestSupport implements AutoCloseable {

  /** The frontend under test. */
  private final CSharpTacFrontend frontend;

  /** The compile context. */
  private final CompileContext context = new TestContext();

  /**
   * Where the C# sidecar is, or null when it is not on this machine.
   *
   * <p>It is resolved in a STATIC INITIALIZER, so the answer is computed once for the whole JVM:
   * the lookup walks the disk, and eight test classes would otherwise each repeat it -- and eight
   * answers to one question are eight chances to drift apart.
   */
  private static final Path EXECUTABLE = CSharpSidecarClient.locateExecutable(null);

  /** Whether the .NET toolchain was found. */
  private static final boolean TOOLCHAIN_AVAILABLE = EXECUTABLE != null;

  CSharpTestSupport() {
    this.frontend = new CSharpTacFrontend(EXECUTABLE);
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
    assumeTheSidecarIsThere();
    return frontend.parse(source, Path.of("Test.cs"), context);
  }

  /**
   * Parses a C# source with the STRUCTURED problems.
   *
   * @param source the source text
   * @return the structured parse result
   */
  TacFrontend.StructuredParseResult parseStructured(String source) {
    assumeTheSidecarIsThere();
    return frontend.parseStructured(source, Path.of("Test.cs"), context);
  }

  /**
   * The sidecar has to be there for a parse to mean anything.
   *
   * <p>The condition lives HERE, and not in the test classes, for two reasons. There is ONE copy of
   * it in the whole suite, so the question cannot drift between the classes that ask it. And it
   * sits on the operation that needs the sidecar, so a test that never reaches the sidecar -- the
   * catalogue's language filter, the language registry -- keeps running on a machine that has no
   * sidecar, instead of being skipped for a reason that has nothing to do with it.
   *
   * <p>It ABORTS the test rather than disabling it, because a disabled test is reported with the
   * condition's name and nothing else -- "@EnabledIf evaluated to false" -- while an aborted test
   * is reported with the sentence that says what to do about it: {@link
   * CSharpSidecarClient#NOT_BUILT}.
   */
  private static void assumeTheSidecarIsThere() {
    assumeTrue(TOOLCHAIN_AVAILABLE, CSharpSidecarClient.NOT_BUILT);
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
