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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Go project's discovery, for a scan that had no build capture.
 *
 * <h2>Why the toolchain is looked for here</h2>
 *
 * <p>The standard library's source lives under the Go toolchain's root, and the front end has to
 * find it to type-check the project's dependencies. A capture names the toolchain outright, but a
 * scan with no capture has nothing to read that from -- so the go command on the PATH is the next
 * best answer.
 *
 * <p>And the environment's GOROOT is deliberately not the answer: it is a leftover of whatever
 * toolchain was installed last, it outlives the toolchain it names, and a value that points at a
 * directory which no longer exists makes the standard library's source look absent -- which makes
 * every standard library import fail silently.
 */
class GoProjectDiscoveryTest {

  @Test
  @DisplayName("the go command on the PATH names the toolchain's root")
  void theGoCommandNamesTheToolchainRoot(@TempDir Path directory) throws IOException {
    Path root = directory.resolve("go");
    Path bin = root.resolve("bin");
    Files.createDirectories(bin);
    // The standard library's source is what makes a directory a toolchain.
    Files.createDirectories(root.resolve("src"));
    Files.writeString(bin.resolve("go.exe"), "not a real toolchain");
    Path other = directory.resolve("elsewhere");
    Files.createDirectories(other);

    String path = other + java.io.File.pathSeparator + bin;

    assertThat(GoProjectDiscovery.toolchainRootIn(path)).isEqualTo(root.toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a PATH without a go command yields no toolchain")
  void aPathWithoutAGoCommandYieldsNoToolchain(@TempDir Path directory) throws IOException {
    Path empty = directory.resolve("empty");
    Files.createDirectories(empty);

    assertThat(GoProjectDiscovery.toolchainRootIn(empty.toString())).isNull();
  }

  @Test
  @DisplayName("a PATH with no go command at all yields no toolchain")
  void noPathYieldsNoToolchain() {
    assertThat(GoProjectDiscovery.toolchainRootIn(null)).isNull();
    assertThat(GoProjectDiscovery.toolchainRootIn("")).isNull();
  }
}
