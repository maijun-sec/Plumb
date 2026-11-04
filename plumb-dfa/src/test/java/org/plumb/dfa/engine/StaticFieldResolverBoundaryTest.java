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
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The resolver root must be bounded, and the source-less consumer must not read source.
 *
 * <p><b>What went wrong.</b> {@code DfaEngine.withFunctionSourcePaths(..)} used to build a {@link
 * org.plumb.dfa.resolve.StaticFieldResolver} as a side effect. The resolver reads {@code .java}
 * files from disk, and the root was picked by walking UP from a sample file. Two failures followed:
 *
 * <ul>
 *   <li><b>The source-less consumer read source.</b> The whole point of the IR contract is that the
 *       analysis depends only on the IR. A consumer that quietly reads {@code .java} files off the
 *       filesystem is not source-less, and its answer depends on what happens to be lying around on
 *       the machine.
 *   <li><b>The walk was unbounded.</b> A file in a temporary directory made the resolver index the
 *       whole temporary tree -- tens of thousands of files -- once per analysis. The consumer tests
 *       took 795 seconds because of it.
 * </ul>
 *
 * <p>These tests pin both halves: the root walk is capped, and passing source paths is metadata
 * only.
 */
class StaticFieldResolverBoundaryTest {

  /**
   * A root holding more files than the budget must not be indexed whole, and must say so.
   *
   * <p>This is the property that bounds the walk. The old rule accepted "two levels up" without
   * counting anything, so a file in a temporary directory indexed the whole temporary tree.
   *
   * <p>Both halves matter: the walk stops, AND the resolver reports that it stopped. A partial
   * index that looks like a complete one silently loses cross-file resolutions.
   */
  @Test
  void aRootLargerThanTheBudgetIsNotIndexedWhole(@TempDir Path tmp) throws Exception {
    int over = org.plumb.dfa.resolve.SourcePathResolver.MAX_INDEXED_FILES + 5;
    Path big = tmp.resolve("big");
    Files.createDirectories(big);
    for (int i = 0; i < over; i++) {
      Files.writeString(big.resolve("C" + i + ".java"), "package p; class C" + i + " {}\n");
    }

    org.plumb.dfa.resolve.SourcePathResolver spr =
        new org.plumb.dfa.resolve.SourcePathResolver(big);
    spr.index();

    assertThat(spr.indexedFileCount())
        .as("a root over the budget must not be indexed whole")
        .isLessThanOrEqualTo(org.plumb.dfa.resolve.SourcePathResolver.MAX_INDEXED_FILES);
    assertThat(spr.indexTruncated())
        .as("an index that stopped at the budget must report that it stopped")
        .isTrue();
  }

  /** A small root is still indexed, so the fix does not turn the resolver off. */
  @Test
  void aSmallRootIsStillIndexed(@TempDir Path tmp) throws Exception {
    Path small = tmp.resolve("small");
    Files.createDirectories(small);
    Files.writeString(small.resolve("Helper.java"), "package p; public class Helper {}\n");

    org.plumb.dfa.resolve.SourcePathResolver spr =
        new org.plumb.dfa.resolve.SourcePathResolver(small);
    spr.index();

    assertThat(spr.indexedFileCount()).as("a small root must still be indexed").isEqualTo(1);
    assertThat(spr.resolveClass("p.Helper")).as("and its classes must still resolve").isNotNull();
  }

  /**
   * Registering source paths must NOT cause the engine to read any source.
   *
   * <p>The observable form of "the engine read source" is a {@link
   * org.plumb.dfa.resolve.StaticFieldResolver} being installed. If the paths are pure metadata, no
   * resolver is installed -- which is what makes the source-less guarantee hold.
   */
  @Test
  void registeringSourcePathsInstallsNoResolver(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("src");
    Files.createDirectories(dir);
    Path file = dir.resolve("Cmd.java");
    Files.writeString(file, "package p; class Cmd {}\n");

    Map<String, Path> paths = new LinkedHashMap<>();
    paths.put("p.Cmd", file);

    DfaEngine engine = new DfaEngine(config(), java.util.List.of(), "global", null);
    engine.withFunctionSourcePaths(paths);

    assertThat(engine.staticFieldResolverForTest())
        .as("passing source paths is metadata; it must not install a source-reading resolver")
        .isNull();
  }

  /** The opt-in still works when the caller asks for it explicitly. */
  @Test
  void theExplicitOptInStillInstallsAResolver(@TempDir Path tmp) throws Exception {
    Path dir = tmp.resolve("src");
    Files.createDirectories(dir);
    Files.writeString(
        dir.resolve("Helper.java"),
        "package p; public class Helper {\n" + "  public static final int ANSWER = 42;\n" + "}\n");

    DfaEngine engine = new DfaEngine(config(), java.util.List.of(), "global", null);
    engine.withStaticFieldResolverRoot(dir);

    assertThat(engine.staticFieldResolverForTest())
        .as("the explicit opt-in must still install a resolver")
        .isNotNull();
  }

  /** A root inside the budget is indexed completely, and says it is complete. */
  @Test
  void anIndexInsideTheBudgetIsComplete(@TempDir Path tmp) throws Exception {
    Path small = tmp.resolve("small");
    Files.createDirectories(small);
    Files.writeString(small.resolve("A.java"), "package p; public class A {}\n");
    Files.writeString(small.resolve("B.java"), "package p; public class B {}\n");

    org.plumb.dfa.resolve.SourcePathResolver spr =
        new org.plumb.dfa.resolve.SourcePathResolver(small);
    spr.index();

    assertThat(spr.indexTruncated()).as("a small root is indexed completely").isFalse();
    assertThat(spr.indexedFileCount()).isEqualTo(2);
    assertThat(spr.indexedFiles()).as("the index must expose the files it indexed").hasSize(2);
  }

  private static org.plumb.dfa.config.EngineConfig config() {
    return org.plumb.dfa.config.ConfigLoader.loadFromClasspath();
  }
}
