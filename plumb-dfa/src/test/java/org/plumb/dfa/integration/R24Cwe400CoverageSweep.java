/*
 * Sweep the resource-leak / exception-handling
 * CWE families to check current coverage.
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
import org.plumb.dfa.checkers.controlflow.TryCatchFinallyChecker;
// R-812 audit cleanup (2026-10-14): UninitNonnullDfaChecker migrated to plumb-style.
// import org.plumb.dfa.checkers.UninitNonnullDfaChecker;
import org.plumb.dfa.checkers.taint.ResourceExhaustionChecker;
// R-812 audit cleanup (2026-10-14): ObsoleteFunctionChecker and
// UninitNonnullDfaChecker migrated to plumb-style. The sweep
// entries for CWE477 / CWE681 now resolve to the plumb-style
// equivalents auto-registered via AstCheckerRegistry.builtins().
// R-812: UncheckedReturnChecker migrated to plumb-style
// (org.plumb.style.security.UncheckedReturnCheck, CWE-252).
// import org.plumb.dfa.checkers.UncheckedReturnChecker;
import org.plumb.dfa.checkers.taint.ResourceLeakChecker;
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
class R24Cwe400CoverageSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  private record SweepSpec(String dirName, List<DfaChecker> checkers) {}

  private static final List<SweepSpec> SPECS =
      List.of(
          new SweepSpec("CWE400_Resource_Exhaustion", List.of(new ResourceExhaustionChecker())),
          new SweepSpec("CWE404_Improper_Resource_Shutdown", List.of(new ResourceLeakChecker())),
          new SweepSpec("CWE401_Memory_Leak", List.of(new ResourceLeakChecker())),
          new SweepSpec("CWE248_Uncaught_Exception", List.of(new TryCatchFinallyChecker())));

  // R-812 audit cleanup (2026-10-14): the following CWEs were
  // covered by plumb-dfa checkers before this audit and have
  // been migrated to plumb-style AST equivalents:
  //   - CWE477_Obsolete_Functions  (was ObsoleteFunctionChecker)
  //   - CWE674_Uncontrolled_Recursion  (was UncheckedReturnChecker)
  //   - CWE681_Incorrect_Conversion_Between_Numeric_Types  (was UninitNonnullDfaChecker)
  // The plumb-style AstCheckerRegistry.builtins() auto-registers
  // the replacements via buildStyleChecks(); the sweep above
  // therefore no longer includes these CWEs.

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runBatch() throws Exception {
    long start = System.currentTimeMillis();
    StringBuilder out = new StringBuilder();
    out.append("R24 →Resource / exception coverage sweep\n");
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

    int totalFires = 0;
    int totalFiles = 0;
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
            .filter(p -> !p.getFileName().toString().equals("Main.java"))
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
      totalFires += findCount;
      totalFiles += files.size();
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
    out.append("\n").append("=".repeat(60)).append('\n');
    out.append("TOTAL: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(elapsed)
        .append("ms\n");
    Path outPath = Paths.get("D:/work/tmp/r500_juliet_sweep/r24-coverage.txt");
    Files.createDirectories(outPath.getParent());
    Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }
}
