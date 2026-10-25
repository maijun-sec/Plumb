package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * Step 7 ? Tests for {@code ScanRunner.computeDedupStats} and the {@link ScanRunner.DedupStats}
 * helpers, plus the {@code --ai-dedup-stats} stderr printer. The verdict map is now {@code
 * Map<String, String>} with values formatted as {@code "VERDICT|reason"} (Step 6 used {@code
 * Map<String, AIDedupResult>} via reflection; Step 7 drops the indirection and the test inputs are
 * now plain strings).
 */
class RAiDedupStatsTest {

  private PrintStream originalErr;
  private ByteArrayOutputStream errBuf;

  @BeforeEach
  void captureStderr() {
    originalErr = System.err;
    errBuf = new ByteArrayOutputStream();
    System.setOut(new PrintStream(errBuf, true, java.nio.charset.StandardCharsets.UTF_8));
    System.setErr(new PrintStream(errBuf, true, java.nio.charset.StandardCharsets.UTF_8));
  }

  @AfterEach
  void restoreStderr() {
    System.setOut(originalErr);
    System.setErr(originalErr);
  }

  // ─── computeDedupStats ──────────────────────────────────

  @Test
  void computeDedupStatsTalliesVerdicts() {
    Finding f1 = finding("file:Line1:msg-A", "SQL_INJECTION", 1);
    Finding f2 = finding("file:Line2:msg-B", "SQL_INJECTION", 2);
    Finding f3 = finding("file:Line3:msg-C", "SQL_INJECTION", 3);
    Map<String, String> verdicts = new LinkedHashMap<>();
    verdicts.put(keyOf(f1), "TP|real sink");
    verdicts.put(keyOf(f2), "FP|sanitized");
    verdicts.put(keyOf(f3), "UNKNOWN|no reply");

    ScanRunner.DedupStats stats =
        ScanRunner.computeDedupStats(List.of(f1, f2, f3), verdicts, false);

    assertThat(stats.total).isEqualTo(3);
    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.TP)[0]).isEqualTo(1);
    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.FP)[0]).isEqualTo(1);
    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.UNKNOWN)[0]).isEqualTo(1);
  }

  @Test
  void computeDedupStatsTalliesPerRule() {
    // 2 SQL_INJECTION (1 TP, 1 FP), 2 HARDCODED_CREDENTIAL (2 FP)
    Finding f1 = finding("a:1:m1", "SQL_INJECTION", 1);
    Finding f2 = finding("a:2:m2", "SQL_INJECTION", 2);
    Finding f3 = finding("a:3:m3", "HARDCODED_CREDENTIAL", 3);
    Finding f4 = finding("a:4:m4", "HARDCODED_CREDENTIAL", 4);
    Map<String, String> verdicts = new LinkedHashMap<>();
    verdicts.put(keyOf(f1), "TP|sink");
    verdicts.put(keyOf(f2), "FP|guard");
    verdicts.put(keyOf(f3), "FP|test fixture");
    verdicts.put(keyOf(f4), "FP|test fixture");

    ScanRunner.DedupStats stats =
        ScanRunner.computeDedupStats(List.of(f1, f2, f3, f4), verdicts, false);

    // SQL_INJECTION: 1 TP, 1 FP, 0 UNKNOWN, 2 total
    assertThat(stats.perRuleCounts.get("SQL_INJECTION")).containsExactly(1, 1, 0, 2);
    // HARDCODED_CREDENTIAL: 0 TP, 2 FP, 0 UNKNOWN, 2 total
    assertThat(stats.perRuleCounts.get("HARDCODED_CREDENTIAL")).containsExactly(0, 2, 0, 2);
  }

  @Test
  void computeDedupStatsClampsUnknownVerdictString() {
    // A model that invents "MAYBE" should be bucketed as UNKNOWN.
    Finding f = finding("a:1:m", "WEAK_CRYPTO", 1);
    Map<String, String> verdicts = Map.of(keyOf(f), "MAYBE|idk");

    ScanRunner.DedupStats stats = ScanRunner.computeDedupStats(List.of(f), verdicts, false);

    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.TP)[0]).isEqualTo(0);
    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.FP)[0]).isEqualTo(0);
    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.UNKNOWN)[0]).isEqualTo(1);
  }

  @Test
  void computeDedupStatsWithMissingKeyCountsAsUnknown() {
    // A finding with no entry in the verdict map means
    // the AI layer never produced a verdict for it
    // (network timeout, parse failure, etc).
    Finding f = finding("a:1:m", "WEAK_CRYPTO", 1);
    Map<String, String> verdicts = Map.of(); // empty

    ScanRunner.DedupStats stats = ScanRunner.computeDedupStats(List.of(f), verdicts, false);

    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.UNKNOWN)[0]).isEqualTo(1);
  }

  @Test
  void computeDedupStatsWithNullVerdictFieldIsUnknown() {
    // Map value "null" (literal) — should be bucketed as UNKNOWN.
    Finding f = finding("a:1:m", "XSS", 1);
    Map<String, String> verdicts = Map.of(keyOf(f), "null");

    ScanRunner.DedupStats stats = ScanRunner.computeDedupStats(List.of(f), verdicts, false);

    assertThat(stats.verdictCounts.get(ScanRunner.Verdict.UNKNOWN)[0]).isEqualTo(1);
  }

  @Test
  void verdictParseClampsCustomStrings() {
    assertThat(ScanRunner.Verdict.parse("TP")).isEqualTo(ScanRunner.Verdict.TP);
    assertThat(ScanRunner.Verdict.parse("FP")).isEqualTo(ScanRunner.Verdict.FP);
    assertThat(ScanRunner.Verdict.parse("UNKNOWN")).isEqualTo(ScanRunner.Verdict.UNKNOWN);
    assertThat(ScanRunner.Verdict.parse("tp")).isEqualTo(ScanRunner.Verdict.TP);
    assertThat(ScanRunner.Verdict.parse("Maybe")).isEqualTo(ScanRunner.Verdict.UNKNOWN);
    assertThat(ScanRunner.Verdict.parse(null)).isEqualTo(ScanRunner.Verdict.UNKNOWN);
  }

  @Test
  void dedupStatsWithDroppedAndWithKeptReturnCopies() {
    Finding f = finding("a:1:m", "SQL_INJECTION", 1);
    Map<String, String> verdicts = Map.of(keyOf(f), "TP");
    ScanRunner.DedupStats base = ScanRunner.computeDedupStats(List.of(f), verdicts, false);
    ScanRunner.DedupStats withDropped = base.withDropped(5);
    ScanRunner.DedupStats withKept = base.withKept(3);

    assertThat(base.droppedFp).isEqualTo(0);
    assertThat(base.kept).isEqualTo(0);
    assertThat(withDropped.droppedFp).isEqualTo(5);
    assertThat(withDropped.kept).isEqualTo(0);
    assertThat(withKept.droppedFp).isEqualTo(0);
    assertThat(withKept.kept).isEqualTo(3);
  }

  // ─── printDedupStats (stderr capture) ────────────────────

  @Test
  void printDedupStatsWritesVerdictDistribution() {
    Finding f1 = finding("a:1:m1", "SQL_INJECTION", 1);
    Finding f2 = finding("a:2:m2", "SQL_INJECTION", 2);
    Finding f3 = finding("a:3:m3", "HARDCODED_CREDENTIAL", 3);
    Map<String, String> verdicts = new LinkedHashMap<>();
    verdicts.put(keyOf(f1), "TP|sink");
    verdicts.put(keyOf(f2), "FP|guard");
    verdicts.put(keyOf(f3), "FP|fixture");

    ScanRunner.DedupStats stats =
        ScanRunner.computeDedupStats(List.of(f1, f2, f3), verdicts, false)
            .withDropped(1) // 1 FP dropped because dropOnAiFp
            .withKept(2);

    ScanRunner.printDedupStats(stats, 1_234L);

    String out = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(out).contains("AI dedup stats");
    assertThat(out).contains("Total findings:   3");
    assertThat(out).contains("Reviewed:         3 (100.0%)");
    assertThat(out).contains("Dropped (FP):     1");
    assertThat(out).contains("Kept:             2");
    // Elapsed formats 1234ms as "1s"
    assertThat(out).contains("Elapsed:          1s");
    assertThat(out).contains("Verdict distribution:");
    assertThat(out).contains("TP");
    assertThat(out).contains("FP");
    assertThat(out).contains("UNKNOWN");
  }

  @Test
  void printDedupStatsWritesTopRulesByFpRatio() {
    Finding f1 = finding("a:1:m1", "SQL_INJECTION", 1);
    Finding f2 = finding("a:2:m2", "SQL_INJECTION", 2);
    Finding f3 = finding("a:3:m3", "SQL_INJECTION", 3);
    Finding f4 = finding("a:4:m4", "HARDCODED_CREDENTIAL", 4);
    Map<String, String> verdicts = new LinkedHashMap<>();
    verdicts.put(keyOf(f1), "FP|guard");
    verdicts.put(keyOf(f2), "FP|guard");
    verdicts.put(keyOf(f3), "TP|real");
    verdicts.put(keyOf(f4), "FP|fixture");

    ScanRunner.DedupStats stats =
        ScanRunner.computeDedupStats(List.of(f1, f2, f3, f4), verdicts, false);

    ScanRunner.printDedupStats(stats, 0L);

    String out = errBuf.toString(java.nio.charset.StandardCharsets.UTF_8);
    assertThat(out).contains("Top 10 rules by FP ratio (FP / total):");
    // Both rules should appear, with FP count / total
    assertThat(out).contains("SQL_INJECTION");
    assertThat(out).contains("2/3");
    assertThat(out).contains("HARDCODED_CREDENTIAL");
    assertThat(out).contains("1/1");
  }

  // ─── format helpers ──────────────────────────────────────

  @Test
  void pctHandlesZeroTotal() {
    // Avoid div-by-zero when there are no findings.
    assertThat(ScanRunner.pctPublic(0, 0)).isEqualTo("0.0");
  }

  @Test
  void padLeftRightAlignsNumbers() {
    assertThat(ScanRunner.padLeftPublic("135", 6)).isEqualTo("   135");
    assertThat(ScanRunner.padLeftPublic("4", 6)).isEqualTo("     4");
    assertThat(ScanRunner.padLeftPublic("1234567", 6)).isEqualTo("1234567");
  }

  @Test
  void padRightLeftAlignsLabels() {
    assertThat(ScanRunner.padRightPublic("TP", 8)).isEqualTo("TP      ");
    assertThat(ScanRunner.padRightPublic("UNKNOWN", 8)).isEqualTo("UNKNOWN ");
    assertThat(ScanRunner.padRightPublic("VERY_LONG_LABEL", 4)).isEqualTo("VERY_LONG_LABEL");
  }

  @Test
  void formatElapsedHandlesAllUnits() {
    assertThat(ScanRunner.formatElapsedPublic(500)).isEqualTo("500ms");
    assertThat(ScanRunner.formatElapsedPublic(999)).isEqualTo("999ms");
    assertThat(ScanRunner.formatElapsedPublic(1000)).isEqualTo("1s");
    assertThat(ScanRunner.formatElapsedPublic(59_000)).isEqualTo("59s");
    assertThat(ScanRunner.formatElapsedPublic(60_000)).isEqualTo("1m0s");
    assertThat(ScanRunner.formatElapsedPublic(125_000)).isEqualTo("2m5s");
    assertThat(ScanRunner.formatElapsedPublic(3_600_000)).isEqualTo("60m0s");
  }

  // ─── helpers ─────────────────────────────────────────────

  private static Finding finding(String testKey, String ruleName, int line) {
    Path fakeFile = Path.of(testKey.split(":", 2)[0] + ".java");
    String message = "synthetic-" + ruleName + "-" + line;
    // ScanRunner.ruleNameOf prefers ruleId.id() over
    // ruleId.name(), so we put the human-readable
    // rule name in id() (and a UUID in name() for
    // uniqueness across the synthetic findings).
    RuleDef rule =
        new RuleDef(
            new RuleId(ruleName, java.util.UUID.randomUUID().toString()),
            ruleName + " test",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.SECURITY,
            List.of("CWE-000"),
            "test.Fake",
            true);
    return new Finding(
        fakeFile, line, 0, rule, message, RuleDef.Severity.MEDIUM, "head", List.of(), null, null);
  }

  private static String keyOf(Finding f) {
    return f.file().toString() + ":" + f.line() + ":" + f.message().hashCode();
  }
}
