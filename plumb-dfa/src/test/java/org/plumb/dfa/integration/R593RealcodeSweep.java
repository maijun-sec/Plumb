/*
 * Sweep ArrayIndexChecker on real code
 * (commons-lang3 + commons-codec) post the recent run
 * derived-guard propagation. Measures total
 * fires and per-rule breakdown.
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r593-realcode.txt.
 */
package org.plumb.dfa.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.plumb.dfa.checkers.taint.ArrayIndexChecker;
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
class R593RealcodeSweep {

  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path CODEC = Paths.get("D:/work/tmp/realcode/codec-src/codec");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r593-realcode.txt");

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void run() throws Exception {
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
    checkers.add(new ArrayIndexChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    StringBuilder sb = new StringBuilder();
    sb.append("R593 — ArrayIndexChecker on real code (post derived-guard)\n");
    Path[] roots = {LANG3, CODEC};
    String[] corpora = {"commons-lang3", "commons-codec"};
    Map<String, Integer> ruleCounts = new LinkedHashMap<>();
    Map<String, Integer> cweCounts = new LinkedHashMap<>();
    int totalFires = 0;
    int totalFiles = 0;
    for (int i = 0; i < corpora.length; i++) {
      List<Path> files = new ArrayList<>();
      if (!Files.isDirectory(roots[i])) continue;
      try (var stream = Files.walk(roots[i])) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(files::add);
      }
      totalFiles += files.size();
      int corpusFires = 0;
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            corpusFires++;
            totalFires++;
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
            if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
          }
        } catch (Exception ignore) {
        }
      }
      sb.append("\n")
          .append(corpora[i])
          .append(": ")
          .append(files.size())
          .append(" files, ")
          .append(corpusFires)
          .append(" fires\n");
    }
    sb.append("\nTotal: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(System.currentTimeMillis() - start)
        .append("ms\n");
    sb.append("By rule: ").append(ruleCounts).append('\n');
    sb.append("By CWE:  ").append(cweCounts).append('\n');
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString());
    System.out.println(sb);
  }
}
