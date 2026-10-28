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
 * Multi-realcodebase validation.
 * Runs the Plumb DFA checkers on:
 *   1. Apache Commons Lang3 (246 files, baseline)
 *   2. Apache Commons Codec (80 files)
 * Goal: confirm the recent fixes work across multiple
 * real-world libraries. Look for new FP patterns that
 * weren't visible in a single corpus.
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r578-multi-realcode.txt.
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.ArrayIndexChecker;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.DBCredentialSinkChecker;
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.HardCodedKeyChecker;
import org.plumb.dfa.checkers.taint.HardCodedPasswordSinkChecker;
// R-812 audit cleanup (2026-10-14): InsecureCrypto /
// ObsoleteFunction / ProcessControl / WeakPrng migrated to
// plumb-style. Imports removed.
// import org.plumb.dfa.checkers.InsecureCryptoChecker;
// import org.plumb.dfa.checkers.ObsoleteFunctionChecker;
// import org.plumb.dfa.checkers.ProcessControlChecker;
// R-812 Batch 3: SuspiciousCommentChecker migrated to plumb-style
// (org.plumb.style.style.SuspiciousCommentCheck, CWE-546).
// import org.plumb.dfa.checkers.SuspiciousCommentChecker;
// R-812 Batch 3: UncheckedReturnChecker migrated to plumb-style
// (org.plumb.style.security.UncheckedReturnCheck, CWE-252).
// import org.plumb.dfa.checkers.UncheckedReturnChecker;
// import org.plumb.dfa.checkers.WeakPrngChecker;
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
import org.plumb.dfa.checkers.taint.NullDerefChecker;
import org.plumb.dfa.checkers.taint.NumericTruncationChecker;
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
import org.plumb.dfa.checkers.taint.UnsafeReflectionChecker;
import org.plumb.dfa.checkers.taint.XPathInjectionChecker;
import org.plumb.dfa.checkers.taint.XssInjectionChecker;
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
class R578MultiRealcodeValidation {

  private static final Path OUT =
      Paths.get("D:/work/tmp/r500_juliet_sweep/r578-multi-realcode.txt");
  private static final Path LANG3 = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path CODEC = Paths.get("D:/work/tmp/realcode/codec-src/codec");

  private static boolean realcodeExists() {
    return Files.isDirectory(LANG3) && Files.isDirectory(CODEC);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("realcodeExists")
  void run() throws Exception {
    long start = System.currentTimeMillis();
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new HardCodedKeyChecker());
    checkers.add(new HardCodedPasswordSinkChecker());
    // R-812 audit cleanup (2026-10-14): InsecureCrypto /
    // ObsoleteFunction / ProcessControl / WeakPrng removed;
    // plumb-style handles CWE-327 / CWE-328 / CWE-780 /
    // CWE-477 / CWE-114 / CWE-338 / CWE-336 via AST.
    // checkers.add(new InsecureCryptoChecker());
    // checkers.add(new ObsoleteFunctionChecker());
    // checkers.add(new ProcessControlChecker());
    // R-812: SuspiciousCommentChecker migrated to plumb-style.
    // checkers.add(new SuspiciousCommentChecker());
    // R-812: UncheckedReturnChecker migrated to plumb-style.
    // checkers.add(new UncheckedReturnChecker());
    // checkers.add(new WeakPrngChecker());
    checkers.add(new ArrayIndexChecker());
    checkers.add(new CommandInjectionChecker());
    checkers.add(new DBCredentialSinkChecker());
    checkers.add(new FormatStringChecker());
    checkers.add(new LdapInjectionChecker());
    checkers.add(new NullDerefChecker());
    checkers.add(new NumericTruncationChecker());
    checkers.add(new PathTraversalChecker());
    checkers.add(new SqlInjectionChecker());
    checkers.add(new UncontrolledMemAllocChecker());
    checkers.add(new UnsafeReflectionChecker());
    checkers.add(new XPathInjectionChecker());
    checkers.add(new XssInjectionChecker());
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    StringBuilder sb = new StringBuilder();
    sb.append("R578 — Multi-realcodebase validation (post-R574+R575 fixes)\n");
    String[] corpora = {"commons-lang3", "commons-codec"};
    Path[] roots = {LANG3, CODEC};
    int totalFiles = 0, totalFires = 0;
    for (int i = 0; i < corpora.length; i++) {
      Path root = roots[i];
      List<Path> files = new ArrayList<>();
      try (var stream = Files.walk(root)) {
        stream
            .filter(Files::isRegularFile)
            .filter(p -> p.toString().endsWith(".java"))
            .forEach(files::add);
      }
      Map<String, Integer> ruleCounts = new LinkedHashMap<>();
      Map<String, Integer> cweCounts = new LinkedHashMap<>();
      int corpusFires = 0;
      int filesWithHits = 0;
      for (Path p : files) {
        try {
          ParsedSource src = parser.parse(p);
          TacProgram prog = new TacTranslator(src).translate();
          FindingReporter reporter = engine.run(prog);
          int fileFires = 0;
          for (Finding f : reporter.all()) {
            corpusFires++;
            fileFires++;
            ruleCounts.merge(f.checkerName(), 1, Integer::sum);
            if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
          }
          if (fileFires > 0) filesWithHits++;
        } catch (Exception ignore) {
        }
      }
      sb.append("\n=== ")
          .append(corpora[i])
          .append(" (")
          .append(files.size())
          .append(" files, ")
          .append(corpusFires)
          .append(" fires, ")
          .append(filesWithHits)
          .append(" files with hits) ===\n");
      sb.append("By rule:\n");
      ruleCounts.entrySet().stream()
          .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
          .forEach(
              e ->
                  sb.append("  ")
                      .append(String.format("%4d", e.getValue()))
                      .append("  ")
                      .append(e.getKey())
                      .append('\n'));
      sb.append("By CWE label:\n");
      cweCounts.entrySet().stream()
          .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
          .forEach(
              e ->
                  sb.append("  ")
                      .append(String.format("%4d", e.getValue()))
                      .append("  ")
                      .append(e.getKey())
                      .append('\n'));
      totalFiles += files.size();
      totalFires += corpusFires;
    }
    sb.append("\n=== TOTAL: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(System.currentTimeMillis() - start)
        .append("ms ===\n");
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
