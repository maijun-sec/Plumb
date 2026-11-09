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

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A C# file must reach the analysis, and the language choice must be honoured on the way there.
 *
 * <h2>What was broken</h2>
 *
 * <p>The C# frontend existed and produced TAC, but it was not in the CLI's file-discovery path: the
 * registry's extension table did not name {@code .cs}. A C# project therefore produced ZERO source
 * files, the scanner printed "no source files found", and it exited <b>0</b> -- success. A CI
 * pipeline reading that exit code would record a clean scan of a project that was never read.
 *
 * <p>So this class pins the two halves separately, because they fail in different ways:
 *
 * <ul>
 *   <li>the DISCOVERY half: {@code .cs} is a source file, and it is routed to the C# frontend;
 *   <li>the DISPATCH half: a registry that serves only Java does not build a C# frontend, so a
 *       Java-only scan pays nothing for the .NET sidecar.
 * </ul>
 */
class CsharpSourceDiscoveryTest {

  @Test
  @DisplayName("a .cs file is a source file Plumb can read")
  void csIsASourceFile() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("Program.cs"))).isTrue();
    assertThat(FrontendRegistry.isSourceFile(Path.of("src", "Fixture.cs"))).isTrue();
  }

  @Test
  @DisplayName("the .cs extension is in the table the file discovery uses")
  void csIsAKnownExtension() {
    assertThat(FrontendRegistry.knownExtensions()).contains("cs");
  }

  @Test
  @DisplayName("a .cs file is routed to the C# language")
  void csMapsToTheCsharpLanguage() {
    assertThat(FrontendRegistry.languageOfExtension("cs")).isEqualTo(SourceLanguage.CSHARP);
  }

  @Test
  @DisplayName("the other languages still map to themselves")
  void theOtherLanguagesAreUnchanged() {
    assertThat(FrontendRegistry.languageOfExtension("java")).isEqualTo(SourceLanguage.JAVA);
    assertThat(FrontendRegistry.languageOfExtension("kt")).isEqualTo(SourceLanguage.KOTLIN);
    assertThat(FrontendRegistry.languageOfExtension("kts")).isEqualTo(SourceLanguage.KOTLIN);
    assertThat(FrontendRegistry.languageOfExtension("scala")).isEqualTo(SourceLanguage.SCALA);
    assertThat(FrontendRegistry.languageOfExtension("sc")).isEqualTo(SourceLanguage.SCALA);
  }

  @Test
  @DisplayName("a file that is not source is still not source")
  void nonSourceFilesStayOut() {
    assertThat(FrontendRegistry.isSourceFile(Path.of("README.md"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("pom.xml"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("Makefile"))).isFalse();
    assertThat(FrontendRegistry.isSourceFile(Path.of("trailing."))).isFalse();
  }

  @Test
  @DisplayName("a registry that serves C# builds the C# frontend")
  void csharpIsServedWhenSelected() {
    // The Java frontend is the one that carries the parser, so it is always present. The
    // C# frontend is built on demand, and the registry is what decides whether it is built
    // at all.
    FrontendRegistry registry =
        new FrontendRegistry(null, java.util.List.of(), java.util.List.of(), java.util.Set.of());

    assertThat(registry.serves(SourceLanguage.CSHARP)).isTrue();
    assertThat(registry.frontendFor(SourceLanguage.CSHARP))
        .as("a registry that serves every language must build the C# frontend")
        .isNotNull()
        .satisfies(f -> assertThat(f.language()).isEqualTo(SourceLanguage.CSHARP));
  }

  @Test
  @DisplayName("a registry that does not serve C# does not build the C# frontend")
  void csharpIsNotServedWhenNotSelected() {
    FrontendRegistry registry =
        new FrontendRegistry(
            null, java.util.List.of(), java.util.List.of(), java.util.Set.of(SourceLanguage.JAVA));

    assertThat(registry.serves(SourceLanguage.CSHARP)).isFalse();
    assertThat(registry.frontendFor(SourceLanguage.CSHARP))
        .as("a Java-only scan must not build the C# frontend: it drives a .NET sidecar")
        .isNull();
  }
}
