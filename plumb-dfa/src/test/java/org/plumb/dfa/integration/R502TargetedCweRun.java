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
import org.plumb.dfa.checkers.taint.ArrayIndexChecker;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.DivByZeroChecker;
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.HttpResponseSplittingChecker;
import org.plumb.dfa.checkers.taint.IntOverflowChecker;
import org.plumb.dfa.checkers.taint.IntUnderflowChecker;
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
import org.plumb.dfa.checkers.taint.NullDerefChecker;
import org.plumb.dfa.checkers.taint.OpenRedirectChecker;
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.checkers.taint.UncontrolledMemAllocChecker;
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

/**
 * Targeted single-CWE run for fast iteration on sink/source/coverage bugs. Runs the full 10-checker
 * stack on a single Juliet CWE directory, prints per-rule-id finding counts plus a small sample of
 * unique sink-callee signatures actually encountered.
 */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R502TargetedCweRun {

  private static final Path JULIET_ROOT = Paths.get("D:/work/tmp/Juliet_Java/src/testcases");

  private static boolean julietRootExists() {
    return Files.isDirectory(JULIET_ROOT);
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe89SqlInjection() throws Exception {
    runOneCwe("CWE89_SQL_Injection");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe78CommandInjection() throws Exception {
    runOneCwe("CWE78_OS_Command_Injection");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe134FormatString() throws Exception {
    runOneCwe("CWE134_Uncontrolled_Format_String");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe113HttpResponseSplitting() throws Exception {
    runOneCwe("CWE113_HTTP_Response_Splitting");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe90LdapInjection() throws Exception {
    runOneCwe("CWE90_LDAP_Injection");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe643XpathInjection() throws Exception {
    runOneCwe("CWE643_Xpath_Injection");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe80XssReflected() throws Exception {
    runOneCwe("CWE80_XSS");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe81XssErrorMsg() throws Exception {
    runOneCwe("CWE81_XSS_Error_Message");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe83XssAttr() throws Exception {
    runOneCwe("CWE83_XSS_Attribute");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe23PathTraversal() throws Exception {
    runOneCwe("CWE23_Relative_Path_Traversal");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe36AbsPathTraversal() throws Exception {
    runOneCwe("CWE36_Absolute_Path_Traversal");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe601OpenRedirect() throws Exception {
    runOneCwe("CWE601_Open_Redirect");
  }

  @Test
  @org.junit.jupiter.api.Timeout(value = 5, unit = TimeUnit.MINUTES)
  @EnabledIf("julietRootExists")
  void runCwe789MemAlloc() throws Exception {
    runOneCwe("CWE789_Uncontrolled_Mem_Alloc");
  }

  private void runOneCwe(String cweName) throws Exception {
    long start = System.currentTimeMillis();
    Path cweDir = JULIET_ROOT.resolve(cweName);
    if (!Files.isDirectory(cweDir)) {
      System.out.println("R502 " + cweName + ": dir not found");
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
    checkers.add(new SqlInjectionChecker());
    checkers.add(new CommandInjectionChecker());
    checkers.add(new PathTraversalChecker());
    checkers.add(new LdapInjectionChecker());
    checkers.add(new XssInjectionChecker());
    checkers.add(new XPathInjectionChecker());
    checkers.add(new HttpResponseSplittingChecker());
    checkers.add(new FormatStringChecker());
    checkers.add(new UncontrolledMemAllocChecker());
    checkers.add(new OpenRedirectChecker());
    checkers.add(new IntOverflowChecker());
    checkers.add(new IntUnderflowChecker());
    checkers.add(new DivByZeroChecker());
    checkers.add(new ArrayIndexChecker());
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
          .forEach(files::add);
    }

    // Per-rule-id counts
    java.util.Map<String, Integer> ruleCounts = new java.util.LinkedHashMap<>();
    // Per-cwe-id counts
    java.util.Map<String, Integer> cweCounts = new java.util.LinkedHashMap<>();
    // Sample of unique sink callee signatures
    java.util.Set<String> sinkSigs = new java.util.TreeSet<>();
    // Per-rule sample messages
    java.util.Map<String, String> sampleMsg = new java.util.LinkedHashMap<>();
    int fileCount = 0;
    int findCount = 0;
    int expectedCwe = 0;
    String expectedCweStr = extractCweNumber(cweName);

    for (Path p : files) {
      fileCount++;
      try {
        ParsedSource src = parser.parse(p);
        TacProgram prog = new TacTranslator(src).translate();
        FindingReporter reporter = engine.run(prog);
        for (Finding f : reporter.all()) {
          findCount++;
          String ruleKey = f.checkerName();
          ruleCounts.merge(ruleKey, 1, Integer::sum);
          if (f.cwe() != null) cweCounts.merge(f.cwe(), 1, Integer::sum);
          if (expectedCweStr != null && expectedCweStr.equals(f.cwe())) expectedCwe++;
          if (sampleMsg.size() < 10 && !sampleMsg.containsKey(ruleKey)) {
            sampleMsg.put(ruleKey, f.message() == null ? "" : f.message().replace('\n', ' '));
          }
        }
      } catch (Exception ignore) {
        // skip parse failures
      }
    }
    long elapsed = System.currentTimeMillis() - start;
    StringBuilder sb = new StringBuilder();
    sb.append("R502 ")
        .append(cweName)
        .append(" (")
        .append(fileCount)
        .append(" files, ")
        .append(findCount)
        .append(" findings, ")
        .append(elapsed)
        .append("ms)\n");
    sb.append("  expectedCwe=")
        .append(expectedCweStr)
        .append(" -> ")
        .append(expectedCwe)
        .append(" hits\n");
    sb.append("  by rule:\n");
    ruleCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    sb.append("  by cwe:\n");
    cweCounts.forEach((k, v) -> sb.append("    ").append(k).append(" : ").append(v).append('\n'));
    if (!sampleMsg.isEmpty()) {
      sb.append("  sample messages:\n");
      sampleMsg.forEach(
          (k, v) ->
              sb.append("    [")
                  .append(k)
                  .append("] ")
                  .append(v.length() > 80 ? v.substring(0, 80) : v)
                  .append('\n'));
    }
    Path out = Paths.get("D:/work/tmp/r500_juliet_sweep/r502-" + cweName + ".txt");
    Files.createDirectories(out.getParent());
    Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
    System.out.println(sb);
    System.out.println("R502 " + cweName + " done -> " + out);
  }

  private static String extractCweNumber(String dirName) {
    if (dirName.startsWith("CWE")) {
      int i = 3;
      while (i < dirName.length() && Character.isDigit(dirName.charAt(i))) i++;
      if (i > 3) return "CWE-" + dirName.substring(3, i);
    }
    return null;
  }
}
