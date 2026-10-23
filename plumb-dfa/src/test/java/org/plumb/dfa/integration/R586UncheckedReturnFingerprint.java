/*
 * Investigate UncheckedReturn 16 fires on
 * commons-lang3. The previous run found 16 fires ? these should be
 * TPs (production code that doesn't check return values
 * is a real bug), but the Failiable list in
 * UncheckedReturnChecker may be over-matching.
 *
 * R-812 Batch 3: UncheckedReturnChecker migrated to plumb-style
 * (org.plumb.style.security.UncheckedReturnCheck). The import
 * is commented out so the file still compiles under the
 * @Disabled annotation.
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r586-unchecked-return.txt.
 */
package org.plumb.dfa.integration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
// R-812 Batch 3: UncheckedReturnChecker migrated to plumb-style.
// import org.plumb.dfa.checkers.UncheckedReturnChecker;
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
class R586UncheckedReturnFingerprint {

  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path OUT =
      Paths.get("D:/work/tmp/r500_juliet_sweep/r586-unchecked-return.txt");

  @Test
  void run() throws Exception {
    List<Path> files = new ArrayList<>();
    try (var stream = Files.walk(LANG3)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(files::add);
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
    // R-812: UncheckedReturnChecker migrated to plumb-style.
    // checkers.add(new UncheckedReturnChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    Map<String, Integer> methodFireCount = new java.util.LinkedHashMap<>();
    Map<String, Integer> fileFireCount = new java.util.LinkedHashMap<>();
    int totalFires = 0;
    for (Path p : files) {
      try {
        ParsedSource src = parser.parse(p);
        TacProgram prog = new TacTranslator(src).translate();
        FindingReporter reporter = engine.run(prog);
        int fileFires = 0;
        for (Finding f : reporter.all()) {
          fileFires++;
          totalFires++;
          // Extract method name from message
          String msg = f.message();
          if (msg.contains(":")) {
            int end = msg.indexOf(':');
            String method = msg.substring(0, end).trim();
            methodFireCount.merge(method, 1, Integer::sum);
          }
        }
        if (fileFires > 0) {
          fileFireCount.merge(p.getFileName().toString(), fileFires, Integer::sum);
        }
      } catch (Exception ignore) {
      }
    }
    StringBuilder sb = new StringBuilder();
    sb.append("R586 — UncheckedReturn on commons-lang3 (246 files, ")
        .append(totalFires)
        .append(" fires)\n");
    sb.append("\nBy method called without checking return:\n");
    methodFireCount.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .forEach(
            e ->
                sb.append("  ")
                    .append(String.format("%3d", e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n'));
    sb.append("\nBy file:\n");
    fileFireCount.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .limit(10)
        .forEach(
            e ->
                sb.append("  ")
                    .append(String.format("%3d", e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n'));
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
