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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A rule id printed in the report has to select that rule when it is passed back.
 *
 * <p><b>Why this test exists.</b> A scan reports its findings with a rule id. The first thing a
 * user does with a finding they do not expect is copy its id out of the report and ask for just
 * that rule. That has to work. If it does not, the scan comes back empty — and an empty scan reads
 * as "the rule found nothing" rather than "the filter matched nothing", which is a false all-clear.
 *
 * <p>It did not work. The comparison between a filter entry and a rule id was re-implemented in
 * four places (the AST registration gate, the post-style finding filter, the registry builder, and
 * the CLI {@code --rule} filter), and each place had its own idea of what a "bare id" is. The
 * registry builder and the post-style filter stripped at the first dot, so {@code
 * ast-bug-incorrect-return-comparison} became {@code bug-incorrect-return-comparison}; the CLI
 * filter stripped only the first domain token, which produced the same wrong answer. The result was
 * that the printed id selected nothing at all, and a per-CWE YAML whitelist written against the
 * printed id selected nothing either — the whole Juliet CWE-253 measurement was silently measuring
 * an empty scan.
 *
 * <p>Now one predicate decides every comparison, and it expands both sides into every form the id
 * can reasonably be written in. This test pins that property from both directions: the id from the
 * report, and the short form a user would type.
 */
class RuleFilterRoundTripTest {

  /**
   * A source with the canonical Juliet CWE-253 pattern: a return value compared against the wrong
   * sentinel.
   */
  private static final String JULIET_CWE253_SOURCE =
      """
      import java.io.FileInputStream;
      import java.io.IOException;

      class RoundTrip {
        void bad() throws IOException {
          FileInputStream fis = new FileInputStream("c:\\\\file.txt");
          byte[] buf = new byte[1024];
          if (fis.read(buf) == 0) {
            System.out.println("empty");
          }
        }
      }
      """;

  /**
   * The rule id the fixture is expected to produce. Pinned so a rename cannot silently pass the
   * test.
   */
  private static final String EXPECTED_RULE_ID = "ast-bug-incorrect-return-comparison";

  private static final Pattern RULE_ID = Pattern.compile("\"rule_id\":\"([^\"]+)\"");

  @Test
  void theRuleIdFromTheReportSelectsThatRule(@TempDir Path dir) throws Exception {
    Path source = writeSource(dir);

    // 1. Scan with no filter and read the rule id off the report.
    Set<String> ids = scan(source, List.of());
    assertThat(ids)
        .as("the fixture has to produce a finding for the round trip to mean anything")
        .contains(EXPECTED_RULE_ID);

    // 2. Feed that exact id back to --rule. It has to select the same rule.
    Set<String> filtered = scan(source, List.of("--rule", EXPECTED_RULE_ID));
    assertThat(filtered)
        .as("the rule id printed in the report must select that rule when passed back to --rule")
        .containsExactly(EXPECTED_RULE_ID);
  }

  @Test
  void theShortFormOfTheRuleIdSelectsTheSameRule(@TempDir Path dir) throws Exception {
    Path source = writeSource(dir);

    Set<String> ids = scan(source, List.of());
    assertThat(ids).contains(EXPECTED_RULE_ID);

    // The short form is what a user types when they do not want to copy the whole id:
    // `ast-bug-incorrect-return-comparison` -> `incorrect-return-comparison`.
    String shortForm = shortFormOf(EXPECTED_RULE_ID);
    assertThat(shortForm).isEqualTo("incorrect-return-comparison");

    Set<String> filtered = scan(source, List.of("--rule", shortForm));
    assertThat(filtered)
        .as("the short form of the rule id must select the same rule as the full form")
        .containsExactly(EXPECTED_RULE_ID);
  }

  @Test
  void aYamlWhitelistWrittenAgainstThePrintedRuleIdSelectsThatRule(@TempDir Path dir)
      throws Exception {
    Path source = writeSource(dir);

    Set<String> ids = scan(source, List.of());
    assertThat(ids).contains(EXPECTED_RULE_ID);

    // A per-CWE config names the rule the way the report prints it.
    Path config = writeWhitelist(dir, "whitelist.yaml", EXPECTED_RULE_ID);

    Set<String> filtered = scan(source, List.of("--config", config.toString()));
    assertThat(filtered)
        .as("a yaml whitelist naming the printed rule id must select that rule")
        .containsExactly(EXPECTED_RULE_ID);
  }

  @Test
  void aYamlWhitelistNamingTheShortFormSelectsTheSameRule(@TempDir Path dir) throws Exception {
    Path source = writeSource(dir);

    Set<String> ids = scan(source, List.of());
    assertThat(ids).contains(EXPECTED_RULE_ID);

    Path config = writeWhitelist(dir, "whitelist-short.yaml", shortFormOf(EXPECTED_RULE_ID));

    Set<String> filtered = scan(source, List.of("--config", config.toString()));
    assertThat(filtered)
        .as("a yaml whitelist naming the short form must select the same rule")
        .containsExactly(EXPECTED_RULE_ID);
  }

  @Test
  void aYamlWhitelistThatNamesNothingDropsEverything(@TempDir Path dir) throws Exception {
    Path source = writeSource(dir);

    Path config = writeWhitelist(dir, "whitelist-other.yaml", "some-rule-that-does-not-exist");

    Set<String> filtered = scan(source, List.of("--config", config.toString()));
    assertThat(filtered)
        .as("a whitelist that names no rule must select no rule — the filter is not a no-op")
        .isEmpty();
  }

  @Test
  void aRuleFilterThatNamesNothingDropsEverything(@TempDir Path dir) throws Exception {
    Path source = writeSource(dir);

    Set<String> filtered = scan(source, List.of("--rule", "some-rule-that-does-not-exist"));
    assertThat(filtered).as("a --rule filter that names no rule must select no rule").isEmpty();
  }

  /**
   * The short form of a rule id: drop the leading domain tokens, keep the name.
   *
   * <p>For {@code ast-bug-incorrect-return-comparison} that is {@code incorrect-return-comparison}.
   * The rule id carries TWO domain-ish tokens ({@code ast} and {@code bug}) before the name, so
   * dropping only the first would leave {@code bug-incorrect-return-comparison} — which is a form
   * the user has to be able to write too, but not the one a human would write.
   *
   * <p>The engine accepts every intermediate form, so the test uses the form that drops the domains
   * and keeps the name.
   */
  private static String shortFormOf(String ruleId) {
    String rest = ruleId;
    for (int i = 0; i < 2; i++) {
      int dash = rest.indexOf('-');
      if (dash <= 0) break;
      rest = rest.substring(dash + 1);
    }
    return rest;
  }

  private static Path writeWhitelist(Path dir, String name, String entry) throws Exception {
    Path config = dir.resolve(name);
    Files.writeString(
        config,
        """
        rules:
          mode: whitelist
          enabled:
            - %s
        """
            .formatted(entry),
        StandardCharsets.UTF_8);
    return config;
  }

  private static Path writeSource(Path dir) throws Exception {
    Path pkg = Files.createDirectories(dir.resolve("src/roundtrip"));
    Path source = pkg.resolve("RoundTrip.java");
    Files.writeString(source, JULIET_CWE253_SOURCE, StandardCharsets.UTF_8);
    return source;
  }

  /**
   * Run the scanner over the source with the given extra CLI arguments and return the rule ids it
   * reported.
   *
   * <p>The CLI is driven through {@link SastMain} rather than through a hand-built {@link
   * ScanConfig}, because the property under test is a CLI-level one: the id a user copies out of
   * the report has to work when they pass it back on the command line.
   */
  private static Set<String> scan(Path source, List<String> extraArgs) throws Exception {
    Path out = Files.createTempFile("roundtrip", ".ndjson");
    List<String> args = new ArrayList<>();
    args.add("-i");
    args.add(source.toString());
    args.add("--format");
    args.add("ndjson");
    args.add("-o");
    args.add(out.toString());
    args.addAll(extraArgs);

    // The CLI calls System.exit(), so it runs in its own JVM.
    Path outDir = Files.createTempDirectory("roundtrip-cli");
    Path result = outDir.resolve("result.txt");
    List<String> cmd = new ArrayList<>();
    cmd.add(javaExecutable());
    cmd.add("-cp");
    cmd.add(System.getProperty("java.class.path"));
    cmd.add(SastMain.class.getName());
    cmd.addAll(args);
    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);
    pb.redirectOutput(result.toFile());
    Process p = pb.start();
    int rc = p.waitFor();
    String output = Files.readString(result, StandardCharsets.UTF_8);
    assertThat(rc).as("the scan must succeed. Output was:\n%s", output).isEqualTo(0);

    Set<String> ids = new LinkedHashSet<>();
    Matcher m = RULE_ID.matcher(Files.readString(out, StandardCharsets.UTF_8));
    while (m.find()) {
      ids.add(m.group(1));
    }
    return ids;
  }

  /** The java executable of the JVM running the test. */
  private static String javaExecutable() {
    String home = System.getProperty("java.home");
    Path exe = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
    return exe.toString();
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase().contains("win");
  }
}
