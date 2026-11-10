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
package org.plumb.tac.frontend.ts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The discovery of a TypeScript project's files.
 *
 * <h2>Why the exclusions matter</h2>
 *
 * <p>Every file the discovery adds is a file the compiler is asked to type check, and every file it
 * leaves out is a file whose types stay unknown. So each exclusion is a decision about what the
 * analysis sees:
 *
 * <ul>
 *   <li>a <b>declaration file</b> holds signatures and no executable body -- analysing it would
 *       report findings against declarations rather than against code;
 *   <li>the <b>dependency directory</b> and the <b>build outputs</b> hold other people's code and
 *       generated code, and a scan of them says nothing about the project;
 *   <li>a <b>dot directory</b> is a tool's own state.
 * </ul>
 */
class TsProjectDiscoveryTest {

  @Test
  @DisplayName("a declaration file is not a source file")
  void aDeclarationFileIsNotASourceFile(@TempDir Path directory) {
    assertThat(TsProjectDiscovery.isSourceFile(directory.resolve("app.ts"))).isTrue();
    assertThat(TsProjectDiscovery.isSourceFile(directory.resolve("app.d.ts")))
        .as("a declaration file holds no body")
        .isFalse();
    assertThat(TsProjectDiscovery.isSourceFile(directory.resolve("app.d.mts"))).isFalse();
    assertThat(TsProjectDiscovery.isSourceFile(directory.resolve("README.md"))).isFalse();
  }

  @Test
  @DisplayName("the project's files are its own, and not its dependencies'")
  void theProjectsFilesAreItsOwn(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("app.ts"), "export const a = 1;\n");
    Files.writeString(directory.resolve("helper.tsx"), "export const b = 1;\n");
    Files.createDirectories(directory.resolve("node_modules").resolve("left-pad"));
    Files.writeString(
        directory.resolve("node_modules").resolve("left-pad").resolve("index.js"),
        "module.exports = 1;\n");
    Files.createDirectories(directory.resolve("dist"));
    Files.writeString(directory.resolve("dist").resolve("bundle.js"), "var a = 1;\n");

    List<Path> files = TsProjectDiscovery.filesUnder(directory);

    assertThat(files)
        .extracting(path -> path.getFileName().toString())
        .containsExactlyInAnyOrder("app.ts", "helper.tsx");
  }

  @Test
  @DisplayName("the project root is the nearest directory that marks one")
  void theProjectRootIsTheNearestDirectoryThatMarksOne(@TempDir Path directory) throws IOException {
    Path nested = Files.createDirectories(directory.resolve("packages").resolve("app"));
    Files.writeString(directory.resolve("tsconfig.json"), "{}\n");
    Path file = nested.resolve("main.ts");
    Files.writeString(file, "export const a = 1;\n");

    assertThat(TsProjectDiscovery.markedRootOf(file))
        .as("the compiler's own project file marks the project")
        .isEqualTo(directory.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a package manifest does not mark the project")
  void aPackageManifestDoesNotMarkTheProject(@TempDir Path directory) throws IOException {
    Path nested = Files.createDirectories(directory.resolve("src"));
    Files.writeString(directory.resolve("package.json"), "{}\n");
    Path file = nested.resolve("main.ts");
    Files.writeString(file, "export const a = 1;\n");

    // A manifest is not what the compiler reads to decide what the project
    // holds, and it is a common thing to find in a HOME directory -- a
    // boundary that walked up to one would make the whole home directory a
    // project.
    assertThat(TsProjectDiscovery.markedRootOf(file)).isNull();
  }

  @Test
  @DisplayName("a file with no project around it is its own project")
  void aFileWithNoProjectAroundItIsItsOwnProject(@TempDir Path directory) throws IOException {
    Path file = directory.resolve("single.ts");
    Files.writeString(file, "export const a = 1;\n");

    TsProjectDiscovery.Project project = TsProjectDiscovery.discover(file, null);

    assertThat(project.root()).isEqualTo(directory.toAbsolutePath().normalize());
    assertThat(project.files()).hasSize(1);
    assertThat(project.truncated()).isFalse();
  }

  @Test
  @DisplayName("the discovery finds the project's own files")
  void theDiscoveryFindsTheProjectsOwnFiles(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("tsconfig.json"), "{}\n");
    Path file = directory.resolve("app.ts");
    Files.writeString(file, "export const a = 1;\n");
    Files.writeString(directory.resolve("types.d.ts"), "export declare const a: number;\n");

    TsProjectDiscovery.Project project = TsProjectDiscovery.discover(file, null);

    assertThat(project.files())
        .extracting(path -> path.getFileName().toString())
        .containsExactly("app.ts");
  }
}
