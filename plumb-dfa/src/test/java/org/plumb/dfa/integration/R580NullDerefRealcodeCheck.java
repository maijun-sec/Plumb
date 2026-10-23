/*
 * Check NullDerefChecker + UncontrolledMemAlloc + UncheckedReturn
 * on real code (post the recent fix). See if these are TPs or FPs.
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r580-realcode-misc.txt.
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
// R-812 Batch 3: UncheckedReturnChecker migrated to plumb-style
// (org.plumb.style.security.UncheckedReturnCheck, CWE-252).
// import org.plumb.dfa.checkers.UncheckedReturnChecker;
import org.plumb.dfa.checkers.taint.NullDerefChecker;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
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
class R580NullDerefRealcodeCheck {

  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path CODEC = Paths.get("D:/work/tmp/realcode/codec-src/codec");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r580-realcode-misc.txt");

  @Test
  @org.junit.jupiter.api.Timeout(value = 3, unit = TimeUnit.MINUTES)
  void run() throws Exception {
    long start = System.currentTimeMillis();
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new NullDerefChecker());
    checkers.add(new UncontrolledMemAllocChecker());
    // R-812: UncheckedReturnChecker migrated to plumb-style.
    // checkers.add(new UncheckedReturnChecker());
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    StringBuilder sb = new StringBuilder();
    sb.append("R580 — NullDeref + UncontrolledMemAlloc + UncheckedReturn on real code\n");
    Path[] roots = {LANG3, CODEC};
    String[] corpora = {"commons-lang3", "commons-codec"};
    Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    for (int i = 0; i < corpora.length; i++) {
      List<Path> files = new ArrayList<>();
      try (var stream = Files.walk(roots[i])) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(files::add);
      }
      Map<String, Integer> corpusRule = new java.util.LinkedHashMap<>();
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            corpusRule.merge(f.checkerName(), 1, Integer::sum);
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
          }
        } catch (Exception ignore) {
        }
      }
      sb.append("\n").append(corpora[i]).append(": ").append(corpusRule).append('\n');
    }
    sb.append("\nTotal: ").append(ruleCounts).append('\n');
    sb.append("Time: ").append(System.currentTimeMillis() - start).append("ms\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
