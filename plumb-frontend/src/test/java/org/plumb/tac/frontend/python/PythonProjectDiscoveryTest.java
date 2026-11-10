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
package org.plumb.tac.frontend.python;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a Python project's files are, and where its boundary is.
 *
 * <h2>Why the boundary matters</h2>
 *
 * <p>A file's module name is its path under the project's root, and a callee's owner is the module
 * that declares it. So the boundary decides both how much of the machine the analysis reads and
 * what the module names are. A boundary that walks up too far makes the scan read thousands of
 * files nobody asked about; a boundary that stops too early makes every module name a bare file
 * name -- and a module name is what the sink catalogue matches on.
 */
class PythonProjectDiscoveryTest {

  @Test
  @DisplayName("a type stub is not a source file")
  void aTypeStubIsNotASourceFile(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("types.pyi");
    Files.writeString(file, "def handle(id: str) -> str: ...\n");

    assertThat(PythonProjectDiscovery.isSourceFile(file)).isFalse();
    assertThat(PythonProjectDiscovery.isStubFile("types.pyi")).isTrue();
    assertThat(PythonProjectDiscovery.isStubFile("types.py")).isFalse();
  }

  @Test
  @DisplayName("the environment directories are left out")
  void theEnvironmentDirectoriesAreLeftOut(@TempDir Path directory) throws IOException {
    Path cache = directory.resolve("__pycache__");
    Files.createDirectories(cache);
    Files.writeString(cache.resolve("module.cpython-312.pyc"), "bytecode\n");
    Path venv = directory.resolve(".venv");
    Files.createDirectories(venv);
    Files.writeString(venv.resolve("library.py"), "a = 1\n");
    Path source = directory.resolve("app.py");
    Files.writeString(source, "a = 1\n");

    assertThat(PythonProjectDiscovery.filesUnder(directory))
        .as("other people's code and the compiler's own cache are not the project's own source")
        .containsExactly(source);
  }

  @Test
  @DisplayName("the project file marks a project")
  void theProjectFileMarksAProject(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");
    Path nested = directory.resolve("src/app/routes");
    Files.createDirectories(nested);
    Path file = nested.resolve("user.py");
    Files.writeString(file, "a = 1\n");

    assertThat(PythonProjectDiscovery.markedRootOf(file)).isEqualTo(directory);
  }

  @Test
  @DisplayName("a package's own marker is not a project marker")
  void aPackagesOwnMarkerIsNotAProjectMarker(@TempDir Path directory) throws IOException {
    // An __init__.py says that a directory is a package, not that the
    // directory is a project -- and every subdirectory of a package would
    // then be a project of its own.
    Files.writeString(directory.resolve("__init__.py"), "\n");
    Path file = directory.resolve("routes/user.py");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "a = 1\n");

    assertThat(PythonProjectDiscovery.markedRootOf(file)).isNull();
  }

  @Test
  @DisplayName("a file with no marker is its own project")
  void aFileWithNoMarkerIsItsOwnProject(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("app.py");
    Files.writeString(file, "a = 1\n");

    PythonProjectDiscovery.Project project = PythonProjectDiscovery.discover(file, null);

    assertThat(project.root()).isEqualTo(directory);
    assertThat(project.files()).containsExactly(file);
    assertThat(project.truncated()).isFalse();
  }

  @Test
  @DisplayName("a project holds every Python file under it")
  void aProjectHoldsEveryPythonFileUnderIt(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("pyproject.toml"), "[project]\nname = \"app\"\n");
    Path packageDirectory = directory.resolve("app");
    Files.createDirectories(packageDirectory);
    Path module = packageDirectory.resolve("routes.py");
    Files.writeString(module, "a = 1\n");
    Path other = directory.resolve("main.py");
    Files.writeString(other, "b = 1\n");
    Path stub = directory.resolve("types.pyi");
    Files.writeString(stub, "def handle(id: str) -> str: ...\n");

    PythonProjectDiscovery.Project project = PythonProjectDiscovery.discover(module, null);

    assertThat(project.root()).isEqualTo(directory);
    assertThat(project.files())
        .as("a stub holds no body, so it is not a file to analyse")
        .containsExactlyInAnyOrder(module, other);
  }
}
