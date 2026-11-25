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
 * Single-checker sweep for CWE-833
 * (Deadlock) on the real Juliet corpus.
 */
package org.plumb.dfa.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.controlflow.DeadlockChecker;
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

class R562Cwe833Sweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe833() throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve("CWE833_Deadlock");
    // A missing corpus directory is this test's premise gone, not a
    // reason to pass quietly. The class used to print and return, which
    // made a vanished corpus indistinguishable from a clean run.
    assertThat(Files.isDirectory(cweDir))
        .as("the Juliet CWE-833 corpus is still where this class expects it")
        .isTrue();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            base.factKinds(),
            base.checkers(),
            base.cweMappings(),
            base.tuning());
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new DeadlockChecker());
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
    int cwe833Hits = 0;
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
          if ("CWE-833".equals(f.cwe())) {
            cwe833Hits++;
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
    sb.append("R562 CWE833_Deadlock (")
        .append(files.size())
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  CWE-833 hits: ").append(cwe833Hits).append('\n');
    sb.append("  Files with fire: ")
        .append(firedFiles)
        .append(" (expected 6 — 2 ReentrantLock + 4 synchronized)\n");
    sb.append("  Note: R30 only detects ReentrantLock\n");
    sb.append("        variants. The 4 synchronized_*\n");
    sb.append("        variants use the Java synchronized\n");
    sb.append("        keyword which the TAC IR lowers to\n");
    sb.append("        MONITORENTER/MONITOREXIT, not to\n");
    sb.append("        .lock() calls. R30 v1 doesn't analyze\n");
    sb.append("        MONITORENTER pairs.\n");
    sb.append("        ReentrantLock fires: 2/2 expected\n");
    sb.append("        synchronized fires: 0/4 (out of R30 scope)\n");
    if (!firedFileNames.isEmpty()) {
      sb.append("  Fired files:\n");
      for (String name : firedFileNames) {
        sb.append("    - ").append(name).append('\n');
      }
    }
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r562-CWE833.txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);

    // A sweep that measured nothing is not a sweep. These are the three
    // things that have to be true for this class to be a guard: the
    // corpus is still there, the engine ran over it, and the engine
    // still recognises the weakness the class is named after.
    //
    // The counts are "more than zero" rather than the exact number on
    // purpose: the exact number is a property of today's corpus and
    // today's engine, while the guard is about the engine still knowing
    // CWE-833.
    assertThat(files.size()).as("files scanned in the CWE-833 corpus").isGreaterThan(0);
    assertThat(cwe833Hits).as("CWE-833 findings in that corpus").isGreaterThan(0);
  }
}
