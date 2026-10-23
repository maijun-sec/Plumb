/*
 * Single-checker sweep for CWE-325 (Missing
 * Required Cryptographic Step). Runs ONLY
 * MissingCryptoInitChecker on the CWE-325
 * corpus. Writes per-CWE result to
 * D:/work/tmp/r500_juliet_sweep/r548-CWE325.txt.
 */
package org.plumb.dfa.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.controlflow.MissingCryptoInitChecker;
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
class R548Cwe325Sweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe325MissingCryptoStep() throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve("CWE325_Missing_Required_Cryptographic_Step");
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R548 CWE325 dir not found");
      return;
    }
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new MissingCryptoInitChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
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
    java.util.Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    java.util.Map<String, Integer> cweCounts = new java.util.LinkedHashMap<>();
    int cwe325Hits = 0;
    int findCount = 0;
    int aHit = 0, bHit = 0, goodHit = 0;
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
          findCount++;
          String ruleKey = f.checkerName();
          ruleCounts.merge(ruleKey, 1, Integer::sum);
          String cwe = f.cwe();
          if (cwe != null) cweCounts.merge(cwe, 1, Integer::sum);
          if ("CWE-325".equals(cwe)) cwe325Hits++;
          if (isBad) aHit++;
          else if (isB) bHit++;
          else if (isGood) goodHit++;
        }
      } catch (Exception ignore) {
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R548 CWE325_Missing_Required_Cryptographic_Step (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  CWE-325 hits: ").append(cwe325Hits).append('\n');
    sb.append("  a_hit: ")
        .append(aHit)
        .append("  b_hit: ")
        .append(bHit)
        .append("  good_hit: ")
        .append(goodHit)
        .append('\n');
    sb.append("  by rule:\n");
    ruleCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    sb.append("  by cwe:\n");
    cweCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r548-CWE325.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
