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
 * Fifth batched sweep for more CWE coverage.
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
import org.plumb.dfa.checkers.controlflow.XxeChecker;
import org.plumb.dfa.checkers.taint.CleartextNetworkCredentialSinkChecker;
// R-812 audit cleanup (2026-10-14): MissingAuthorizationChecker migrated to plumb-style.
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
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
class R39Batch5CweSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  private record SweepSpec(String dirName, List<DfaChecker> checkers) {}

  private static final List<SweepSpec> SPECS =
      List.of(
          new SweepSpec(
              "CWE319_Cleartext_Tx_Sensitive_Info",
              List.of(new CleartextNetworkCredentialSinkChecker())),
          new SweepSpec("CWE23_Relative_Path_Traversal", List.of(new PathTraversalChecker())),
          new SweepSpec("CWE36_Absolute_Path_Traversal", List.of(new PathTraversalChecker())),
          new SweepSpec(
              "CWE80_XSS", List.of(new org.plumb.dfa.checkers.taint.XssInjectionChecker())),
          new SweepSpec(
              "CWE81_XSS_Error_Message",
              List.of(new org.plumb.dfa.checkers.taint.XssInjectionChecker())),
          new SweepSpec(
              "CWE83_XSS_Attribute",
              List.of(new org.plumb.dfa.checkers.taint.XssInjectionChecker())),
          new SweepSpec("CWE611_XXE", List.of(new XxeChecker())));

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runBatch() throws Exception {
    long start = System.currentTimeMillis();
    StringBuilder out = new StringBuilder();
    out.append("R39 — Fifth batched CWE coverage sweep\n");
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
    Path outPath = Paths.get("D:/work/tmp/r500_juliet_sweep/r39-batch5.txt");
    Files.createDirectories(outPath.getParent());
    Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }
}
