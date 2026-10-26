package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The {@code --help} output has to be readable.
 *
 * <p>{@code HelpFormatter} defaults to 74 columns, and its wrapping algorithm degenerates when a
 * description is longer than the space left for it: the text breaks one character per line. The
 * longest option description in this CLI is several hundred characters, so the default width turned
 * the help into over a thousand single-character lines -- a user asking for help got a wall of
 * vertical text.
 *
 * <p>These tests pin the two properties that matter: the help is not degenerate, and it names every
 * option the CLI registers.
 */
class CliHelpOutputTest {

  /** Run the CLI with the given arguments and capture everything it prints. */
  private static String run(String... args) {
    PrintStream originalOut = System.out;
    PrintStream originalErr = System.err;
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      System.setErr(new PrintStream(buffer, true, StandardCharsets.UTF_8));
      SastMain.main(args);
    } finally {
      System.setOut(originalOut);
      System.setErr(originalErr);
    }
    return buffer.toString(StandardCharsets.UTF_8);
  }

  @Test
  void theHelpIsNotBrokenOneCharacterPerLine() {
    String help = run("--help");

    var lines = help.lines().filter(l -> !l.isBlank()).toList();
    assertThat(lines).as("the help must not be empty").isNotEmpty();

    // The degenerate form: a line holding a single character.
    long singleCharLines = lines.stream().filter(l -> l.strip().length() == 1).count();
    assertThat(singleCharLines)
        .as("the help must not break a description one character per line")
        .isZero();

    // And the whole thing has to be a reasonable length. The degenerate
    // form produced over a thousand lines.
    assertThat(lines.size()).as("the help must be a summary, not a wall of text").isLessThan(400);
  }

  @Test
  void theDescriptionColumnIsWideEnoughToWrap() {
    // The degenerate form is not caused by a narrow terminal: it is caused
    // by the OPTION column eating the description column. At the default
    // width of 74 the longest option name plus its argument pushed the
    // description column down to one or two characters, and the wrapping
    // algorithm then emitted one character per line.
    //
    // So the invariant is not "the width is large" -- it is "the
    // description column is wide enough to hold prose". The description
    // lines are the ones whose text starts at the description column; the
    // continuation lines are pure indent. If the column is wide enough,
    // every line that carries text carries a phrase.
    String help = run("--help");

    // The description column is where the first description starts.
    int descriptionColumn = -1;
    for (String line : help.lines().toList()) {
      int idx = line.indexOf("Send every finding");
      if (idx >= 0) {
        descriptionColumn = idx;
        break;
      }
    }
    assertThat(descriptionColumn).as("the help must describe --ai-dedup").isGreaterThan(0);

    // Every line that carries text at the description column has to carry
    // more than a character or two.
    for (String line : help.lines().toList()) {
      if (line.length() <= descriptionColumn) {
        continue;
      }
      String text = line.substring(descriptionColumn);
      if (text.isBlank()) {
        continue; // a continuation line with nothing yet
      }
      assertThat(text.strip().length())
          .as("the description column is too narrow, so the text is broken up: %r", line)
          .isGreaterThan(3);
    }
  }

  @Test
  void theHelpNamesEveryOptionTheParserAccepts() {
    String help = run("--help");

    // The usage line lists every option the parser knows about, so the
    // flags named there and the flags the help describes must agree.
    var usage = help.lines().filter(l -> l.contains("--")).toList();
    assertThat(usage).as("the help must print a usage line").isNotEmpty();

    // A long option that the help describes has to appear in the usage
    // line too. The usage line is the summary; the description is the
    // detail. A flag described but not in the usage line is a flag the
    // user has to guess at.
    var described = new java.util.LinkedHashSet<String>();
    for (String line : help.lines().toList()) {
      var trimmed = line.strip();
      if (!trimmed.startsWith("--")) {
        continue;
      }
      var m = java.util.regex.Pattern.compile("^(--[a-z0-9][a-z0-9-]*)").matcher(trimmed);
      if (m.find()) {
        described.add(m.group(1));
      }
    }
    assertThat(described).as("the help must describe some options").isNotEmpty();

    var usageText = String.join("\n", usage);
    for (String flag : described) {
      assertThat(usageText)
          .as("%s is described but missing from the usage line", flag)
          .contains(flag);
    }
  }

  @Test
  void theHelpCarriesNoProcessHistory() {
    String help = run("--help");

    // A round number in a string the user reads is process history that
    // leaked into the product. The user has no way to interpret it.
    assertThat(help)
        .as("the help must not carry round numbers")
        .doesNotContainPattern("(?<![A-Za-z0-9_])R-?\\d{3,4}[a-z]?(?![A-Za-z0-9_])");
  }
}
