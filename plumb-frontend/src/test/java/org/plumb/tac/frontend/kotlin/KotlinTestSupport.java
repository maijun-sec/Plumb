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
package org.plumb.tac.frontend.kotlin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession;
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilderKt;
import org.plumb.tac.frontend.CompileContext;
import org.plumb.tac.frontend.SymbolTable;
import org.plumb.tac.frontend.TacFrontend;

/**
 * Shared scaffolding for the Kotlin frontend's tests.
 *
 * <p><b>Why the session is a JVM-wide singleton.</b> The Analysis API's standalone
 * application is a global singleton: {@code buildStandaloneAnalysisAPISession} sets up an
 * embedded IntelliJ application, and {@code disposeGlobalStandaloneApplicationServices}
 * tears it down for the whole JVM. Two consequences follow, and both shape this class:
 *
 * <ul>
 *   <li>A second session cannot be built after the global services have been released.
 *       So the session is built once, lazily, and shared by every test class.
 *   <li>The session is expensive to build (it initialises the IntelliJ platform), so
 *       building it once is also the fast option.
 * </ul>
 *
 * <p><b>Why the session outlives the test classes.</b> Surefire runs every test class in
 * one JVM, and the session's own lifetime is JVM-global. The session is therefore
 * released by a JVM shutdown hook rather than by a test class's {@code @AfterAll}, so the
 * order in which the classes run cannot break the classes that run after it.
 *
 * <p><b>Why the source is written to disk.</b> The Analysis API resolves files that are
 * on disk and inside the session's content scope. The frontend stages the source under
 * the session's source root; the test harness hands it a root so the staging lands
 * somewhere disposable.
 */
final class KotlinTestSupport {

  /**
   * The root every test class stages its source under.
   *
   * <p>Declared before {@link SessionHolder} because the holder's initialiser reads it:
   * static initialisers run in declaration order.
   */
  private static final Path ROOT = createRoot();

  /**
   * The shared session, built on first use.
   *
   * <p>Held in a holder class so the session is built exactly once even when several
   * test classes initialise at the same time.
   */
  private static final class SessionHolder {
    static final StandaloneAnalysisAPISession INSTANCE = build();

    private SessionHolder() {}

    /** Builds the session over a shared source root and installs the shutdown hook. */
    static StandaloneAnalysisAPISession build() {
      com.intellij.openapi.Disposable disposable =
          com.intellij.openapi.util.Disposer.newDisposable("plumb-kotlin-test-session");
      StandaloneAnalysisAPISession session =
          buildSession(disposable, KotlinTestSupport.ROOT);
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      session.getApplication()
                          .runWriteAction(
                              () -> com.intellij.openapi.util.Disposer.dispose(disposable));
                    } finally {
                      StandaloneAnalysisAPISessionBuilderKt
                          .disposeGlobalStandaloneApplicationServices();
                    }
                  },
                  "plumb-kotlin-test-shutdown"));
      return session;
    }
  }

  /** Creates the shared source root. */
  private static Path createRoot() {
    try {
      Path root = Files.createTempDirectory("plumb-kotlin-frontend-test");
      // A placeholder, so the source root is not empty when the session is built.
      Files.writeString(root.resolve("Placeholder.kt"), "package placeholder\n");
      return root;
    } catch (IOException ex) {
      throw new IllegalStateException("could not create the test's source root", ex);
    }
  }

  /**
   * The library roots the session resolves against.
   *
   * <p>Without them the Analysis API cannot resolve {@code StringBuilder}, {@code Regex},
   * or any other class that comes from a library, and every type that comes from one
   * resolves to an <i>error type</i> -- which would make every library-typed value
   * unknown, and would lose the nullability the Kotlin frontend exists to provide.
   *
   * <p>Two sources:
   *
   * <ul>
   *   <li>The Kotlin standard library, found on the running JVM's classpath. It is an
   *       ordinary jar, so the session's file system can index it.
   *   <li>The JDK's own classes. They live in the runtime image ({@code lib/modules}),
   *       which the session's virtual file system cannot mount -- it has no JRT file
   *       system. The JDK's {@code lib/ct.sym} is the closest mountable equivalent: it
   *       is an ordinary ZIP of the JDK's API signatures, and it is what the Kotlin
   *       compiler itself uses as a library root.
   * </ul>
   *
   * @return the roots, empty when none could be located
   */
  static List<Path> libraryRoots() {
    List<Path> roots = new ArrayList<>();
    // The Kotlin standard library, wherever it is on this JVM's classpath. It is an
    // ordinary jar, so the session's file system can index it.
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      if (!entry.endsWith("kotlin-stdlib.jar") && !entry.contains("kotlin-stdlib-")) {
        continue;
      }
      Path path = Path.of(entry);
      if (Files.exists(path) && !roots.contains(path)) {
        roots.add(path);
      }
    }
    return roots;
  }

  /** The root the session treats as its source root. */
  private final Path sourceRoot;

  /** The frontend under test. */
  private final KotlinTacFrontend frontend;

  /** The compile context, so a test can read the modules it declared. */
  private final CompileContext context;

  KotlinTestSupport() {
    this.sourceRoot = ROOT;
    StandaloneAnalysisAPISession session = SessionHolder.INSTANCE;
    this.frontend = new KotlinTacFrontend(session, null, List.of(this.sourceRoot));
    this.context = new TestContext(this.sourceRoot);
  }

  /** Builds the Analysis API session over one source root. */
  private static StandaloneAnalysisAPISession buildSession(
      com.intellij.openapi.Disposable disposable, Path root) {
    final KotlinModuleStructure structure =
        new KotlinModuleStructure(List.of(root), List.of());
    return StandaloneAnalysisAPISessionBuilderKt.buildStandaloneAnalysisAPISession(
        disposable,
        false,
        new kotlin.jvm.functions.Function1<
            org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder,
            kotlin.Unit>() {
          @Override
          public kotlin.Unit invoke(
              org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISessionBuilder
                  builder) {
            builder.buildKtModuleProvider(
                new kotlin.jvm.functions.Function1<
                    org.jetbrains.kotlin.analysis.project.structure.builder
                        .KaModuleContainerBuilder,
                    kotlin.Unit>() {
                  @Override
                  public kotlin.Unit invoke(
                      org.jetbrains.kotlin.analysis.project.structure.builder
                          .KaModuleContainerBuilder moduleProvider) {
                    structure.addTo(moduleProvider);
                    return kotlin.Unit.INSTANCE;
                  }
                });
            return kotlin.Unit.INSTANCE;
          }
        });
  }

  /** The frontend under test. */
  KotlinTacFrontend frontend() {
    return frontend;
  }

  /** The compile context the frontend was built over. */
  CompileContext context() {
    return context;
  }

  /** The session's source root. */
  Path sourceRoot() {
    return sourceRoot;
  }

  /** The shared Analysis API session, for a test that inspects it directly. */
  StandaloneAnalysisAPISession session() {
    return SessionHolder.INSTANCE;
  }

  /**
   * Parses a Kotlin source.
   *
   * @param source the source text
   * @return the parse result
   */
  TacFrontend.ParseResult parse(String source) {
    return frontend.parse(source, sourceRoot.resolve("Test.kt"), context);
  }

  /** Removes the test's temporary directory. */
  void cleanUp() {
    if (sourceRoot == null || !Files.exists(sourceRoot)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(sourceRoot)) {
      List<Path> paths = new ArrayList<>();
      walk.forEach(paths::add);
      paths.sort(Comparator.reverseOrder());
      for (Path path : paths) {
        Files.deleteIfExists(path);
      }
    } catch (IOException ex) {
      // Best effort.
    }
  }

  /** A minimal compile context over one source root. */
  private static final class TestContext implements CompileContext {
    private final Path root;

    TestContext(Path root) {
      this.root = root;
    }

    @Override
       public List<Module> modules() {
      return List.of(new Module("test", root, List.of(), List.of(), List.of(), null));
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
        public java.util.Optional<String> lookup(String ownerType, String methodName, int arity) {
          return java.util.Optional.empty();
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

  /** Writes a file into the session's source root. */
  Path write(String name, String content) {
    try {
      Path file = sourceRoot.resolve(name);
      Files.createDirectories(file.getParent());
      Files.write(file, content.getBytes(StandardCharsets.UTF_8));
      return file;
    } catch (IOException ex) {
      throw new IllegalStateException("could not write " + name, ex);
    }
  }
}
