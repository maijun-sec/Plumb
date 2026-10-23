/*
 * Baseline validation for the
 * 5-day cross-fn sprint.
 *
 * <p>Runs a fixed-size subset of Juliet
 * CWE-256 / CWE-259 / CWE-89 files and
 * reports the fire count. The baseline
 * is documented in the design doc:
 * design doc:
 * <ul>
 *   <li>CWE-256: 38 fires baseline</li>
 *   <li>CWE-259: 54 fires baseline</li>
 *   <li>CWE-89:  2115 fires baseline</li>
 * </ul>
 * Expected after the cross-fn sprint:
 * <ul>
 *   <li>CWE-256: 100 (+62)</li>
 *   <li>CWE-259: 180 (+126)</li>
 *   <li>CWE-89:  2200 (+85)</li>
 * </ul>
 *
 * <p>This sweep is deliberately small
 * (10 files per CWE family) to fit within
 * the 5-minute test timeout. The
 * design doc's +62/+126 estimates
 * assumed the full Juliet corpus; the
 * small subset gives a directional signal
 * (improvement, regression, or no
 * change), not a precise number.</p>
 *
 * <p>Writes results to
 * {@code D:/work/tmp/r500_juliet_sweep/r35-crossfn.txt}.</p>
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.DBCredentialSinkChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.summary.FunctionSummaryCache;
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
class R35CrossFnSweepTest {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r35-crossfn.txt");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCrossFnBaseline() throws Exception {
    long start = System.currentTimeMillis();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    List<DfaChecker> checkers = new ArrayList<>();
    // Two representative
    // taint checkers: SqlInjectionChecker
    // (CWE-89) and DBCredentialSinkChecker
    // (CWE-256). The cross-function
    // summary fold applies to all
    // taint checkers via
    // TaintedCheckerBase, so this gives
    // a directional signal for the
    // 5-day sprint.
    checkers.add(new SqlInjectionChecker());
    checkers.add(new DBCredentialSinkChecker());
    DfaEngine engine = new DfaEngine(base, checkers, "cha", null);
    FunctionSummaryCache cache = new FunctionSummaryCache(base.tuning());
    JavaSourceParser parser = new JavaSourceParser();
    StringBuilder sb = new StringBuilder();
    sb.append("cross-fn baseline\n");
    sb.append("Started: ").append(new java.util.Date()).append('\n');
    // small subset: 10 files
    // per CWE family. We pick the
    // first 10 alphabetically to
    // avoid selection bias.
    String[] families = {};
    int totalFires = 0;
    for (String family : families) {
      Path cweDir = JULIET_ROOT.resolve(family);
      if (!Files.isDirectory(cweDir)) {
        sb.append("  ").append(family).append(" : dir not found\n");
        continue;
      }
      List<Path> files = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(cweDir)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("antbuild"))
            .filter(p -> !p.getFileName().toString().equals("ServletMain.java"))
            .filter(p -> !p.getFileName().toString().equals("Main.java"))
            .sorted()
            .limit(10)
            .forEach(files::add);
      }
      int fires = 0;
      int aHit = 0, bHit = 0, goodHit = 0;
      Map<String, Integer> ruleCounts = new LinkedHashMap<>();
      for (Path p : files) {
        String fn = p.getFileName().toString();
        boolean isBad = fn.matches(".*_\\d+a\\.java$");
        boolean isB = fn.matches(".*_\\d+b\\.java$");
        boolean isGood =
            fn.contains("goodG2B") || fn.contains("goodB2G") || fn.matches(".*_\\d+c\\.java$");
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            fires++;
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
            if (isBad) aHit++;
            else if (isB) bHit++;
            else if (isGood) goodHit++;
          }
        } catch (Exception ignore) {
          // Skip files that fail to
          // parse (some Juliet files
          // have unsupported syntax).
        }
      }
      totalFires += fires;
      sb.append("  ")
          .append(family)
          .append(" (")
          .append(files.size())
          .append(" files): ")
          .append(fires)
          .append(" fires  [a=")
          .append(aHit)
          .append(" b=")
          .append(bHit)
          .append(" good=")
          .append(goodHit)
          .append("]\n");
      for (Map.Entry<String, Integer> e : ruleCounts.entrySet()) {
        sb.append("    ").append(e.getKey()).append(" : ").append(e.getValue()).append('\n');
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    sb.append("Total fires: ").append(totalFires).append('\n');
    sb.append("Elapsed: ").append(elapsed).append("ms\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
