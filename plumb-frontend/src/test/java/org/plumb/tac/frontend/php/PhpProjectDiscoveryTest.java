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
package org.plumb.tac.frontend.php;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The PHP project's own files: which ones belong to it, and where its boundary is.
 *
 * <h2>Why the boundary matters</h2>
 *
 * <p>A file's module name is its path under the project's root, and a callee's owner is the
 * namespace that declares it. A boundary that cuts the project in the wrong place therefore does
 * not cost precision -- it changes the names the sink catalogue matches on.
 */
class PhpProjectDiscoveryTest {

  @Test
  @DisplayName("only PHP files are the project's own source")
  void onlyPhpFilesAreTheProjectsOwnSource(@TempDir Path directory) throws IOException {
    Path vendor = directory.resolve("vendor");
    Files.createDirectories(vendor);
    Files.writeString(vendor.resolve("library.php"), "<?php\nfunction f() {}\n");
    Path hidden = directory.resolve(".cache");
    Files.createDirectories(hidden);
    Files.writeString(hidden.resolve("generated.php"), "<?php\nfunction g() {}\n");
    Path source = directory.resolve("app.php");
    Files.writeString(source, "<?php\nfunction h() {}\n");

    assertThat(PhpProjectDiscovery.filesUnder(directory))
        .as("other people's code and a tool's own state are not the project's own source")
        .containsExactly(source);
  }

  @Test
  @DisplayName("the Composer manifest marks a project")
  void theComposerManifestMarksAProject(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path nested = directory.resolve("src/Services");
    Files.createDirectories(nested);
    Path file = nested.resolve("Mailer.php");
    Files.writeString(file, "<?php\n");

    assertThat(PhpProjectDiscovery.markedRootOf(file)).isEqualTo(directory);
  }

  @Test
  @DisplayName("a file with no marker is its own project")
  void aFileWithNoMarkerIsItsOwnProject(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("app.php");
    Files.writeString(file, "<?php\n");

    PhpProjectDiscovery.Project project = PhpProjectDiscovery.discover(file, null);

    assertThat(project.root()).isEqualTo(directory);
    assertThat(project.files()).containsExactly(file);
    assertThat(project.truncated()).isFalse();
  }

  @Test
  @DisplayName("a project holds every PHP file under it")
  void aProjectHoldsEveryPhpFileUnderIt(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path sourceDirectory = directory.resolve("src");
    Files.createDirectories(sourceDirectory);
    Path module = sourceDirectory.resolve("Mailer.php");
    Files.writeString(module, "<?php\n");
    Path other = directory.resolve("index.php");
    Files.writeString(other, "<?php\n");

    PhpProjectDiscovery.Project project = PhpProjectDiscovery.discover(module, null);

    assertThat(project.root()).isEqualTo(directory);
    assertThat(project.files()).containsExactlyInAnyOrder(module, other);
  }

  @Test
  @DisplayName("a file that is not PHP is not a source file")
  void aFileThatIsNotPhpIsNotASourceFile(@TempDir Path directory) throws IOException {
    Path php = directory.resolve("app.php");
    Files.writeString(php, "<?php\n");
    Path module = directory.resolve("composer.json");
    Files.writeString(module, "{}\n");

    assertThat(PhpProjectDiscovery.isSourceFile(php)).isTrue();
    assertThat(PhpProjectDiscovery.isSourceFile(module)).isFalse();
  }
}
