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
package org.plumb.tac.frontend.arkts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where an ArkTS project's files are, and where its boundary is.
 *
 * <h2>Why the boundary matters</h2>
 *
 * <p>The compiler is given a whole project, so the boundary decides how much of the machine the
 * analysis reads. A boundary that walks up too far makes the scan read thousands of files nobody
 * asked about; a boundary that stops too early makes the compiler resolve an import to nothing --
 * and an unresolved import means an unknown type, which means a <b>missing finding</b> rather than
 * a missing precision.
 */
class ArktsProjectDiscoveryTest {

  @Test
  @DisplayName("a declaration file is not a source file")
  void aDeclarationFileIsNotASourceFile(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("types.d.ets");
    Files.writeString(file, "export declare function handle(id: string): string;\n");

    assertThat(ArktsProjectDiscovery.isSourceFile(file)).isFalse();
    assertThat(ArktsProjectDiscovery.isDeclarationFile("types.d.ets")).isTrue();
    assertThat(ArktsProjectDiscovery.isDeclarationFile("types.d.ts")).isTrue();
  }

  @Test
  @DisplayName("the dependency directories are left out")
  void theDependencyDirectoriesAreLeftOut(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path module = directory.resolve("oh_modules");
    Files.createDirectories(module);
    Files.writeString(module.resolve("library.ets"), "export const a = 1;\n");
    Path nodeModules = directory.resolve("node_modules");
    Files.createDirectories(nodeModules);
    Files.writeString(nodeModules.resolve("library.ets"), "export const b = 1;\n");
    Path source = directory.resolve("Page.ets");
    Files.writeString(source, "export const a = 1;\n");

    assertThat(ArktsProjectDiscovery.filesUnder(directory))
        .as("other people's code is not the project's own source")
        .containsExactly(source);
  }

  @Test
  @DisplayName("the build profile marks a module")
  void theBuildProfileMarksAModule(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path nested = directory.resolve("src/main/ets/pages");
    Files.createDirectories(nested);
    Path file = nested.resolve("Index.ets");
    Files.writeString(file, "export const a = 1;\n");

    assertThat(ArktsProjectDiscovery.markedRootOf(file)).isEqualTo(directory);
  }

  @Test
  @DisplayName("the package manifest marks a module")
  void thePackageManifestMarksAModule(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("oh-package.json5"), "{}\n");
    Path file = directory.resolve("src/main/ets/Index.ets");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "export const a = 1;\n");

    assertThat(ArktsProjectDiscovery.markedRootOf(file)).isEqualTo(directory);
  }

  @Test
  @DisplayName("a plain package manifest is not a marker")
  void aPlainPackageManifestIsNotAMarker(@TempDir Path directory) throws IOException {
    // A package manifest is a common thing to find in a HOME directory, and a
    // boundary that walks up to it would make the whole home directory one
    // project -- thousands of files the analysis never asked for.
    Files.writeString(directory.resolve("package.json"), "{}\n");
    Path file = directory.resolve("src/Index.ets");
    Files.createDirectories(file.getParent());
    Files.writeString(file, "export const a = 1;\n");

    assertThat(ArktsProjectDiscovery.markedRootOf(file)).isNull();
  }

  @Test
  @DisplayName("a file with no marker is its own project")
  void aFileWithNoMarkerIsItsOwnProject(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("Index.ets");
    Files.writeString(file, "export const a = 1;\n");

    ArktsProjectDiscovery.Project project = ArktsProjectDiscovery.discover(file, null);

    assertThat(project.root()).isEqualTo(directory);
    assertThat(project.files()).containsExactly(file);
    assertThat(project.truncated()).isFalse();
  }

  @Test
  @DisplayName("a project holds its ArkTS files and the TypeScript beside them")
  void aProjectHoldsItsArktsFilesAndTheTypeScriptBesideThem(@TempDir Path directory)
      throws IOException {
    Files.writeString(directory.resolve("build-profile.json5"), "{}\n");
    Path arkts = directory.resolve("Index.ets");
    Files.writeString(arkts, "export const a = 1;\n");
    Path typescript = directory.resolve("helper.ts");
    Files.writeString(typescript, "export const b = 1;\n");

    ArktsProjectDiscovery.Project project = ArktsProjectDiscovery.discover(arkts, null);

    assertThat(project.files())
        .as("the compiler needs the TypeScript next to the ArkTS to resolve the types")
        .containsExactlyInAnyOrder(arkts, typescript);
  }
}
