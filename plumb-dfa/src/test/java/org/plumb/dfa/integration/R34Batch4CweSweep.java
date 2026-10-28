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
 * Fourth batched sweep covering more
 * CWE families for coverage.
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
import org.plumb.dfa.checkers.taint.HardCodedKeyChecker;
// R-812 audit cleanup (2026-10-14): UninitNonnullDfaChecker +
// MissingInputValidationChecker migrated to plumb-style. Removed import.
// R-812 Batch 3: SuspiciousCommentChecker migrated to plumb-style
// (org.plumb.style.style.SuspiciousCommentCheck, CWE-546).
// import org.plumb.dfa.checkers.SuspiciousCommentChecker;
// R-812: ErrorMessageInfoLeakChecker migrated to plumb-style
// (org.plumb.style.error.ErrorMessageInfoLeakCheck, CWE-209).
// The DFA class is gone — kept the sweep harness here for
// historical reference; uncomment the import + usage when
// running against the plumb-style AST path.
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
class R34Batch4CweSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  private record SweepSpec(String dirName, List<DfaChecker> checkers) {}

  private static final List<SweepSpec> SPECS =
      List.of(
          new SweepSpec(
              "CWE329_Not_Using_Random_IV_with_CBC_Mode", List.of(new HardCodedKeyChecker())),
          new SweepSpec("CWE759_Unsalted_One_Way_Hash", List.of(new HardCodedKeyChecker())),
          new SweepSpec("CWE760_Predictable_Salt_One_Way_Hash", List.of(new HardCodedKeyChecker())),
          new SweepSpec("CWE111_Unsafe_JNI", List.of()),
          new SweepSpec("CWE226_Sensitive_Information_Uncleared_Before_Release", List.of()),
          new SweepSpec("CWE500_Public_Static_Field_Not_Final", List.of()),
          // R-812: ErrorMessageInfoLeakChecker migrated to
          // plumb-style. This sweep is @Disabled anyway.
          new SweepSpec(
              "CWE526_Info_Exposure_Environment_Variables",
              List.of(/* new ErrorMessageInfoLeakChecker() */ )),
          new SweepSpec(
              "CWE533_Info_Exposure_Server_Log", List.of(/* new ErrorMessageInfoLeakChecker() */ )),
          new SweepSpec(
              "CWE534_Info_Exposure_Debug_Log", List.of(/* new ErrorMessageInfoLeakChecker() */ )),
          new SweepSpec(
              "CWE535_Info_Exposure_Shell_Error",
              List.of(/* new ErrorMessageInfoLeakChecker() */ )));

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runBatch() throws Exception {
    long start = System.currentTimeMillis();
    StringBuilder out = new StringBuilder();
    out.append("R34 — Fourth batched CWE coverage sweep\n");
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
    Path outPath = Paths.get("D:/work/tmp/r500_juliet_sweep/r34-batch4.txt");
    Files.createDirectories(outPath.getParent());
    Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
    System.out.println(out);
  }
}
