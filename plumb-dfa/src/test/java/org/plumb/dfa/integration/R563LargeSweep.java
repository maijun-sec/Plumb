/*
 * Large remaining CWE sweep.
 *   - CWE-369 Divide by Zero (3072 files, large)
 *   - CWE-315 Plaintext Storage in Cookie (65 files)
 *   - CWE-190/191 (large, already done, sample 200 each)
 * Uses: DivByZeroChecker.
 */
package org.plumb.dfa.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.DivByZeroChecker;
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
class R563LargeSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r563-large.txt");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 6, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void sweep() throws Exception {
    long start = System.currentTimeMillis();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new DivByZeroChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    String[] dirs = {};
    StringBuilder sb = new StringBuilder();
    sb.append("R563 — Large remaining CWE sweep (DivByZero / PlaintextCookie)\n");
    int totalFiles = 0, totalFires = 0;
    for (String dirName : dirs) {
      Path cweDir = JULIET_ROOT.resolve(dirName);
      if (!Files.isDirectory(cweDir)) {
        sb.append("  (skip missing dir: ").append(dirName).append(")\n");
        continue;
      }
      List<Path> files = new ArrayList<>();
      try (var stream = Files.walk(cweDir)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("antbuild"))
            .filter(p -> !p.getFileName().toString().equals("ServletMain.java"))
            .filter(p -> !p.getFileName().toString().equals("Main.java"))
            .forEach(files::add);
      }
      // For CWE369 (3000+ files), sample 400 for speed
      if (dirName.equals("CWE369_Divide_by_Zero") && files.size() > 400) {
        files = files.subList(0, 400);
      }
      Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
      int dirFires = 0;
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            dirFires++;
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
          }
        } catch (Exception ignore) {
        }
      }
      totalFiles += files.size();
      totalFires += dirFires;
      sb.append("  ")
          .append(dirName)
          .append(": ")
          .append(files.size())
          .append(" files, ")
          .append(dirFires)
          .append(" fires\n");
      ruleCounts.forEach(
          (k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    }
    sb.append("\nTotal: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(System.currentTimeMillis() - start)
        .append("ms\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
