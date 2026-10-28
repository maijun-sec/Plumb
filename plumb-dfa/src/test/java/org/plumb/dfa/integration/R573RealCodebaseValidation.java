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
 * Real-codebase validation on Apache Commons Lang3.
 *
 * Real-world Java code from a popular open-source library
 * (246 files / ~70K lines). Goal: run the full Plumb DFA
 * checkers and see what fires. Unlike the previous synthetic run,
 * these are production-quality files with no planted bugs.
 *
 * Expected:
 *   - Few fires (production code, no obvious vulns)
 *   - Some fires for "insecure" patterns that are
 *     technically valid in a library (e.g. Object.equals
 *     comparison, System.out)
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r573-realcode.txt.
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
class R573RealCodebaseValidation {

  private static final Path REALCODE = Paths.get("D:/work/tmp/realcode/junit-src/lang3");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r573-realcode.txt");

  private static boolean realcodeExists() {
    return Files.isDirectory(REALCODE);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("realcodeExists")
  void run() throws Exception {
    long start = System.currentTimeMillis();
    List<DfaChecker> checkers = new ArrayList<>();
    // 24 representative checkers
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
    // Collect files
    List<Path> files = new ArrayList<>();
    try (var stream = Files.walk(REALCODE)) {
      stream
          .filter(Files::isRegularFile)
          .filter(p -> p.toString().endsWith(".java"))
          .forEach(files::add);
    }
    StringBuilder sb = new StringBuilder();
    sb.append("R573 — Real-codebase validation: Apache Commons Lang3 ")
        .append(files.size())
        .append(" files\n");
    Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    Map<String, Integer> cweCounts = new java.util.LinkedHashMap<>();
    Map<String, Integer> fileHitMap = new java.util.LinkedHashMap<>();
    int totalFires = 0;
    int filesWithHits = 0;
    long parseNs = 0, translateNs = 0, runNs = 0;
    for (Path p : files) {
      try {
        long t0 = System.nanoTime();
        ParsedSource src = parser.parse(p);
        long t1 = System.nanoTime();
        parseNs += t1 - t0;
        TacProgram prog = new TacTranslator(src).translate();
        long t2 = System.nanoTime();
        translateNs += t2 - t1;
        FindingReporter reporter = engine.run(prog);
        long t3 = System.nanoTime();
        runNs += t3 - t2;
        int fileFires = 0;
        for (Finding f : reporter.all()) {
          totalFires++;
          fileFires++;
          ruleCounts.merge(f.checkerName(), 1, Integer::sum);
          if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
        }
        if (fileFires > 0) {
          filesWithHits++;
          fileHitMap.put(p.getFileName().toString(), fileFires);
        }
      } catch (Exception ignore) {
      }
    }
    sb.append("\nTotal fires: ")
        .append(totalFires)
        .append(" in ")
        .append(filesWithHits)
        .append(" / ")
        .append(files.size())
        .append(" files\n");
    sb.append("Time: parse=")
        .append(parseNs / 1_000_000)
        .append("ms translate=")
        .append(translateNs / 1_000_000)
        .append("ms run=")
        .append(runNs / 1_000_000)
        .append("ms total=")
        .append((System.currentTimeMillis() - start))
        .append("ms\n");
    sb.append("\nBy rule (sorted by fire count):\n");
    ruleCounts.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .forEach(
            e ->
                sb.append("  ")
                    .append(String.format("%4d", e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n'));
    sb.append("\nBy CWE label:\n");
    cweCounts.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .forEach(
            e ->
                sb.append("  ")
                    .append(String.format("%4d", e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n'));
    sb.append("\nTop fire files (file: fires):\n");
    fileHitMap.entrySet().stream()
        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
        .limit(15)
        .forEach(
            e ->
                sb.append("  ")
                    .append(String.format("%4d", e.getValue()))
                    .append("  ")
                    .append(e.getKey())
                    .append('\n'));
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
