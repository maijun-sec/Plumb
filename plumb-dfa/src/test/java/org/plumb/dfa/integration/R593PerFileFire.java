/*
 * Per-file fire distribution for lang3
 * (post the recent run). Shows which files have the most
 * ArrayIndex fires.
 */
package org.plumb.dfa.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
class R593PerFileFire {

  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r593-perfile.txt");

  @Test
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  void run() throws Exception {
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
    Map<String, Integer> fileFires = new TreeMap<>();
    List<Path> files = new ArrayList<>();
    try (var stream = Files.walk(LANG3)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(files::add);
    }
    for (Path p : files) {
      try {
        ParsedSource src = parser.parse(p);
        TacProgram prog = new TacTranslator(src).translate();
        FindingReporter reporter = engine.run(prog);
        int n = 0;
        for (Finding f : reporter.all()) {
          n++;
        }
        if (n > 0) {
          String name = p.getFileName().toString();
          fileFires.merge(name, n, Integer::sum);
        }
      } catch (Exception ignore) {
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("R593 — Per-file fire count (lang3, post R593)\n");
    // Sort by count descending
    List<Map.Entry<String, Integer>> sorted = new ArrayList<>(fileFires.entrySet());
    sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));
    for (Map.Entry<String, Integer> e : sorted) {
      sb.append("  ").append(e.getValue()).append("  ").append(e.getKey()).append('\n');
    }
    sb.append("\nTotal: ")
        .append(fileFires.values().stream().mapToInt(Integer::intValue).sum())
        .append(" fires in ")
        .append(fileFires.size())
        .append(" files\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString());
    System.out.println(sb);
  }
}
