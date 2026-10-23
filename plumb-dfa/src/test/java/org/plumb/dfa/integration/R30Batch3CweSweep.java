/*
 * Third batched sweep for CWE family coverage.
 * Adds more CWE dirs to the coverage map.
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
import org.plumb.dfa.checkers.controlflow.OffByOneChecker;
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
class R30Batch3CweSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  private record SweepSpec(String dirName, List<DfaChecker> checkers) {}

  private static final List<SweepSpec> SPECS =
      List.of(new SweepSpec("CWE193_Off_by_One_Error", List.of(new OffByOneChecker())));

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runBatch() throws Exception {
    long start = System.currentTimeMillis();
    StringBuilder out = new StringBuilder();
    out.append("R30 — Third batched CWE coverage sweep\n");
    out.append("=".repeat(60)).append('\n');

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    JavaSourceParser parser = new JavaSourceParser();

    for (SweepSpec spec : SPECS) {
      Path cweDir = JULIET_ROOT.resolve(spec.dirName);
      if (!Files.isDirectory(cweDir)) {
        out.append("  ").append(spec.dirName).append(": SKIP (dir not found)\n");
        continue;
      }
      DfaEngine engine = new DfaEngine(cfg, spec.checkers, "cha", null);
      List<Path> files = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(cweDir)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .filter(p -> !p.toString().contains("antbuild"))
            .forEach(files::add);
      }
      Map<String, Integer> cweCounts = new LinkedHashMap<>();
      int findCount = 0;
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          for (Finding f : reporter.all()) {
            findCount++;
            if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
          }
        } catch (Exception ignore) {
        }
      }
      out.append('\n')
          .append(spec.dirName)
          .append(" (")
          .append(files.size())
          .append(" files, ")
          .append(findCount)
          .append(" fires)\n");
      cweCounts.forEach(
          (k, v) -> out.append("    ").append(k).append(" : ").append(v).append('\n'));
    }
    long elapsed = System.currentTimeMillis() - start;
    out.append("\nTotal: ").append(elapsed).append("ms\n");
    Path outPath = Paths.get("D:/work/tmp/r500_juliet_sweep/r30-batch3.txt");
    Files.createDirectories(outPath.getParent());
    Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }
}
