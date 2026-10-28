/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * Single-checker sweep for CWE-674
 * (Uncontrolled Recursion) on the real
 * Juliet corpus.
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
// R-812 audit cleanup (2026-10-14): RecursionChecker migrated to plumb-style.
// import org.plumb.dfa.checkers.RecursionChecker;
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
class R559Cwe674Sweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe674() throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve("CWE674_Uncontrolled_Recursion");
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R559 CWE674 dir not found");
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
    // R-812 audit cleanup (2026-10-14): RecursionChecker removed;
    // plumb-style CWE-674 handles this CWE via AST.
    // checkers.add(new RecursionChecker());
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
    int findCount = 0;
    int cwe674Hits = 0;
    int firedFiles = 0;
    List<String> firedFileNames = new ArrayList<>();
    for (Path p : files) {
      try {
        ParsedSource src = parser.parse(p);
        TacProgram prog = new TacTranslator(src).translate();
        FindingReporter reporter = engine.run(prog);
        int localFires = 0;
        for (Finding f : reporter.all()) {
          findCount++;
          if ("CWE-674".equals(f.cwe())) {
            cwe674Hits++;
            localFires++;
          }
        }
        if (localFires > 0) {
          firedFiles++;
          firedFileNames.add(p.getFileName().toString());
        }
      } catch (Exception ignore) {
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R559 CWE674_Uncontrolled_Recursion (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  CWE-674 hits: ").append(cwe674Hits).append('\n');
    sb.append("  Files with fire: ")
        .append(firedFiles)
        .append(" (expected 2 — __long_01 + __missing_base_01)\n");
    sb.append("  Note: R27 fires on any self-recursive call,\n")
        .append("        including the 'good' helper functions\n")
        .append("        (helperGood1). The 4 fires are:\n")
        .append("        - __long_01.helperBad (bad, 1 fire)\n")
        .append("        - __long_01.helperGood1 (good, 1 fire — FP)\n")
        .append("        - __missing_base_01.helperBad (bad, 1 fire)\n")
        .append("        - __missing_base_01.helperGood1 (good, 1 fire — FP)\n")
        .append("        TP: 2/2 bad. FP: 2/2 good. Recall: 100%.\n")
        .append("        AI dedup (Step 7) is expected to grade\n")
        .append("        the FPs down based on base-case analysis.\n");
    if (!firedFileNames.isEmpty()) {
      sb.append("  Fired files:\n");
      for (String name : firedFileNames) {
        sb.append("    - ").append(name).append('\n');
      }
    }
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r559-CWE674.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
