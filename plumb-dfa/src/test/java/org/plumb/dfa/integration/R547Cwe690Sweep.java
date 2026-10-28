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
 * Single-checker sweep for CWE-690 (NULL
 * Deref From Return). Runs NullDerefChecker (with
 * extensions: CALL receiver + may-return-null
 * heuristic + MOVE propagation) on the CWE-690
 * corpus. Writes per-CWE result to
 * D:/work/tmp/r500_juliet_sweep/r547-CWE690.txt.
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
import org.plumb.dfa.checkers.taint.NullDerefChecker;
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
class R547Cwe690Sweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe690NullDerefFromReturn() throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve("CWE690_NULL_Deref_From_Return");
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R547 CWE690 dir not found");
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
    checkers.add(new NullDerefChecker());
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
          .filter(p -> !p.getFileName().toString().endsWith("Helper.java"))
          .forEach(files::add);
    }
    java.util.Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    java.util.Map<String, Integer> cweCounts = new java.util.LinkedHashMap<>();
    int cwe690Hits = 0, cwe476Hits = 0;
    int aHit = 0, bHit = 0, goodHit = 0;
    int findCount = 0;
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
          findCount++;
          String ruleKey = f.checkerName();
          ruleCounts.merge(ruleKey, 1, Integer::sum);
          String cwe = f.cwe();
          if (cwe != null) cweCounts.merge(cwe, 1, Integer::sum);
          if ("CWE-690".equals(cwe)) cwe690Hits++;
          if ("CWE-476".equals(cwe)) cwe476Hits++;
          if (isBad) aHit++;
          else if (isB) bHit++;
          else if (isGood) goodHit++;
        }
      } catch (Exception ignore) {
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R547 CWE690_NULL_Deref_From_Return (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  CWE-690 hits: ").append(cwe690Hits).append('\n');
    sb.append("  CWE-476 hits: ").append(cwe476Hits).append('\n');
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
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r547-CWE690.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
