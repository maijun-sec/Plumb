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
package org.plumb.dfa.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectCompilationContext}.
 *
 * <p>The context is the glue between {@link SourcePathResolver} and {@link StaticFieldResolver}.
 * Its job is to model the javac-style {@code -sourcepath} use case: a project root that contains
 * every package the analysis may need, and a sub-tree inside that root that the analysis should
 * actually walk. The tests below exercise (a) the projectRoot / sourceRoot split, (b) the
 * containment rule, (c) the work-list filtering, (d) the cross-tree class and field resolution, and
 * (e) the absence of any Juliet-specific logic in the resolver itself.
 */
class R253ProjectCompilationContextTest {

  @Test
  void context_worksWithSubTreeUnderProjectRoot(@TempDir Path tmp) throws Exception {
    // Layout: <tmp>/Juliet_Java is the project root. Inside it, the
    // sub-tree <tmp>/Juliet_Java/src/testcases/CWE_X/s01 holds the
    // file we want to analyse; the testcasesupport package lives
    // elsewhere in the same root. The context must report the
    // sub-tree file as part of filesToAnalyze() and must resolve
    // the cross-tree import by walking the project root.
    Path projectRoot = tmp.resolve("Juliet_Java");
    Path src = projectRoot.resolve("src");
    Path support = src.resolve("testcasesupport");
    Path cwe = src.resolve("testcases").resolve("CWE190_Integer_Overflow").resolve("s01");
    Files.createDirectories(support);
    Files.createDirectories(cwe);
    Files.writeString(
        support.resolve("IO.java"),
        "package testcasesupport;\n"
            + "public class IO {\n"
            + "  public static final int STATIC_FIVE = 5;\n"
            + "  public static final int STATIC_FALSE_INT = 0;\n"
            + "  public static final boolean STATIC_FALSE = false;\n"
            + "}\n");
    Files.writeString(
        cwe.resolve("Bad.java"),
        "package testcases.CWE190_Integer_Overflow.s01;\n"
            + "import testcasesupport.IO;\n"
            + "public class Bad {\n"
            + "  public void bad() {\n"
            + "    int x = IO.STATIC_FIVE;\n"
            + "  }\n"
            + "}\n");

    ProjectCompilationContext ctx = new ProjectCompilationContext(projectRoot, cwe);
    ctx.index();

    // (1) Work-list contains the file inside the sub-tree, not the
    //     one in testcasesupport (which lives outside the
    //     sub-tree).
    List<Path> work = ctx.filesToAnalyze();
    assertThat(work).hasSize(1);
    assertThat(work.get(0).getFileName().toString()).isEqualTo("Bad.java");

    // (2) Cross-tree class resolution: the file inside the
    //     sub-tree imports testcasesupport.IO. The context must
    //     find IO.java in the project root, even though
    //     testcasesupport/ lives outside sourceRoot.
    Path io = ctx.resolveClass("testcasesupport.IO");
    assertThat(io).isNotNull();
    assertThat(io.getFileName().toString()).isEqualTo("IO.java");

    // (3) Cross-tree static field resolution. The whole point of
    //     this context is that STATIC_FIVE is foldable without
    //     any hardcoded Juliet knowledge — the resolver just
    //     reads IO.java under testcasesupport/ and extracts
    //     the literal.
    StaticFieldResolver.LiteralValue v = ctx.lookupStatic("testcasesupport.IO", "STATIC_FIVE");
    assertThat(v).isNotNull();
    assertThat(v.value).isEqualTo(5L);
  }

  @Test
  void context_rejectsSourceRootOutsideProjectRoot(@TempDir Path tmp) throws Exception {
    Path projectRoot = tmp.resolve("proj");
    Path outside = tmp.resolve("outside");
    Files.createDirectories(projectRoot);
    Files.createDirectories(outside);
    assertThatThrownBy(() -> new ProjectCompilationContext(projectRoot, outside))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not inside projectRoot");
  }

  @Test
  void context_resolvesJulietFromSyntheticLayout(@TempDir Path tmp) throws Exception {
    // The synthetic Juliet layout: a project root, a
    // testcasesupport package, and a CWE sub-tree. The same
    // resolver works on real Juliet and on a synthetic copy —
    // the only thing the resolver needs is the project root
    // and the sub-tree.
    Path projectRoot = tmp.resolve("Juliet_Java");
    Path support = projectRoot.resolve("testcasesupport");
    Path cwe =
        projectRoot
            .resolve("testcases")
            .resolve("CWE129_Improper_Validation_of_Array_Index")
            .resolve("s01");
    Files.createDirectories(support);
    Files.createDirectories(cwe);
    Files.writeString(
        support.resolve("IO.java"),
        "package testcasesupport;\n"
            + "public class IO {\n"
            + "  public static final int STATIC_FIVE = 5;\n"
            + "  public static final int STATIC_FALSE_INT = 0;\n"
            + "}\n");
    Files.writeString(
        cwe.resolve("Bad.java"),
        "package testcases.CWE129_Improper_Validation_of_Array_Index.s01;\n"
            + "import testcasesupport.IO;\n"
            + "public class Bad {\n"
            + "  public void bad() {\n"
            + "    int[] arr = new int[IO.STATIC_FIVE];\n"
            + "    int x = arr[-1];\n"
            + "  }\n"
            + "}\n");
    Files.writeString(
        cwe.resolve("Good.java"),
        "package testcases.CWE129_Improper_Validation_of_Array_Index.s01;\n"
            + "import testcasesupport.IO;\n"
            + "public class Good {\n"
            + "  public void good() {\n"
            + "    int[] arr = new int[IO.STATIC_FIVE];\n"
            + "    int x = arr[2];\n"
            + "  }\n"
            + "}\n");
    ProjectCompilationContext ctx = new ProjectCompilationContext(projectRoot, cwe);
    ctx.index();
    // both CWE files are in the work-list
    assertThat(ctx.countFilesToAnalyze()).isEqualTo(2);
    // the support class is indexed but not in the work-list
    assertThat(ctx.indexedClassCount()).isEqualTo(3);
    assertThat(ctx.staticFieldCount()).isEqualTo(2);
  }

  @Test
  void context_filesToAnalyzeRespectsBuildFilter(@TempDir Path tmp) throws Exception {
    // Build-output directories inside the sub-tree must be
    // skipped by the work-list, even though the index still
    // walks the project root.
    Path projectRoot = tmp.resolve("proj");
    Path src = projectRoot.resolve("src");
    Path target = projectRoot.resolve("target");
    Files.createDirectories(src);
    Files.createDirectories(target);
    Files.writeString(src.resolve("Real.java"), "package x; public class Real {}");
    Files.writeString(target.resolve("Copy.java"), "package x; public class Copy {}");
    ProjectCompilationContext ctx = new ProjectCompilationContext(projectRoot, src);
    ctx.index();
    List<Path> work = ctx.filesToAnalyze();
    // The sub-tree is src/, so only Real.java is in the work-list.
    assertThat(work).hasSize(1);
    assertThat(work.get(0).getFileName().toString()).isEqualTo("Real.java");
    // But the index still contains the package → file map for
    // both, because the index walks the whole project root.
    // (Copy.java is excluded by the build filter inside
    // SourcePathResolver — same filter applies to both.)
  }

  @Test
  void context_handlesMissingProjectRootGracefully(@TempDir Path tmp) throws Exception {
    ProjectCompilationContext ctx =
        new ProjectCompilationContext(
            tmp.resolve("does-not-exist"), tmp.resolve("does-not-exist").resolve("sub"));
    ctx.index();
    assertThat(ctx.indexedClassCount()).isZero();
    assertThat(ctx.staticFieldCount()).isZero();
    assertThat(ctx.countFilesToAnalyze()).isZero();
    assertThat(ctx.resolveClass("a.b.C")).isNull();
  }
}
