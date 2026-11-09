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
package org.plumb.tac.frontend.go;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The source directories of a Go module's dependencies.
 *
 * <h2>Why these directories matter</h2>
 *
 * <p>The export data of a dependency is gone by the time the analysis reads the capture, so the
 * dependency is resolved from its source. The toolchain's module cache holds the dependencies that
 * were downloaded -- but not the ones that were <b>replaced</b>, and not the ones that belong to
 * another module of a <b>workspace</b>. Those are directories on disk, and this is where they are
 * found.
 */
class GoModuleSourcesTest {

  /**
   * The directory an import path resolves to, the way the analysis resolves it.
   *
   * @param moduleRoot the module the package belongs to
   * @param importPath the import path
   * @return the directory, or null when no module provides it
   */
  private static Path directoryOf(Path moduleRoot, String importPath) {
    return GoModuleSources.directoryFor(GoModuleSources.modulesOf(moduleRoot), importPath);
  }

  @Test
  @DisplayName("a replaced dependency's source directory is found")
  void aReplacedDependencyIsFound(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module.resolve("pkg"));
    Files.writeString(
        module.resolve("go.mod"),
        "module example.com/app\n\ngo 1.23\n\nreplace other.example/lib => ../other-repo\n");
    Path other = directory.resolve("other-repo");
    Files.createDirectories(other.resolve("pkg"));

    assertThat(directoryOf(module, "other.example/lib/pkg"))
        .isEqualTo(other.resolve("pkg").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a workspace module's source directory is found")
  void aWorkspaceModuleIsFound(@TempDir Path directory) throws IOException {
    Path workspace = directory.resolve("work");
    Path app = workspace.resolve("app");
    Path library = workspace.resolve("library");
    Files.createDirectories(app);
    Files.createDirectories(library.resolve("pkg"));
    Files.writeString(app.resolve("go.mod"), "module example.com/app\n\ngo 1.23\n");
    Files.writeString(library.resolve("go.mod"), "module example.com/library\n\ngo 1.23\n");
    Files.writeString(workspace.resolve("go.work"), "go 1.23\n\nuse (\n\t./app\n\t./library\n)\n");

    assertThat(directoryOf(app, "example.com/library/pkg"))
        .isEqualTo(library.resolve("pkg").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a package of the module itself is found")
  void aPackageOfTheModuleItselfIsFound(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module.resolve("pkg"));
    Files.writeString(module.resolve("go.mod"), "module example.com/app\n\ngo 1.23\n");

    assertThat(directoryOf(module, "example.com/app/pkg"))
        .isEqualTo(module.resolve("pkg").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("the standard library is left to the toolchain's own lookup")
  void theStandardLibraryIsLeftAlone(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/app\n\ngo 1.23\n");

    assertThat(directoryOf(module, "fmt")).isNull();
    assertThat(directoryOf(module, "os")).isNull();
    assertThat(directoryOf(module, "net/http")).isNull();
  }

  @Test
  @DisplayName("a replacement that names another module is left to the module cache")
  void aReplacementThatNamesAModuleIsLeftAlone(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module);
    Files.writeString(
        module.resolve("go.mod"),
        "module example.com/app\n\ngo 1.23\n\nreplace other.example/lib => other.example/lib v1.2.3\n");

    assertThat(directoryOf(module, "other.example/lib/pkg")).isNull();
  }

  @Test
  @DisplayName("the longest matching module path wins")
  void theLongestMatchingModulePathWins(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module);
    Files.writeString(
        module.resolve("go.mod"),
        "module example.com/m\n\ngo 1.23\n\nreplace example.com/m2 => ../m2\n");
    Path other = directory.resolve("m2");
    Files.createDirectories(other.resolve("pkg"));

    assertThat(directoryOf(module, "example.com/m2/pkg"))
        .isEqualTo(other.resolve("pkg").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a module file's statements are read one directive at a time")
  void aModuleFilesStatementsAreReadOneDirectiveAtATime(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("go.mod");
    Files.writeString(
        file,
        "module example.com/app\n\ngo 1.23\n\nrequire (\n\texample.com/x v1.0.0\n)\n\n"
            + "replace (\n\texample.com/y => ../y\n\texample.com/z => ../z\n)\n");

    List<String> statements = GoModuleSources.statementsOf(file);

    // The two replacements share one block, and each of them has to be a
    // statement of its own: a block is read line by line, because each line
    // of it is a directive.
    assertThat(statements)
        .contains(
            "module example.com/app",
            "replace example.com/y => ../y",
            "replace example.com/z => ../z");
  }

  @Test
  @DisplayName("the module map names the module, the replaced modules and the workspace's")
  void theModuleMapNamesEveryModuleOnDisk(@TempDir Path directory) throws IOException {
    Path workspace = directory.resolve("work");
    Path app = workspace.resolve("app");
    Path library = workspace.resolve("library");
    Path other = workspace.resolve("other");
    Files.createDirectories(app);
    Files.createDirectories(library);
    Files.createDirectories(other);
    Files.writeString(app.resolve("go.mod"), "module example.com/app\n\ngo 1.23\n");
    Files.writeString(library.resolve("go.mod"), "module example.com/library\n\ngo 1.23\n");
    Files.writeString(other.resolve("go.mod"), "module example.com/other\n\ngo 1.23\n");
    Files.writeString(
        app.resolve("go.mod"),
        "module example.com/app\n\ngo 1.23\n\nreplace example.com/other => ../other\n");
    Files.writeString(workspace.resolve("go.work"), "go 1.23\n\nuse (\n\t./app\n\t./library\n)\n");

    Map<String, Path> modules = GoModuleSources.modulesOf(app);

    assertThat(modules)
        .containsEntry("example.com/app", app.toAbsolutePath().normalize())
        .containsEntry("example.com/library", library.toAbsolutePath().normalize())
        .containsEntry("example.com/other", other.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a replaced module's own replacement is found too")
  void aReplacedModulesOwnReplacementIsFoundToo(@TempDir Path directory) throws IOException {
    Path module = directory.resolve("app");
    Path middle = directory.resolve("middle");
    Path deepest = directory.resolve("deepest");
    Files.createDirectories(module);
    Files.createDirectories(middle);
    Files.createDirectories(deepest);
    Files.writeString(
        module.resolve("go.mod"),
        "module example.com/app\n\ngo 1.23\n\nreplace example.com/middle => ../middle\n");
    Files.writeString(
        middle.resolve("go.mod"),
        "module example.com/middle\n\ngo 1.23\n\nreplace example.com/deepest => ../deepest\n");
    Files.writeString(deepest.resolve("go.mod"), "module example.com/deepest\n\ngo 1.23\n");

    Map<String, Path> modules = GoModuleSources.modulesOf(module);

    assertThat(modules)
        .containsEntry("example.com/middle", middle.toAbsolutePath().normalize())
        .containsEntry("example.com/deepest", deepest.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("an import path is resolved against the module that provides it")
  void anImportPathIsResolvedAgainstTheModuleThatProvidesIt(@TempDir Path directory)
      throws IOException {
    Path module = directory.resolve("app");
    Files.createDirectories(module.resolve("pkg"));
    Files.writeString(module.resolve("go.mod"), "module example.com/app\n\ngo 1.23\n");

    Map<String, Path> modules = GoModuleSources.modulesOf(module);

    assertThat(GoModuleSources.directoryFor(modules, "example.com/app/pkg"))
        .isEqualTo(module.resolve("pkg").toAbsolutePath().normalize());
    assertThat(GoModuleSources.directoryFor(modules, "fmt")).isNull();
  }
}
