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
 * Single-checker sweep for CWE-400 (Resource
 * Exhaustion). Runs ONLY the ResourceExhaustionChecker
 * on the CWE-400 corpus to measure cweFire
 * without loading 30+ checkers (which would take
 * 10+ min for 2400 files). Writes a per-CWE result to
 * D:/work/tmp/r500_juliet_sweep/r546-CWE400.txt.
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
import org.plumb.dfa.checkers.taint.ResourceExhaustionChecker;
import org.plumb.dfa.checkers.taint.UncheckedLoopConditionChecker;
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

/** fast single-CWE sweep for CWE-400. */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R546Cwe400Sweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe400SleepSweep() throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve("CWE400_Resource_Exhaustion");
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R546 CWE400 dir not found");
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
    // Single-checker: ResourceExhaustionChecker + sibling
    // UncheckedLoopConditionChecker for for_loop variants.
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new ResourceExhaustionChecker());
    checkers.add(new UncheckedLoopConditionChecker());
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
    int cwe400Hits = 0, cwe606Hits = 0;
    int sleepHits = 0, forLoopHits = 0, writeHits = 0;
    int findCount = 0;
    int aHit = 0, bHit = 0, goodHit = 0;
    for (Path p : files) {
      String fn = p.getFileName().toString();
      boolean isBad = fn.matches(".*_\\d+a\\.java$");
      boolean isB = fn.matches(".*_\\d+b\\.java$");
      boolean isGood =
          fn.contains("goodG2B")
              || fn.contains("goodB2G")
              || fn.matches(".*_\\d+c\\.java$")
              || fn.contains("good1");
      try {
        ParsedSource src = parser.parse(p);
        TacProgram prog = new TacTranslator(src).translate();
        FindingReporter reporter = engine.run(prog);
        for (Finding f : reporter.all()) {
          findCount++;
          String ruleKey = f.checkerName();
          ruleCounts.merge(ruleKey, 1, Integer::sum);
          String cwe = f.cwe();
          if (cwe != null) cweCounts.merge(cwe, 1, Integer::sum);
          if ("CWE-400".equals(cwe)) cwe400Hits++;
          if ("CWE-606".equals(cwe)) cwe606Hits++;
          if (fn.contains("sleep_") && "CWE-400".equals(cwe)) sleepHits++;
          if (fn.contains("_for_loop") && "CWE-606".equals(cwe)) forLoopHits++;
          if (fn.contains("_write") && "CWE-606".equals(cwe)) writeHits++;
          if (isBad) aHit++;
          else if (isB) bHit++;
          else if (isGood) goodHit++;
        }
      } catch (Exception ignore) {
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R546 CWE400_Resource_Exhaustion (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  CWE-400 hits: ").append(cwe400Hits).append('\n');
    sb.append("  CWE-606 hits: ").append(cwe606Hits).append(" (sibling for_loop/write)\n");
    sb.append("    sleep_   CWE-400: ").append(sleepHits).append('\n');
    sb.append("    for_loop CWE-606: ").append(forLoopHits).append('\n');
    sb.append("    _write   CWE-606: ").append(writeHits).append('\n');
    sb.append("  a_hit: ")
        .append(aHit)
        .append("  b_hit: ")
        .append(bHit)
        .append("  good_hit: ")
        .append(goodHit)
        .append('\n');
    sb.append("  by rule:\n");
    ruleCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    sb.append("  by cwe:\n");
    cweCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r546-CWE400.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
