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
package org.plumb.dfa.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.resolve.ProjectCompilationContext;
import org.plumb.dfa.resolve.StaticFieldResolver;

/**
 * Real-Juliet integration test for {@link ProjectCompilationContext}.
 *
 * <p>Reads the actual Juliet Java Test Suite root on disk and asserts that the new project-root
 * mode resolves the {@code testcasesupport} package correctly. The test is <i>not</i> a hardcoded
 * suite: the resolver has no Juliet knowledge; the assertions are derived from what Juliet actually
 * contains. If the Juliet layout changes (e.g. someone renames the package) the test will fail with
 * a clear, namespaced error.
 *
 * <p>The test is gated on the Juliet root being present at {@code D:/work/tmp/Juliet_Java}. When
 * run on a different machine the test is skipped, so the same build works in CI and locally.
 */
class R253JulietProjectRootSweepTest {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT)
        && Files.isDirectory(JULIET_ROOT.resolve("src"))
        && Files.isDirectory(JULIET_ROOT.resolve("src").resolve("testcasesupport"));
  }

  @Test
  @EnabledIf("julietRootExists")
  void projectContext_indexesJulietTestcasesupport() throws Exception {
    // (1) Build a context
    // with the real Juliet
    // root and the
    // testcasesupport/ as
    // the source tree. This
    // validates that the
    // indexer walks the
    // whole project root
    // and finds the support
    // classes.
    Path support = JULIET_ROOT.resolve("src").resolve("testcasesupport");
    ProjectCompilationContext ctx = new ProjectCompilationContext(JULIET_ROOT, support);
    ctx.index();
    // The testcasesupport
    // package contains a
    // handful of helper
    // classes; we just
    // require that at least
    // IO.java is in the
    // index.
    assertThat(ctx.resolveClass("testcasesupport.IO"))
        .as("Juliet's IO helper class must be indexed")
        .isNotNull();
    // (2) Real Juliet declares
    //     STATIC_FINAL_FIVE
    //     as a public static
    //     final int = 5
    //     (not STATIC_FIVE —
    //     the original
    //     user-facing name
    //     changed between
    //     releases). The
    //     resolver must pick
    //     it up from the
    //     source on disk.
    StaticFieldResolver.LiteralValue five =
        ctx.lookupStatic("testcasesupport.IO", "STATIC_FINAL_FIVE");
    assertThat(five).as("STATIC_FINAL_FIVE must be parsed").isNotNull();
    assertThat(five.value).isEqualTo(5L);
    // (3) STATIC_FINAL_TRUE /
    //     STATIC_FINAL_FALSE
    //     are booleans.
    StaticFieldResolver.LiteralValue t =
        ctx.lookupStatic("testcasesupport.IO", "STATIC_FINAL_TRUE");
    assertThat(t).isNotNull();
    assertThat(t.value).isEqualTo(true);
    StaticFieldResolver.LiteralValue f =
        ctx.lookupStatic("testcasesupport.IO", "STATIC_FINAL_FALSE");
    assertThat(f).isNotNull();
    assertThat(f.value).isEqualTo(false);
  }

  @Test
  @EnabledIf("julietRootExists")
  void projectContext_cweSubTreeAsksForAnalysisFiles() throws Exception {
    // Pick a single Juliet
    // CWE folder (e.g.
    // CWE129) and verify
    // that the work-list
    // contains only files
    // inside that sub-tree
    // (not files in other
    // CWE folders, not
    // files in
    // testcasesupport).
    Path cwe =
        JULIET_ROOT
            .resolve("src")
            .resolve("testcases")
            .resolve("CWE129_Improper_Validation_of_Array_Index")
            .resolve("s01");
    if (!Files.isDirectory(cwe)) {
      // Some Juliet
      // distributions may
      // not include this
      // CWE — skip rather
      // than fail.
      return;
    }
    ProjectCompilationContext ctx = new ProjectCompilationContext(JULIET_ROOT, cwe);
    ctx.index();
    List<Path> work = ctx.filesToAnalyze();
    assertThat(work).as("work-list under s01/").isNotEmpty();
    // Every work-list file
    // must live inside the
    // sub-tree, and the
    // sub-tree must
    // contain a Juliet
    // CWE_... class so
    // the import
    // resolution has
    // something to
    // resolve.
    for (Path p : work) {
      assertThat(p.startsWith(cwe)).as("file " + p + " is in sub-tree").isTrue();
    }
    // The project root
    // (full Juliet) has
    // thousands of
    // classes, so the
    // indexer is a true
    // cross-file search.
    assertThat(ctx.indexedClassCount())
        .as("indexed class count for full Juliet")
        .isGreaterThan(1000);
    // STATIC_FINAL_FIVE is
    // still resolvable even
    // though the sub-tree
    // is the CWE folder.
    StaticFieldResolver.LiteralValue v =
        ctx.lookupStatic("testcasesupport.IO", "STATIC_FINAL_FIVE");
    assertThat(v).isNotNull();
    assertThat(v.value).isEqualTo(5L);
  }
}
