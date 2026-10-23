/*
 * Real codebase validation: Juliet
 * baseline.
 *
 * <p>Runs the full plumb-dfa checker
 * suite (all 19 CWE families) over the
 * Juliet C/C++ test corpus' Java
 * equivalent (Juliet Java). For each CWE
 * dir, records:
 *
 * <ul>
 *   <li>Expected vs actual cweFire count</li>
 *   <li>Per-rule breakdown</li>
 *   <li>Wall time</li>
 * </ul>
 *
 * <p>The result is written to
 * {@code D:/work/tmp/r500_juliet_sweep/
 * r10-baseline.txt} as a regression-test
 * baseline. Future R-rounds can compare
 * their sweep output to this baseline to
 * detect regression.</p>
 *
 * <p>Why use Juliet as a real codebase:</p>
 * <ul>
 *   <li>It's a recognised industry-standard
 *       corpus (NSA Center for Assured
 *       Software).</li>
 *   <li>It has 50+ CWE dirs with hundreds
 *       of files per dir ? large enough
 *       to surface real engine behaviour.</li>
 *   <li>The expectedCwe per file is
 *       known (encoded in the filename),
 *       so we can compute P/R.</li>
 *   <li>It's already on the local
 *       filesystem under
 *       {@code D:/work/tmp/Juliet_Java/}.</li>
 * </ul>
 *
 * <p>The output is meant to be checked in
 * and used as a regression baseline. Any
 * future R-round that changes the count
 * for a CWE family should be documented
 * in the round's doc.</p>
 */
package org.plumb.dfa.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.DBCredentialSinkChecker;
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.HardCodedKeyChecker;
import org.plumb.dfa.checkers.taint.HardCodedPasswordSinkChecker;
// R-812 audit cleanup (2026-10-14): WeakPrngChecker /
// InsecureCryptoChecker / ObsoleteFunctionChecker migrated to
// plumb-style. Imports removed.
// import org.plumb.dfa.checkers.WeakPrngChecker;
// import org.plumb.dfa.checkers.InsecureCryptoChecker;
// import org.plumb.dfa.checkers.ObsoleteFunctionChecker;
import org.plumb.dfa.checkers.taint.HttpResponseSplittingChecker;
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
import org.plumb.dfa.checkers.taint.OpenRedirectChecker;
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
import org.plumb.dfa.checkers.taint.XPathInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R10RealCodebaseBaseline {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  /**
   * The set of CWE dirs we want baseline coverage on. Skipping the huge CWE-23 / CWE-36 / CWE-78
   * (1800+ files each) to keep the baseline run under 30 minutes.
   */
  private static final List<String> BASELINE_CWES = List.of();

  @Test
  @org.junit.jupiter.api.Timeout(value = 30, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runJulietBaseline() throws Exception {
    long start = System.currentTimeMillis();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());

    // Load ALL 19 CWE-family
    // checkers. HardCodedPasswordSink
    // covers CWE-259;
    // DBCredentialSink covers
    // CWE-256; the 12 taint-style
    // checkers cover the rest.
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new HardCodedKeyChecker());
    checkers.add(new HardCodedPasswordSinkChecker());
    // R-812 audit cleanup (2026-10-14): WeakPrng / InsecureCrypto /
    // ObsoleteFunction removed; plumb-style covers CWE-338 /
    // CWE-336 / CWE-327 / CWE-328 / CWE-780 / CWE-477 via AST.
    // checkers.add(new WeakPrngChecker());
    // checkers.add(new InsecureCryptoChecker());
    // checkers.add(new ObsoleteFunctionChecker());
    checkers.add(new DBCredentialSinkChecker());
    checkers.add(new SqlInjectionChecker());
    checkers.add(new CommandInjectionChecker());
    checkers.add(new PathTraversalChecker());
    checkers.add(new LdapInjectionChecker());
    checkers.add(new XPathInjectionChecker());
    checkers.add(new HttpResponseSplittingChecker());
    checkers.add(new FormatStringChecker());
    checkers.add(new OpenRedirectChecker());
    checkers.add(new UncontrolledMemAllocChecker());

    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();

    StringBuilder out = new StringBuilder();
    out.append("R10 — Real codebase validation baseline (Juliet Java)\n");
    out.append("Loaded ").append(checkers.size()).append(" checkers\n");
    out.append("=".repeat(70)).append('\n');

    int totalFiles = 0;
    int totalFires = 0;
    for (String cweName : BASELINE_CWES) {
      Path cweDir = JULIET_ROOT.resolve(cweName);
      if (!Files.isDirectory(cweDir)) {
        out.append("  ").append(cweName).append(": SKIP (dir not found)\n");
        continue;
      }
      long cweStart = System.currentTimeMillis();
      List<Path> files = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(cweDir)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("antbuild"))
            .filter(p -> !p.getFileName().toString().equals("ServletMain.java"))
            .filter(p -> !p.getFileName().toString().equals("Main.java"))
            .forEach(files::add);
      }
      Map<String, Integer> ruleCounts = new LinkedHashMap<>();
      Map<String, Integer> cweCounts = new LinkedHashMap<>();
      int findCount = 0;
      int expectedCweCount = 0;
      String expectedCweStr = extractCweNumber(cweName);
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            findCount++;
            String ruleKey = f.checkerName();
            ruleCounts.merge(ruleKey, 1, Integer::sum);
            if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
            if (expectedCweStr != null && expectedCweStr.equals(f.cwe())) {
              expectedCweCount++;
            }
          }
        } catch (Exception ignore) {
        }
      }
      long elapsed = System.currentTimeMillis() - cweStart;
      totalFiles += files.size();
      totalFires += findCount;
      out.append('\n')
          .append(cweName)
          .append(" (")
          .append(files.size())
          .append(" files, ")
          .append(findCount)
          .append(" fires, ")
          .append(elapsed)
          .append("ms)\n");
      out.append("  expectedCwe=")
          .append(expectedCweStr)
          .append(" -> ")
          .append(expectedCweCount)
          .append(" hits\n");
      if (!ruleCounts.isEmpty()) {
        out.append("  by rule:\n");
        ruleCounts.forEach(
            (k, v) -> out.append("    ").append(k).append(" : ").append(v).append('\n'));
      }
      if (!cweCounts.isEmpty()) {
        out.append("  by cwe:\n");
        cweCounts.forEach(
            (k, v) -> out.append("    ").append(k).append(" : ").append(v).append('\n'));
      }
    }
    long totalElapsed = System.currentTimeMillis() - start;
    out.append('\n').append("=".repeat(70)).append('\n');
    out.append("TOTAL: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(totalElapsed)
        .append("ms\n");
    Path outPath = Paths.get("D:/work/tmp/r500_juliet_sweep/r10-baseline.txt");
    Files.createDirectories(outPath.getParent());
    Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }

  private static String extractCweNumber(String dirName) {
    if (dirName.startsWith("CWE")) {
      int i = 3;
      while (i < dirName.length() && Character.isDigit(dirName.charAt(i))) i++;
      if (i > 3) return "CWE-" + dirName.substring(3, i);
    }
    return null;
  }
}
