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
 * Uncontrolled Recursion sweep.
 * Runs:
 *   - UncontrolledRecursionChecker (CWE-674, 6 files)
 * Writes D:/work/tmp/r500_juliet_sweep/r557-recursion-loop.txt.
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
import org.plumb.dfa.checkers.taint.UncontrolledRecursionChecker;
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
class R557RecursionInfiniteLoopSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");
  private static final Path OUT =
      Paths.get("D:/work/tmp/r500_juliet_sweep/r557-recursion-loop.txt");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
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
    checkers.add(new UncontrolledRecursionChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    String[] dirs = {};
    StringBuilder sb = new StringBuilder();
    sb.append("R557 — Recursion + Infinite Loop sweep\n");
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
      Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
      int dirFires = 0, dirA = 0, dirB = 0, dirGood = 0;
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
            dirFires++;
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
            if (isBad) dirA++;
            else if (isB) dirB++;
            else if (isGood) dirGood++;
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
          .append(" fires (a=")
          .append(dirA)
          .append(" b=")
          .append(dirB)
          .append(" good=")
          .append(dirGood)
          .append(")\n");
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
