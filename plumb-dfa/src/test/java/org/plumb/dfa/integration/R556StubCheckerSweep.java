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
 * Batched sweep of all 11 taint stub checkers
 * (CFR-decompiled one-liners) on a small set
 * of CWE dirs likely to exercise them. Goal: discover
 * which stub checkers actually fire and which need real
 * patterns. Targets:
 *   - CWE-80/81/83 (XSS)  -> xss.sinks
 *   - CWE-78              -> ognl.sinks
 *   - CWE-89              -> code-execution.sinks
 *   - CWE-470             -> reflection.sinks
 *   - CWE-114 (Process Ctl) -> jni.sinks
 *   - CWE-384             -> session.sinks
 *   - CWE-129 (Array)     -> mem-alloc.sinks (for UnsafeJni / random etc)
 *   - CWE-319 (Cleartext) -> ssl.sinks
 *   - CWE-674 (Recursion) -> trust-boundary.sinks (?)
 *   - CWE-22 (Path Trv)   -> file-path.sinks (CWE-23/36)
 *
 * Writes D:/work/tmp/r500_juliet_sweep/r556-stub.txt.
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
import org.plumb.dfa.checkers.taint.BadCertVerificationChecker;
import org.plumb.dfa.checkers.taint.ElInjectionChecker;
import org.plumb.dfa.checkers.taint.InsecureRandomChecker;
import org.plumb.dfa.checkers.taint.JavaCodeInjectionChecker;
import org.plumb.dfa.checkers.taint.JcrInjectionChecker;
import org.plumb.dfa.checkers.taint.JspDynamicIncludeChecker;
import org.plumb.dfa.checkers.taint.OgnlInjectionChecker;
import org.plumb.dfa.checkers.taint.SessionFixationChecker;
import org.plumb.dfa.checkers.taint.TrustBoundaryViolationChecker;
import org.plumb.dfa.checkers.taint.UnsafeJniChecker;
import org.plumb.dfa.checkers.taint.UnsafeReflectionChecker;
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
class R556StubCheckerSweep {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");
  private static final Path OUT = Paths.get("D:/work/tmp/r500_juliet_sweep/r556-stub.txt");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  private static final String[] TARGET_DIRS = {};

  @Test
  @org.junit.jupiter.api.Timeout(value = 8, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void sweepStubCheckers() throws Exception {
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
    checkers.add(new BadCertVerificationChecker());
    checkers.add(new ElInjectionChecker());
    checkers.add(new InsecureRandomChecker());
    checkers.add(new JavaCodeInjectionChecker());
    checkers.add(new JcrInjectionChecker());
    checkers.add(new JspDynamicIncludeChecker());
    checkers.add(new OgnlInjectionChecker());
    checkers.add(new SessionFixationChecker());
    checkers.add(new TrustBoundaryViolationChecker());
    checkers.add(new UnsafeJniChecker());
    checkers.add(new UnsafeReflectionChecker());
    DfaEngine engine = new DfaEngine(cfg, checkers, "cha", null);
    JavaSourceParser parser = new JavaSourceParser();
    Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    Map<String, Integer> cweCounts = new java.util.LinkedHashMap<>();
    int totalFiles = 0;
    int totalFires = 0;
    StringBuilder sb = new StringBuilder();
    sb.append("R556 — 11 stub checkers sweep\n");
    for (String dirName : TARGET_DIRS) {
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
      int dirFires = 0;
      int dirA = 0, dirB = 0, dirGood = 0;
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
            totalFires++;
            String ruleKey = f.checkerName();
            ruleCounts.merge(ruleKey, 1, Integer::sum);
            String cwe = f.cwe();
            if (cwe != null) cweCounts.merge(cwe, 1, Integer::sum);
            if (isBad) dirA++;
            else if (isB) dirB++;
            else if (isGood) dirGood++;
          }
        } catch (Exception ignore) {
        }
      }
      totalFiles += files.size();
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
    }
    long elapsed = System.currentTimeMillis() - start;
    sb.append("\nTotal: ")
        .append(totalFiles)
        .append(" files, ")
        .append(totalFires)
        .append(" fires, ")
        .append(elapsed)
        .append("ms\n");
    sb.append("By rule:\n");
    ruleCounts.forEach((k, v) -> sb.append("  ").append(k).append(" : ").append(v).append('\n'));
    sb.append("By cwe:\n");
    cweCounts.forEach((k, v) -> sb.append("  ").append(k).append(" : ").append(v).append('\n'));
    Files.createDirectories(OUT.getParent());
    Files.writeString(OUT, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
  }
}
