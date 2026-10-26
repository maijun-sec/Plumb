package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the Gradle hooks.
 *
 * <p>These hooks are opt-in because they make the build slower. The test that matters is the
 * one that says they stay off unless asked for: a user who did not ask for a capture must not
 * pay for a disabled daemon.
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

  @Test
  void theHooksTargetTheGradleConfigurationApi() {
    // The classes Coverity hooks in its common-capture.properties.
    assertThat(GradleHooks.targetClasses())
        .containsExactly(
            "org.gradle.StartParameter", "org.gradle.launcher.daemon.configuration.DaemonParameters");
  }
}
