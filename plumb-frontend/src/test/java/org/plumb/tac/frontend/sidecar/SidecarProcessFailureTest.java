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
package org.plumb.tac.frontend.sidecar;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the JVM says when a sidecar does not answer.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>A sidecar dies for reasons the JVM cannot see: the Node runtime cannot find the package the
 * script imports, the runtime is missing itself, the script has a syntax error. Each of those says
 * itself on the sidecar's stderr, and the sidecar's stderr is exactly where it goes. Without it the
 * reader gets a broken pipe and has to guess which of the reasons it was -- and the commonest one,
 * a dependency that was never installed, looks like a broken product rather than a missing setup
 * step.
 *
 * <p>So the sidecar's last words go into the message. This test drives a process that dies with
 * something to say, which needs no sidecar and no toolchain: the JVM that runs the test is itself a
 * program that can be started with an option it refuses.
 */
class SidecarProcessFailureTest {

  @Test
  @DisplayName("a dead sidecar's own words are in the message")
  void aDeadSidecarsOwnWordsAreInTheMessage() throws IOException {
    Path java = javaExecutable();
    SidecarProcess process =
        SidecarProcess.launch("stand-in", List.of(java.toString(), "--no-such-option"), null);
    try {
      assertThatThrownBy(() -> process.exchange("{}"))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("the stand-in sidecar did not answer")
          .hasMessageContaining("it exited with")
          .hasMessageContaining("Unrecognized option")
          // an end of file carries no message of its own, and a message that
          // says "null" says less than the name of the exception does
          .hasMessageNotContaining(": null");
    } finally {
      process.close();
    }
  }

  /**
   * The Java program of the JVM that runs this test.
   *
   * <p>The sidecar launcher refuses a program that names a path it cannot find, so the name has to
   * be the executable's own name and not the extension-less one.
   *
   * @return the Java executable
   */
  private static Path javaExecutable() {
    String name =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe"
            : "java";
    return Path.of(System.getProperty("java.home"), "bin", name);
  }
}
