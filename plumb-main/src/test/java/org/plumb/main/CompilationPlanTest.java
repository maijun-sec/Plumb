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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.capture.CaptureEntry;
import org.plumb.tac.frontend.csharp.CSharpProjectProfile;
import org.plumb.tac.frontend.go.GoPackage;
import org.plumb.tac.frontend.go.GoProjectProfile;

/**
 * The compilation plans read a build's own command lines, and the plan is the ONLY place where the
 * build's inputs become the analysis's inputs.
 *
 * <h2>Why these are pinned</h2>
 *
 * <p>A missing reference, a missing preprocessor symbol, a missing language version or a missing
 * import configuration each fail the SAME way: the compilation succeeds, the analysis runs, and the
 * result is quietly smaller than the truth. No diagnostic appears anywhere. So each case here is a
 * case where a wrong answer is indistinguishable from a right one, which is why they are pinned
 * rather than left to the end-to-end test.
 *
 * <h2>What the spellings are</h2>
 *
 * <p>Both compilers accept several spellings of the same thing -- the option and its value joined
 * or separate, a slash or a dash, a comma or a semicolon in a list -- and the spelling is chosen by
 * whoever built the project, not by this reader. So every spelling is read.
 */
class CompilationPlanTest {

  @Test
  @DisplayName("a C# command line yields the references, the symbols and the sources")
  void aCsharpCommandLineIsRead() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/app",
            List.of(
                "/usr/share/dotnet/sdk/8.0/csc.dll",
                "/reference:/refs/System.Runtime.dll,/refs/My.Other.dll",
                "/define:DEBUG;TRACE",
                "/langversion:12",
                "/nullable:enable",
                "/out:bin/App.dll",
                "Program.cs",
                "Service.cs"),
            null);

    CsharpCompilePlan.Project project = CsharpCompilePlan.projectOf(entry);
    CSharpProjectProfile profile = project.profile();

    assertThat(profile.references())
        .containsExactly(Path.of("/refs/System.Runtime.dll"), Path.of("/refs/My.Other.dll"));
    assertThat(profile.defines()).containsExactly("DEBUG", "TRACE");
    assertThat(profile.langVersion()).isEqualTo("12");
    assertThat(profile.nullableContext()).isEqualTo("enable");
    assertThat(project.output()).isEqualTo(Path.of("/src/app/bin/App.dll"));
    assertThat(project.sourceFiles())
        .containsExactly(Path.of("/src/app/Program.cs"), Path.of("/src/app/Service.cs"));
  }

  @Test
  @DisplayName("a relative reference is resolved against the compile's own directory")
  void aRelativeReferenceIsResolved() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/app", List.of("csc", "/reference:libs/A.dll", "Program.cs"), null);

    CsharpCompilePlan.Project project = CsharpCompilePlan.projectOf(entry);

    assertThat(project.profile().references()).containsExactly(Path.of("/src/app/libs/A.dll"));
  }

  @Test
  @DisplayName("an option whose value is the next token is read too")
  void aSeparateValueIsRead() {
    // The compiler accepts "/reference foo.dll" as well as "/reference:foo.dll".
    // Reading only the joined form would drop every reference of a build that
    // spells it the other way, and a dropped reference is a missing assembly.
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/app",
            List.of(
                "csc",
                "/reference",
                "/refs/A.dll",
                "/define",
                "DEBUG",
                "/langversion",
                "10",
                "/nullable",
                "disable",
                "/out",
                "bin/App.dll",
                "Program.cs"),
            null);

    CsharpCompilePlan.Project project = CsharpCompilePlan.projectOf(entry);
    CSharpProjectProfile profile = project.profile();

    assertThat(profile.references()).containsExactly(Path.of("/refs/A.dll"));
    assertThat(profile.defines()).containsExactly("DEBUG");
    assertThat(profile.langVersion()).isEqualTo("10");
    assertThat(profile.nullableContext()).isEqualTo("disable");
    assertThat(project.output()).isEqualTo(Path.of("/src/app/bin/App.dll"));
    assertThat(project.sourceFiles()).containsExactly(Path.of("/src/app/Program.cs"));
  }

  @Test
  @DisplayName("a source file on its own is a source file, and it does not crash the reader")
  void aPositionalSourceFileIsRead() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry("/src/app", List.of("csc", "Program.cs"), null);

    assertThatCode(() -> CsharpCompilePlan.projectOf(entry)).doesNotThrowAnyException();

    assertThat(CsharpCompilePlan.projectOf(entry).sourceFiles())
        .containsExactly(Path.of("/src/app/Program.cs"));
  }

  @Test
  @DisplayName("an option that looks like a source file is not a source file")
  void anOptionThatLooksLikeASourceFileIsNotOne() {
    // "/recurse:*.cs" names a pattern. Reading it as a file would put a file
    // that does not exist into the compilation.
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/app", List.of("csc", "/recurse:*.cs", "/target:library"), null);

    assertThat(CsharpCompilePlan.projectOf(entry).sourceFiles()).isEmpty();
  }

  @Test
  @DisplayName("a comma-separated list and a semicolon-separated list are both split")
  void bothListSeparatorsAreSplit() {
    CompilationDatabaseEntry comma =
        new CompilationDatabaseEntry("/s", List.of("csc", "/define:A,B"), null);
    CompilationDatabaseEntry semicolon =
        new CompilationDatabaseEntry("/s", List.of("csc", "/define:A;B"), null);

    assertThat(CsharpCompilePlan.projectOf(comma).profile().defines()).containsExactly("A", "B");
    assertThat(CsharpCompilePlan.projectOf(semicolon).profile().defines())
        .containsExactly("A", "B");
  }

  @Test
  @DisplayName("a repeated symbol is one symbol")
  void aRepeatedSymbolIsOneSymbol() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry("/s", List.of("csc", "/define:DEBUG", "/define:DEBUG"), null);

    assertThat(CsharpCompilePlan.projectOf(entry).profile().defines()).containsExactly("DEBUG");
  }

  @Test
  @DisplayName("a Java compilation is not a C# compilation")
  void aJavaCommandIsNotCsharp() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry("/s", List.of("javac", "-cp", "a.jar", "Main.java"), null);

    assertThat(CsharpCompilePlan.isCsharpCommand(entry)).isFalse();
  }

  @Test
  @DisplayName("a Kotlin command yields the Kotlin language, and a Scala command yields Scala")
  void aJvmCommandYieldsItsLanguage() {
    CompilationDatabaseEntry kotlin =
        new CompilationDatabaseEntry(
            "/s", List.of("kotlinc", "-d", "out", "src/main/kotlin/demo/App.kt"), null);
    CompilationDatabaseEntry scala =
        new CompilationDatabaseEntry(
            "/s", List.of("scalac", "-d", "out", "src/main/scala/demo/App.scala"), null);

    assertThat(JavacCompilePlan.captureOf(kotlin).language()).isEqualTo("kotlin");
    assertThat(JavacCompilePlan.captureOf(scala).language()).isEqualTo("scala");
  }

  @Test
  @DisplayName("the source root is the tree that holds the compiled sources")
  void theSourceRootIsDerivedFromTheCompiledSources(@TempDir Path directory) throws IOException {
    Files.createDirectories(directory.resolve("src/main/kotlin/demo"));
    Files.createDirectories(directory.resolve("src/main/kotlin/util"));
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(),
            List.of(
                "kotlinc",
                "-d",
                "out",
                "src/main/kotlin/demo/App.kt",
                "src/main/kotlin/util/Helper.kt"),
            null);

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);

    // The deepest directory that holds every compiled source: the directory
    // the Kotlin conventions call the source root.
    assertThat(capture.sourcePath()).containsExactly(directory.resolve("src/main/kotlin"));
  }

  @Test
  @DisplayName("a compilation that names no compiler is recognised by the unit it compiled")
  void aCompilationWithoutACompilerIsRecognisedByItsUnit(@TempDir Path directory)
      throws IOException {
    // A build that compiles inside the JVM hands the compiler's options over
    // as a list, so the database entry has no compiler to name -- the first
    // argument is an option. The unit is still named, and the unit says
    // which language it is.
    Path source = directory.resolve("src/main/java/demo/app/App.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo.app;\npublic final class App {}\n");

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(), List.of("-d", "out", "-classpath", "lib.jar"), source.toString());

    assertThat(JavacCompilePlan.isJvmCommand(entry)).isTrue();

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);
    assertThat(capture.sourceFiles()).containsExactly(source);
    assertThat(capture.language()).isEqualTo("java");
  }

  @Test
  @DisplayName("the derived source root has the package path peeled off it")
  void theDerivedSourceRootHasThePackagePathPeeledOff(@TempDir Path directory) throws IOException {
    // The compiled file lives two segments below the source root, and the
    // package says so. A type solver looks a type up at root/<package path>,
    // so a root that still holds the package path misses by one segment --
    // and the miss is silent.
    Path source = directory.resolve("src/main/java/demo/core/Core.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo.core;\npublic final class Core {}\n");

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(), List.of("javac", "-d", "out", source.toString()), null);

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);

    assertThat(capture.sourcePath()).containsExactly(directory.resolve("src/main/java"));
  }

  @Test
  @DisplayName("a source root that is already the root is left alone")
  void aSourceRootThatIsAlreadyTheRootIsLeftAlone(@TempDir Path directory) throws IOException {
    // The file sits directly in the root, and its package says one segment.
    // The root does not end with that segment, so nothing is peeled and the
    // root stays where it is.
    Path source = directory.resolve("src/Main.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "package demo;\npublic final class Main {}\n");

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(), List.of("javac", "-d", "out", source.toString()), null);

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);

    assertThat(capture.sourcePath()).containsExactly(directory.resolve("src"));
  }

  @Test
  @DisplayName("an explicit source path is read, and resolved against the compile's directory")
  void anExplicitSourcePathIsRead(@TempDir Path directory) throws IOException {
    Files.createDirectories(directory.resolve("src/main/java"));
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(),
            List.of("javac", "-sourcepath", "src/main/java", "-d", "out", "src/Main.java"),
            null);

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);

    assertThat(capture.sourcePath()).containsExactly(directory.resolve("src/main/java"));
  }

  @Test
  @DisplayName("an option that takes its value as the next argument is read")
  void anOptionWithASeparateValueIsRead(@TempDir Path directory) throws IOException {
    Files.createDirectories(directory.resolve("lib"));
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            directory.toString(),
            List.of("javac", "-classpath", "lib/a.jar", "-d", "out", "src/Main.java"),
            null);

    CaptureEntry capture = JavacCompilePlan.captureOf(entry);

    // The value is the NEXT argument, so reading it means reading it BEFORE
    // the option is handled -- and skipping it afterwards.
    assertThat(capture.classpath()).containsExactly(directory.resolve("lib/a.jar"));
    assertThat(capture.outputDirectory()).isEqualTo(directory.resolve("out"));
  }

  @Test
  @DisplayName("a Go compile command yields the package, its files and its language version")
  void aGoCompileCommandIsRead() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod",
            List.of(
                "/go/pkg/tool/windows_amd64/compile.exe",
                "-o",
                "/tmp/pkg.a",
                "-p",
                "example.com/mod/store",
                "-lang=go1.21",
                "-importcfg",
                "/tmp/importcfg",
                "store.go",
                "store_test.go"),
            null);

    assertThat(GoCompilePlan.isGoCommand(entry)).isTrue();
    assertThat(GoCompilePlan.isGoCompileCommand(entry)).isTrue();

    GoPackage goPackage = GoCompilePlan.packageOf(entry, Path.of("/src/mod"));

    assertThat(goPackage.importPath()).isEqualTo("example.com/mod/store");
    assertThat(goPackage.goVersion()).isEqualTo("go1.21");
    assertThat(goPackage.files()).containsExactly(Path.of("/src/mod/store.go"));
  }

  @Test
  @DisplayName("a Go flag whose value is the next token is read too")
  void aGoSeparateFlagValueIsRead() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod",
            List.of(
                "/go/pkg/tool/windows_amd64/compile.exe",
                "-p",
                "example.com/mod",
                "-lang",
                "go1.22",
                "a.go"),
            null);

    GoPackage goPackage = GoCompilePlan.packageOf(entry, Path.of("/src/mod"));

    assertThat(goPackage.importPath()).isEqualTo("example.com/mod");
    assertThat(goPackage.goVersion()).isEqualTo("go1.22");
  }

  @Test
  @DisplayName("a test file is not part of the package the build produces")
  void aTestFileIsNotPartOfThePackage() {
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod",
            List.of("/go/pkg/tool/windows_amd64/compile.exe", "-p", "m", "a.go", "a_test.go"),
            null);

    GoPackage goPackage = GoCompilePlan.packageOf(entry, Path.of("/src/mod"));

    assertThat(goPackage.files()).containsExactly(Path.of("/src/mod/a.go"));
  }

  @Test
  @DisplayName("the import configuration maps every dependency to its export data")
  void anImportConfigurationIsRead(@TempDir Path directory) throws IOException {
    // The export data is named with paths the platform can express, because a
    // path that is not absolute on this platform would be resolved against the
    // configuration's own directory -- which is a different path entirely.
    Path fmt = directory.resolve("fmt.a");
    Path store = directory.resolve("store.a");
    Path config = directory.resolve("importcfg");
    Files.writeString(
        config,
        "# a comment\n"
            + "packagefile fmt="
            + fmt
            + "\n"
            + "packagefile example.com/mod/store="
            + store
            + "\n");

    Map<String, Path> imports = GoCompilePlan.readImportConfig(config);

    assertThat(imports).containsEntry("fmt", fmt).containsEntry("example.com/mod/store", store);
  }

  @Test
  @DisplayName("a line the reader does not understand is skipped, not guessed at")
  void anUnknownLineIsSkipped(@TempDir Path directory) throws IOException {
    Path config = directory.resolve("importcfg");
    Files.writeString(config, "packagefile fmt=/tmp/fmt.a\nsomething else entirely\n");

    assertThat(GoCompilePlan.readImportConfig(config)).containsOnlyKeys("fmt");
  }

  @Test
  @DisplayName("an import configuration that is not there yields no dependency, and no failure")
  void aMissingImportConfigurationIsEmpty(@TempDir Path directory) {
    assertThat(GoCompilePlan.readImportConfig(directory.resolve("absent.cfg"))).isEmpty();
  }

  @Test
  @DisplayName("the linker says which packages the program actually contains")
  void theLinkerIsRead(@TempDir Path directory) throws IOException {
    Path config = directory.resolve("link.cfg");
    Files.writeString(
        config, "packagefile fmt=/go/pkg/fmt.a\npackagefile example.com/mod=/tmp/m.a\n");
    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod",
            List.of(
                "/go/pkg/tool/windows_amd64/link.exe",
                "-importcfg",
                config.toString(),
                "-o",
                "app.exe"),
            null);

    assertThat(GoCompilePlan.isGoLinkCommand(entry)).isTrue();

    GoProjectProfile profile = GoCompilePlan.projectOf(List.of(entry));

    assertThat(profile.linkedPackages()).contains("fmt", "example.com/mod");
    assertThat(profile.binary()).isEqualTo(Path.of("/src/mod/app.exe"));
  }

  @Test
  @DisplayName("the module root is the nearest ancestor that holds a go.mod")
  void theModuleRootIsFound(@TempDir Path directory) throws IOException {
    Path root = directory.resolve("mod");
    Path inner = root.resolve("internal/store");
    Files.createDirectories(inner);
    Files.writeString(root.resolve("go.mod"), "module example.com/mod\n");

    assertThat(GoCompilePlan.moduleRootOf(inner)).isEqualTo(root.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a package outside any module has no module root")
  void aPackageOutsideAModuleHasNoRoot(@TempDir Path directory) {
    assertThat(GoCompilePlan.moduleRootOf(directory)).isNull();
  }

  @Test
  @DisplayName("the toolchain the build used is the one the compiler's own path names")
  void theToolchainRootIsRead(@TempDir Path directory) throws IOException {
    // The standard library's source lives under the toolchain's root, and the
    // front end cannot ask the environment for it: the GOROOT in the
    // environment outlives the toolchain it names, and a stale one makes every
    // standard library import fail SILENTLY -- the calls into the standard
    // library lose their types, and the findings that depend on them vanish
    // while the scan reports success.
    Path root = directory.resolve("go");
    Files.createDirectories(root);
    // The standard library's source is what makes a directory a toolchain.
    Files.createDirectories(root.resolve("src"));
    String compiler =
        root.resolve("pkg")
            .resolve("tool")
            .resolve("windows_amd64")
            .resolve("compile.exe")
            .toString();

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod", List.of(compiler, "-p", "example.com/mod", "a.go"), null);

    assertThat(GoCompilePlan.toolchainRootOf(entry)).isEqualTo(root.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a toolchain that is not on this machine is not used")
  void aToolchainThatIsNotThereIsNotUsed(@TempDir Path directory) {
    String compiler =
        directory
            .resolve("absent")
            .resolve("pkg")
            .resolve("tool")
            .resolve("compile.exe")
            .toString();

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod", List.of(compiler, "-p", "example.com/mod", "a.go"), null);

    assertThat(GoCompilePlan.toolchainRootOf(entry)).isNull();
  }

  @Test
  @DisplayName("the project carries the toolchain the build used")
  void theProjectCarriesTheToolchain(@TempDir Path directory) throws IOException {
    Path root = directory.resolve("go");
    Files.createDirectories(root);
    // The standard library's source is what makes a directory a toolchain.
    Files.createDirectories(root.resolve("src"));
    String compiler =
        root.resolve("pkg")
            .resolve("tool")
            .resolve("windows_amd64")
            .resolve("compile.exe")
            .toString();

    CompilationDatabaseEntry entry =
        new CompilationDatabaseEntry(
            "/src/mod", List.of(compiler, "-p", "example.com/mod", "a.go"), null);

    GoProjectProfile profile = GoCompilePlan.projectOf(List.of(entry));

    assertThat(profile.goroot()).isEqualTo(root.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a compilation database is read in both of its command shapes")
  void aCompilationDatabaseIsRead() throws IOException {
    String text =
        """
        [
          {
            "directory": "/src/app",
            "arguments": ["csc", "/reference:a.dll", "Program.cs"],
            "file": "/src/app/Program.cs"
          },
          {
            "directory": "/src/app",
            "command": "javac -cp \\"a b.jar\\" Main.java",
            "file": "/src/app/Main.java"
          }
        ]
        """;

    List<CompilationDatabaseEntry> entries = CompilationDatabaseReader.parse(text);

    assertThat(entries).hasSize(2);
    assertThat(entries.get(0).arguments()).containsExactly("csc", "/reference:a.dll", "Program.cs");
    assertThat(entries.get(1).arguments()).containsExactly("javac", "-cp", "a b.jar", "Main.java");
  }

  @Test
  @DisplayName("a file that is not a database is refused")
  void aFileThatIsNotADatabaseIsRefused() {
    assertThatCode(() -> CompilationDatabaseReader.parse("{\"not\": \"an array\"}"))
        .isInstanceOf(IOException.class);
  }

  @Test
  @DisplayName("a database that starts with a byte order mark is read anyway")
  void aByteOrderMarkIsTolerated() throws IOException {
    // The PowerShell 5.1 default writes one, and so do several other
    // producers. The mark is not part of the JSON, and the format is an
    // interchange format, so a database with one is still a database.
    String marked = "\uFEFF" + "[{\"directory\": \"/s\", \"arguments\": [\"csc\", \"A.cs\"]}]";

    List<CompilationDatabaseEntry> entries = CompilationDatabaseReader.parse(marked);

    assertThat(entries).hasSize(1);
    assertThat(entries.get(0).arguments()).containsExactly("csc", "A.cs");
  }
}
