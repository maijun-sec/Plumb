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
// R-812 Batch 3: CleartextSocketUsageChecker migrated to plumb-style
// (org.plumb.style.security.CleartextSocketUsageCheck, CWE-319).
// import org.plumb.dfa.checkers.CleartextSocketUsageChecker;
import org.plumb.dfa.checkers.taint.CleartextNetworkCredentialSinkChecker;
import org.plumb.dfa.checkers.taint.DBCredentialSinkChecker;
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

/** R542 — single-checker sweep on CWE-319. */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R542CleartextSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe319CleartextTx() throws Exception {
    runOneCwe("CWE319_Cleartext_Tx_Sensitive_Info");
  }

  private void runOneCwe(String cweName) throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve(cweName);
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R542 " + cweName + ": dir not found");
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
    // R-812 Batch 3: CleartextSocketUsageChecker migrated to plumb-style
    // (org.plumb.style.security.CleartextSocketUsageCheck, CWE-319).
    // checkers.add(new CleartextSocketUsageChecker());
    checkers.add(new DBCredentialSinkChecker());
    checkers.add(new CleartextNetworkCredentialSinkChecker());
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
    int expectedCwe = 0;
    String expectedCweStr = extractCweNumber(cweName);
    int findCount = 0;
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
          if (expectedCweStr != null && expectedCweStr.equals(f.cwe())) expectedCwe++;
        }
      } catch (Exception ignore) {
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R542 ")
        .append(cweName)
        .append(" (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  expectedCwe=")
        .append(expectedCweStr)
        .append(" -> ")
        .append(expectedCwe)
        .append(" hits\n");
    sb.append("  by rule:\n");
    ruleCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    sb.append("  by cwe:\n");
    cweCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r542-" + cweName + ".txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
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
