package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for the agent's own arguments.
 *
 * <p><b>Why the agent needs arguments at all.</b> Gradle does not forward system properties to the
 * JVM it builds in. Measured on Gradle 9.2.0: {@code org.gradle.jvmargs} keeps the {@code
 * -javaagent} and drops every {@code -D}; {@code systemProp.} in {@code gradle.properties} applies
 * to the build JVM while the compile runs in the forked daemon.
 *
 * <p>The agent's own arguments are the one channel that survives, because {@code -javaagent} is
 * forwarded and {@code -javaagent:jar=key=value} hands the arguments to {@code premain}.
 */
class AgentArgumentsTest {

  @Test
  void noArgumentsChangeNothing() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.clearProperty("plumb.test.agent.arg");
    try {
      CaptureAgent.applyAgentArguments(null);
      CaptureAgent.applyAgentArguments("");
      CaptureAgent.applyAgentArguments("   ");
      assertThat(System.getProperty("plumb.test.agent.arg")).isNull();
    } finally {
      if (before != null) {
        System.setProperty("plumb.test.agent.arg", before);
      }
    }
  }

  @Test
  void oneArgumentBecomesASystemProperty() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.clearProperty("plumb.test.agent.arg");
    try {
      CaptureAgent.applyAgentArguments("plumb.test.agent.arg=value");
      assertThat(System.getProperty("plumb.test.agent.arg")).isEqualTo("value");
    } finally {
      if (before != null) {
        System.setProperty("plumb.test.agent.arg", before);
      } else {
        System.clearProperty("plumb.test.agent.arg");
      }
    }
  }

  @Test
  void severalArgumentsAllBecomeSystemProperties() {
    String first = System.getProperty("plumb.test.agent.one");
    String second = System.getProperty("plumb.test.agent.two");
    System.clearProperty("plumb.test.agent.one");
    System.clearProperty("plumb.test.agent.two");
    try {
      CaptureAgent.applyAgentArguments("plumb.test.agent.one=1,plumb.test.agent.two=2");
      assertThat(System.getProperty("plumb.test.agent.one")).isEqualTo("1");
      assertThat(System.getProperty("plumb.test.agent.two")).isEqualTo("2");
    } finally {
      restore("plumb.test.agent.one", first);
      restore("plumb.test.agent.two", second);
    }
  }

  @Test
  void whitespaceAroundTheSeparatorIsIgnored() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.clearProperty("plumb.test.agent.arg");
    try {
      CaptureAgent.applyAgentArguments("  plumb.test.agent.arg  =  value  ");
      assertThat(System.getProperty("plumb.test.agent.arg")).isEqualTo("value");
    } finally {
      restore("plumb.test.agent.arg", before);
    }
  }

  /**
   * A value may contain an {@code =}.
   *
   * <p>Only the first one separates the key from the value, so a value like {@code a=b} survives.
   */
  @Test
  void onlyTheFirstSeparatorCounts() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.clearProperty("plumb.test.agent.arg");
    try {
      CaptureAgent.applyAgentArguments("plumb.test.agent.arg=a=b");
      assertThat(System.getProperty("plumb.test.agent.arg")).isEqualTo("a=b");
    } finally {
      restore("plumb.test.agent.arg", before);
    }
  }

  /**
   * A malformed argument is skipped, not fatal.
   *
   * <p>An agent that refuses to start because one argument is wrong would be a worse failure than
   * an argument that is ignored.
   */
  @Test
  void malformedArgumentsAreSkipped() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.clearProperty("plumb.test.agent.arg");
    try {
      CaptureAgent.applyAgentArguments("noequalssign,,=novalue,plumb.test.agent.arg=ok");
      assertThat(System.getProperty("plumb.test.agent.arg")).isEqualTo("ok");
    } finally {
      restore("plumb.test.agent.arg", before);
    }
  }

  /**
   * An explicit system property wins over an agent argument.
   *
   * <p>The system property was set deliberately; the agent argument is a default.
   */
  @Test
  void anExplicitSystemPropertyWins() {
    String before = System.getProperty("plumb.test.agent.arg");
    System.setProperty("plumb.test.agent.arg", "explicit");
    try {
      CaptureAgent.applyAgentArguments("plumb.test.agent.arg=fromArgument");
      assertThat(System.getProperty("plumb.test.agent.arg")).isEqualTo("explicit");
    } finally {
      restore("plumb.test.agent.arg", before);
    }
  }

  private static void restore(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }
}
