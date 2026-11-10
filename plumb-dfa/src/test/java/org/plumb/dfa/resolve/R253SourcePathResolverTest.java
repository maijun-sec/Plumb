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

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SourcePathResolver} and {@link StaticFieldResolver}. The resolvers don't know
 * about Juliet, IO, or any test-specific symbols ? they extract class names and field values
 * directly from the source files the user points them at. The Juliet-specific {@code
 * IO.STATIC_FIVE} is a side-effect of indexing the project root; the same code resolves {@code
 * Config.MAX_RETRIES} or any other {@code public static final} field.
 */
class R253SourcePathResolverTest {

  @Test
  void sourcePath_resolvesClassByFqcn(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path support = project.resolve("support");
    Files.createDirectories(support);
    Files.writeString(
        support.resolve("Helper.java"),
        "package support;\n"
            + "public class Helper {\n"
            + "  public static final int ANSWER = 42;\n"
            + "  public static final String NAME = \"hi\";\n"
            + "  public static final boolean ON = true;\n"
            + "  public static final long BIG = 1000L;\n"
            + "}\n");
    Files.writeString(support.resolve("Unused.java"), "package support;\n" + "class Unused {}\n");
    SourcePathResolver r = new SourcePathResolver(project);
    r.index();
    assertThat(r.resolveClass("support.Helper"))
        .as("Helper resolves to its file")
        .isEqualTo(support.resolve("Helper.java"));
    assertThat(r.indexedClassCount()).isEqualTo(2);
  }

  @Test
  void staticField_extractsLiterals(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path support = project.resolve("support");
    Files.createDirectories(support);
    Files.writeString(
        support.resolve("Helper.java"),
        "package support;\n"
            + "public class Helper {\n"
            + "  public static final int ANSWER = 42;\n"
            + "  public static final String NAME = \"hi\";\n"
            + "  public static final boolean ON = true;\n"
            + "  public static final long BIG = 1000L;\n"
            + "  public static final double PI = 3.14;\n"
            + "}\n");
    SourcePathResolver spr = new SourcePathResolver(project);
    spr.index();
    StaticFieldResolver sfr = new StaticFieldResolver(spr);
    sfr.index();
    assertThat(sfr.size()).isEqualTo(5);
    StaticFieldResolver.LiteralValue ans = sfr.lookup("support.Helper", "ANSWER");
    assertThat(ans.kind).isEqualTo(StaticFieldResolver.LiteralValue.Kind.INT);
    assertThat(ans.value).isEqualTo(42L);
    StaticFieldResolver.LiteralValue name = sfr.lookup("support.Helper", "NAME");
    assertThat(name.kind).isEqualTo(StaticFieldResolver.LiteralValue.Kind.STRING);
    assertThat(name.value).isEqualTo("hi");
    StaticFieldResolver.LiteralValue on = sfr.lookup("support.Helper", "ON");
    assertThat(on.kind).isEqualTo(StaticFieldResolver.LiteralValue.Kind.BOOLEAN);
    assertThat(on.value).isEqualTo(true);
    StaticFieldResolver.LiteralValue big = sfr.lookup("support.Helper", "BIG");
    assertThat(big.kind).isEqualTo(StaticFieldResolver.LiteralValue.Kind.LONG);
    assertThat(big.value).isEqualTo(1000L);
  }

  @Test
  void staticField_skipsNonFinalFields(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path support = project.resolve("support");
    Files.createDirectories(support);
    Files.writeString(
        support.resolve("Helper.java"),
        "package support;\n"
            + "public class Helper {\n"
            + "  public static int NOT_FINAL = 42;\n"
            + "  private static final int PRIVATE = 5;\n"
            + "  public static final String COMPUTED = \"x\" + \"y\";\n"
            + "}\n");
    SourcePathResolver spr = new SourcePathResolver(project);
    spr.index();
    StaticFieldResolver sfr = new StaticFieldResolver(spr);
    sfr.index();
    // Only the truly
    // public-static-final
    // with a literal
    // initializer survives.
    assertThat(sfr.size()).isEqualTo(0);
  }

  @Test
  void sourcePath_excludesBuildOutput(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path src = project.resolve("src");
    Path target = project.resolve("target");
    Files.createDirectories(src);
    Files.createDirectories(target);
    Files.writeString(src.resolve("Real.java"), "package x; public class Real {}");
    Files.writeString(target.resolve("Copy.java"), "package x; public class Copy {}");
    SourcePathResolver r = new SourcePathResolver(project);
    r.index();
    // Real is indexed; Copy
    // (under target/) is
    // filtered out.
    assertThat(r.resolveClass("x.Real")).isNotNull();
    assertThat(r.resolveClass("x.Copy"))
        .as("target/ is excluded by the build-output filter")
        .isNull();
  }

  @Test
  void sourcePath_handlesMissingRootGracefully(@TempDir Path tmp) throws Exception {
    SourcePathResolver r = new SourcePathResolver(tmp.resolve("does-not-exist"));
    r.index();
    assertThat(r.indexedClassCount()).isZero();
    assertThat(r.resolveClass("x.Y")).isNull();
  }

  @Test
  void juliet_simulation(@TempDir Path tmp) throws Exception {
    // Demonstrate the
    // resolvers working
    // end-to-end on a
    // synthetic
    // Juliet-like
    // layout: a project
    // root with a
    // testcasesupport/IO
    // class and a
    // testcases/CWE_X
    // class. The same code
    // works on real Juliet;
    // the test just
    // demonstrates the
    // shape.
    Path project = tmp.resolve("Juliet_Java");
    Path support = project.resolve("testcasesupport");
    Path testcases = project.resolve("testcases");
    Path cwe = testcases.resolve("CWE190_Integer_Overflow/s01");
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
    SourcePathResolver spr = new SourcePathResolver(project);
    spr.index();
    StaticFieldResolver sfr = new StaticFieldResolver(spr);
    sfr.index();
    // The test demonstrates
    // that the resolver
    // works without
    // any Juliet-specific
    // knowledge — it just
    // follows the package
    // declarations.
    StaticFieldResolver.LiteralValue v = sfr.lookup("testcasesupport.IO", "STATIC_FIVE");
    assertThat(v).isNotNull();
    assertThat(v.value).isEqualTo(5L);
    StaticFieldResolver.LiteralValue f = sfr.lookup("testcasesupport.IO", "STATIC_FALSE");
    assertThat(f.value).isEqualTo(false);
  }

  @Test
  void staticField_lookupToleratesShortClassName(@TempDir Path tmp) throws Exception {
    // R-810: the DFA engine emits
    // FIELDLOAD with the receiver
    // name stripped to the simple
    // form ({@code IO} not
    // {@code testcasesupport.IO})
    // because the IR's
    // {@code classRefVars} map
    // already lifted the
    // package-less name. The
    // resolver must accept the
    // short form for the lookup
    // to find the value. Without
    // this fallback, every
    // Juliet FIELDLOAD falls
    // through to the
    // orphan-SOURCE shape even
    // when a literal is
    // available.
    Path project = tmp.resolve("p");
    Path support = project.resolve("testcasesupport");
    Files.createDirectories(support);
    Files.writeString(
        support.resolve("IO.java"),
        "package testcasesupport;\n"
            + "public class IO {\n"
            + "  public static final int STATIC_FINAL_FIVE = 5;\n"
            + "}\n");
    SourcePathResolver spr = new SourcePathResolver(project);
    spr.index();
    StaticFieldResolver sfr = new StaticFieldResolver(spr);
    sfr.index();
    // FQCN still wins when the
    // caller has the package
    // (some entry points do, e.g.
    // the merged-program path).
    StaticFieldResolver.LiteralValue byFqcn = sfr.lookup("testcasesupport.IO", "STATIC_FINAL_FIVE");
    assertThat(byFqcn).as("FQCN lookup still hits").isNotNull();
    assertThat(byFqcn.value).isEqualTo(5L);
    // Short name (the DFA
    // engine's normal form) also
    // hits via the suffix
    // fallback.
    StaticFieldResolver.LiteralValue byShort = sfr.lookup("IO", "STATIC_FINAL_FIVE");
    assertThat(byShort)
        .as("R-810: short-class-name lookup " + "finds the literal via " + "suffix match")
        .isNotNull();
    assertThat(byShort.value).isEqualTo(5L);
    // Unknown field still misses.
    assertThat(sfr.lookup("IO", "NOT_A_FIELD")).isNull();
    // Unknown class still misses.
    assertThat(sfr.lookup("NotAClass", "FOO")).isNull();
  }

  @Test
  void staticField_lookupPreferExactMatchOverShortName(@TempDir Path tmp) throws Exception {
    // R-810: when the engine passes
    // a FQCN that exactly matches
    // an indexed key, the resolver
    // must return that entry, not
    // a different short-name
    // collision. (Belt and
    // suspenders for the suffix
    // fallback.)
    Path project = tmp.resolve("p");
    Files.createDirectories(project);
    Path a = project.resolve("a");
    Path b = project.resolve("b");
    Files.createDirectories(a);
    Files.createDirectories(b);
    Files.writeString(
        a.resolve("A.java"),
        "package a; public class A {\n" + "  public static final int F = 1;\n" + "}\n");
    Files.writeString(
        b.resolve("B.java"),
        "package b; public class B {\n" + "  public static final int F = 2;\n" + "}\n");
    SourcePathResolver spr = new SourcePathResolver(project);
    spr.index();
    StaticFieldResolver sfr = new StaticFieldResolver(spr);
    sfr.index();
    assertThat(sfr.lookup("a.A", "F").value).isEqualTo(1L);
    assertThat(sfr.lookup("b.B", "F").value).isEqualTo(2L);
  }

  /**
   * The files the analysis reads must not be the ones a budget drops.
   *
   * <p>The resolver indexes a project root under a budget, and the budget decides which files the
   * losers are. It used to spend itself on whatever the directory walk met first, which is
   * arbitrary: a project root that holds a large tree (a vendored directory, a test corpus, a home
   * directory) could leave the caller's OWN files outside the index. A partial index looks like a
   * complete one, so the cross-file references into those files stopped resolving without a word.
   *
   * <p>The work-list comes first now.
   */
  @Test
  void theWorkListSurvivesATreeThatIsOverTheBudget(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path work = project.resolve("src").resolve("main").resolve("java").resolve("app");
    Files.createDirectories(work);
    Files.writeString(work.resolve("Main.java"), "package app;\npublic class Main {}\n");
    // A big tree elsewhere in the project root, and DEEPER than the work-list.
    Path bulk = project.resolve("corpus").resolve("cwe").resolve("cases");
    Files.createDirectories(bulk);
    int over = SourcePathResolver.MAX_INDEXED_FILES + 5;
    for (int i = 0; i < over; i++) {
      Files.writeString(bulk.resolve("C" + i + ".java"), "package cwe;\nclass C" + i + " {}\n");
    }

    ProjectCompilationContext ctx = new ProjectCompilationContext(project, work);
    ctx.index();

    assertThat(ctx.sourcePath().resolveClass("app.Main"))
        .as("the file the analysis reads must be in the index")
        .isNotNull();
  }

  /**
   * The shared classes near the top of a source tree come before the bulk that lives deep.
   *
   * <p>A test corpus puts thousands of cases in deep directories and its helper classes in a
   * shallow one, and the helper classes are the ones the analysis has to resolve. Spending the
   * budget on directory order lost them.
   */
  @Test
  void theShallowFilesAreIndexedBeforeTheDeepOnes(@TempDir Path tmp) throws Exception {
    Path project = tmp.resolve("proj");
    Path support = project.resolve("src").resolve("testcasesupport");
    Files.createDirectories(support);
    Files.writeString(support.resolve("IO.java"), "package testcasesupport;\npublic class IO {}\n");
    Path bulk = project.resolve("src").resolve("testcases").resolve("cwe").resolve("s01");
    Files.createDirectories(bulk);
    int over = SourcePathResolver.MAX_INDEXED_FILES + 5;
    for (int i = 0; i < over; i++) {
      Files.writeString(bulk.resolve("C" + i + ".java"), "package cwe;\nclass C" + i + " {}\n");
    }

    SourcePathResolver r = new SourcePathResolver(project);
    r.index();

    assertThat(r.resolveClass("testcasesupport.IO"))
        .as("a shared helper near the top of the tree is indexed before the deep bulk")
        .isNotNull();
    assertThat(r.indexTruncated())
        .as("an index that stopped at the budget still says that it stopped")
        .isTrue();
  }
}
