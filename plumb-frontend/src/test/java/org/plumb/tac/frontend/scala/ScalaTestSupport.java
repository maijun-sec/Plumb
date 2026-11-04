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
package org.plumb.tac.frontend.scala;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.SymbolTable;
import org.plumb.tac.frontend.TacFrontend;

/**
 * Shared scaffolding for the Scala frontend's tests.
 *
 * <p><b>Why the frontend is created per test class rather than shared.</b> The Scala
 * frontend holds no state between parses -- each parse creates its own compiler
 * context, plugin and phase -- so a single instance could serve every test. It is
 * created here once per test class anyway, because that mirrors how a caller uses it
 * and because it keeps the tests independent of the order they run in.
 *
 * <p><b>Why the source is written to disk by the frontend.</b> dotc compiles files, not
 * strings: it needs a real path to report positions against and to write class files
 * next to. The frontend therefore stages the source in a temporary directory and
 * removes it again, which is why a test only has to hand it the source text.
 */
final class ScalaTestSupport {

  /** The frontend under test. */
  private final ScalaTacFrontend frontend;

  /** The compile context, so a test can read the modules it declared. */
  private final CompileContext context;

  ScalaTestSupport() {
    this.frontend = new ScalaTacFrontend();
    this.context = new TestContext();
  }

  /** The frontend under test. */
  ScalaTacFrontend frontend() {
    return frontend;
  }

  /** The compile context the frontend was built over. */
  CompileContext context() {
    return context;
  }

  /**
   * Parses a Scala source.
   *
   * @param source the source text
   * @return the parse result
   */
  TacFrontend.ParseResult parse(String source) {
    return frontend.parse(source, Path.of("Test.scala"), context);
  }

  /**
   * Parses a Scala source with the STRUCTURED problems.
   *
   * <p>This is the form a source-less consumer receives: the functions AND the problems that
   * say which constructs were not lowered.
   *
   * @param source the source text
   * @return the structured parse result
   */
  TacFrontend.StructuredParseResult parseStructured(String source) {
    return frontend.parseStructured(source, Path.of("Test.scala"), context);
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
