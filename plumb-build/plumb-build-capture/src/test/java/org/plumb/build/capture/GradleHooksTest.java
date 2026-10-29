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
package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the Gradle hooks.
 *
 * <p>These hooks are opt-in because they make the build slower. The test that matters is the one
 * that says they stay off unless asked for: a user who did not ask for a capture must not pay for a
 * disabled daemon.
 */
class GradleHooksTest {

  @Test
  void theHooksAreOffByDefault() {
    assertThat(GradleHooks.enabled()).isFalse();
  }

  @Test
  void theHooksCanBeTurnedOn() {
    String previous = System.getProperty(GradleHooks.PROP_DISABLE_DAEMON);
    System.setProperty(GradleHooks.PROP_DISABLE_DAEMON, "true");
    try {
      assertThat(GradleHooks.enabled()).isTrue();
    } finally {
      if (previous == null) {
        System.clearProperty(GradleHooks.PROP_DISABLE_DAEMON);
      } else {
        System.setProperty(GradleHooks.PROP_DISABLE_DAEMON, previous);
      }
    }
  }

  /**
   * The environment variable is the channel that reaches Gradle's JVM.
   *
   * <p>Measured on Gradle 9.2.0: {@code org.gradle.jvmargs} forwards the {@code -javaagent} and
   * drops every {@code -D}, so a property set that way never arrives. The environment, on the other
   * hand, is inherited by the JVM Gradle builds in.
   *
   * <p>The test cannot set an environment variable for the current process, so it checks the
   * constant and the fallback logic instead: the property wins when it is set, and the environment
   * is consulted when it is not.
   */
  @Test
  void theEnvironmentVariableIsASecondChannel() {
    assertThat(GradleHooks.ENV_DISABLE_DAEMON).isEqualTo("PLUMB_CAPTURE_GRADLE_HOOKS");

    // The environment variable is not set in this JVM, so with no property
    // the hooks stay off -- the property is what the test above turns on.
    String previous = System.getProperty(GradleHooks.PROP_DISABLE_DAEMON);
    System.clearProperty(GradleHooks.PROP_DISABLE_DAEMON);
    try {
      assertThat(GradleHooks.enabled())
          .as("with neither channel set, the hooks stay off")
          .isEqualTo(Boolean.parseBoolean(System.getenv(GradleHooks.ENV_DISABLE_DAEMON)));
    } finally {
      if (previous != null) {
        System.setProperty(GradleHooks.PROP_DISABLE_DAEMON, previous);
      }
    }
  }

  @Test
  void theHooksTargetTheGradleConfigurationApi() {
    // The classes the reference hooks in its common-capture.properties.
    assertThat(GradleHooks.targetClasses())
        .containsExactly(
            "org.gradle.StartParameter",
            "org.gradle.launcher.daemon.configuration.DaemonParameters");
  }
}
