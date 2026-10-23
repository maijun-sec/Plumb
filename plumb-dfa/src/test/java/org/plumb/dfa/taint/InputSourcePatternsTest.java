package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the shared input-source pattern catalogue.
 *
 * <p>The mirror checkers (IntOverflow, DivByZero, ...) need to recognise the same set of "input
 * source" calls (readLine, parseInt, etc.). This test locks the contract for the shared catalogue
 * so the checkers can't drift.
 */
class InputSourcePatternsTest {

  @Test
  void defaults_containsReadLineAndParseMethods() {
    assertThat(InputSourcePatterns.DEFAULT_PATTERNS)
        .contains(
            ".readLine",
            ".read",
            ".parseByte",
            ".parseShort",
            ".parseInt",
            ".parseLong",
            ".parseDouble",
            ".parseFloat");
  }

  @Test
  void defaults_containsScannerNextMethods() {
    assertThat(InputSourcePatterns.DEFAULT_PATTERNS)
        .contains(
            ".nextInt",
            ".nextLong",
            ".nextShort",
            ".nextByte",
            ".nextLine",
            ".nextFloat",
            ".nextDouble");
  }

  @Test
  void defaults_containsSystemEnv() {
    assertThat(InputSourcePatterns.DEFAULT_PATTERNS)
        .contains("System.getenv", "System.getProperty");
  }

  @Test
  void staticIsInputSourceCall_matchesAllDefaultPatterns() {
    // Sanity: each default pattern matches
    // a synthetic callee.
    for (String p : InputSourcePatterns.DEFAULT_PATTERNS) {
      String synth = "java.foo.Foo" + p + "/0";
      assertThat(InputSourcePatterns.isInputSourceCall(synth))
          .as("expected match for pattern %s in %s", p, synth)
          .isTrue();
    }
  }

  @Test
  void staticIsInputSourceCall_nullCalleeIsFalse() {
    assertThat(InputSourcePatterns.isInputSourceCall(null)).isFalse();
  }

  @Test
  void staticIsInputSourceCall_rejectsUnrelatedCall() {
    assertThat(InputSourcePatterns.isInputSourceCall("java.util.ArrayList#add/2")).isFalse();
    assertThat(InputSourcePatterns.isInputSourceCall("java.io.PrintStream#println/1")).isFalse();
  }

  @Test
  void staticIsInputSourceCall_matchesCalleeSigFormat() {
    // Real IR form is
    // "ClassName.methodName/arity", with
    // a dot before the method name (e.g.
    // "java.io.BufferedReader.readLine/0").
    assertThat(InputSourcePatterns.isInputSourceCall("java.io.BufferedReader.readLine/0")).isTrue();
    assertThat(InputSourcePatterns.isInputSourceCall("java.lang.Integer.parseInt/1")).isTrue();
  }

  @Test
  void customCatalogue_canExtendDefaults() {
    InputSourcePatterns custom = new InputSourcePatterns(List.of(".fooBar", ".baz"));
    assertThat(custom.matches("com.x.Foo.fooBar/0")).isTrue();
    assertThat(custom.matches("com.x.Foo.baz/1")).isTrue();
    // readLine is NOT in custom
    assertThat(custom.matches("java.io.BufferedReader.readLine/0")).isFalse();
  }

  @Test
  void customCatalogue_doesNotMutateDefaults() {
    // Sanity: adding a custom pattern
    // does not pollute DEFAULT_PATTERNS.
    int before = InputSourcePatterns.DEFAULT_PATTERNS.size();
    new InputSourcePatterns(List.of(".fooBar"));
    assertThat(InputSourcePatterns.DEFAULT_PATTERNS).hasSize(before);
  }
}
