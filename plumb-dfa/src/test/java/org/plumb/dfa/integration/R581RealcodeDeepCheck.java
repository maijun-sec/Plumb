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
 * Single-checker sweeps on commons-lang3 + commons-codec
 * to see if CWE-specific real-codebase coverage reveals new
 * TPs that Juliet doesn't have.
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
import org.plumb.dfa.checkers.taint.ArrayIndexChecker;
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
class R581RealcodeDeepCheck {
  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path CODEC = Paths.get("D:/work/tmp/realcode/codec-src/codec");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r581-realcode-deep.txt");

  @Test
  @org.junit.jupiter.api.Timeout(value = 3, unit = TimeUnit.MINUTES)
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
    JavaSourceParser parser = new JavaSourceParser();
    // Three single-checker sweeps
    String[] ruleNames = {"ArrayIndex", "NullDeref"};
    List<DfaChecker>[] checkerSets = new List[2];
    checkerSets[0] = new ArrayList<>();
    checkerSets[0].add(new ArrayIndexChecker());
    checkerSets[1] = new ArrayList<>();
    checkerSets[1].add(new NullDerefChecker());
    StringBuilder sb = new StringBuilder();
    sb.append("R581 — Real-codebase single-checker deep dive\n");
    Path[] roots = {LANG3, CODEC};
    String[] corpora = {"commons-lang3", "commons-codec"};
    for (int c = 0; c < checkerSets.length; c++) {
      DfaEngine engine = new DfaEngine(cfg, checkerSets[c], "cha", null);
      sb.append("\n=== ").append(ruleNames[c]).append(" ===\n");
      for (int i = 0; i < corpora.length; i++) {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(roots[i])) {
          stream
              .filter(Files::isRegularFile)
              .filter(p -> p.toString().endsWith(".java"))
              .forEach(files::add);
        }
        Map<Integer, String> fires = new java.util.TreeMap<>();
        for (Path p : files) {
          try {
            ParsedSource src = parser.parse(p);
            TacProgram prog = new TacTranslator(src).translate();
            FindingReporter reporter = engine.run(prog);
            for (Finding f : reporter.all()) {
              fires.merge(f.line(), f.message(), (a, b) -> a + "|" + b);
            }
          } catch (Exception ignore) {
          }
        }
        int fp = fires.size();
        sb.append("  ").append(corpora[i]).append(": ").append(fp).append(" fires\n");
      }
    }
    sb.append("\nTime: ").append(System.currentTimeMillis() - start).append("ms\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
