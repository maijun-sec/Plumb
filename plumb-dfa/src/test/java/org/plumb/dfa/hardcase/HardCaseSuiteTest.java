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
package org.plumb.dfa.hardcase;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * The hard-case set: the engine's verdict on the programs it is supposed to judge.
 *
 * <h2>What this is for</h2>
 *
 * <p>Every other guard in this repository asks whether ONE mechanism still behaves. This one asks a
 * different question, and it is the question the rest of the work is measured against: <b>does the
 * engine get these programs right?</b>
 *
 * <p>The cases are small programs, each with a verdict the program deserves. A program that
 * overwrites the tainted value with a constant before the sink deserves no finding; a program that
 * does not deserves one. The set spans the three axes the work is organised around:
 *
 * <ul>
 *   <li><b>flow</b> — the sensitivity of the taint to assignment and to the join of branches;
 *   <li><b>points-to</b> — the taint that travels through a field, across a call boundary;
 *   <li><b>constraint</b> — the path conditions that decide whether a path exists at all.
 * </ul>
 *
 * <h2>How a case is judged</h2>
 *
 * <p>Each case is run with <b>only</b> the checker it is about, and only the findings of that
 * checker's rule are counted. A case is a claim about one rule, so a finding from another rule says
 * nothing about it — and loading every checker at once lets one checker's finding stand in for
 * another's verdict, which is how a fixture's own noise gets read as a result.
 *
 * <h2>Why the score has a floor and not just an assertion</h2>
 *
 * <p>The set contains cases the engine gets right today and cases it gets wrong today. A test that
 * asserted every case would be red from the start and would say nothing about whether the work is
 * moving. So the set is a ratchet:
 *
 * <ul>
 *   <li>a case marked <b>closed</b> must keep passing — a regression is a failure;
 *   <li>the total score must never fall below {@link #FLOOR} — a fix cannot be paid for with a
 *       regression elsewhere.
 * </ul>
 *
 * <p>When a case is fixed, its status moves from {@code open} to {@code closed} and the floor goes
 * up. The score is printed on every run, so the direction of travel is visible without reading the
 * diff.
 */
@DisplayName("The hard-case set: the engine's verdict on the programs it is supposed to judge")
class HardCaseSuiteTest {

  /**
   * The score the engine must not fall below.
   *
   * <p>It is the number of cases the engine gets right today. It only ever goes up.
   *
   * <p><b>Where the number came from.</b> 14 of the 15 cases, measured on the tree this change was
   * made on: the three assignment cases ({@code fs-reassign-const}, {@code fs-param-reassign},
   * {@code fs-field-reassign}) became passable when an assignment stopped keeping a taint it had
   * already overwritten, and {@code fs-branch-both-clean} became passable when the join of two arms
   * that both overwrite the value became clean. The one that still fails is {@code
   * pt-field-overwritten-cross-method}, which is a points-to case.
   */
  private static final int FLOOR = 14;

  private static final String MANIFEST = "/hardcases/manifest.tsv";

  /**
   * One case: the program, the rule it is about, and the verdict it deserves.
   *
   * <p>{@code config} is the name of a YAML file next to the program, or empty. A case that needs
   * the project's own configuration -- a sanitizer the project declares, say -- carries it, because
   * a case judged under configuration it does not have would measure the wrong thing.
   */
  private record Case(
      String id,
      String checker,
      String rule,
      String expect,
      String family,
      String status,
      String config) {}

  @TempDir Path tmp;

  /**
   * Every case the engine gets right today still gets right, and the score has not fallen.
   *
   * <p>This is the assertion that keeps the work honest: a case that is {@code closed} and starts
   * failing is a regression, and a score below the floor means the same thing spread over several
   * cases.
   */
  @Test
  @DisplayName("every closed case still passes, and the score has not fallen")
  void everyClosedCaseStillPassesAndTheScoreHasNotFallen() throws Exception {
    List<Case> cases = manifest();
    assertThat(cases).as("the manifest lists the cases").isNotEmpty();

    int score = 0;
    List<String> regressions = new ArrayList<>();
    List<String> openFailures = new ArrayList<>();
    for (Case c : cases) {
      int count = findingsFor(c);
      boolean ok = ("FINDING".equals(c.expect()) == (count > 0));
      if (ok) {
        score++;
      } else {
        String detail =
            c.id() + ": expected " + c.expect() + ", got " + (count > 0 ? "FINDING" : "NO_FINDING");
        if ("closed".equals(c.status())) {
          regressions.add(detail);
        } else {
          openFailures.add(detail);
        }
      }
    }

    System.out.println(
        "HARD-CASES\t"
            + score
            + "/"
            + cases.size()
            + "\topen still failing: "
            + openFailures.size());

    assertThat(regressions)
        .as(
            "these cases are closed -- the engine gets them right today, and a case that starts "
                + "failing is a regression")
        .isEmpty();
    assertThat(score)
        .as("the hard-case score must not fall below the floor")
        .isGreaterThanOrEqualTo(FLOOR);
  }

  /**
   * The manifest is readable and every case has a program.
   *
   * <p>A case whose program is missing would be judged "no finding" and would silently look like a
   * fix — a fixture that measures nothing looks exactly like a case that passes.
   */
  @Test
  @DisplayName("every case in the manifest has its program")
  void everyCaseInTheManifestHasItsProgram() throws Exception {
    for (Case c : manifest()) {
      assertThat(resource("/hardcases/" + c.id() + ".java"))
          .as("the program of case " + c.id())
          .isNotNull();
    }
  }

  // ─── the machinery ───────────────────────────────────────────────────

  private List<Case> manifest() throws Exception {
    String text = resourceText(MANIFEST);
    assertThat(text).as("the manifest is on the classpath").isNotNull();
    List<Case> out = new ArrayList<>();
    for (String raw : text.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty() || line.startsWith("#")) continue;
      String[] cols = line.split("\\s+");
      assertThat(cols.length)
          .as("the manifest line is complete: " + line)
          .isGreaterThanOrEqualTo(6);
      String config = cols.length > 6 ? cols[6] : "";
      out.add(new Case(cols[0], cols[1], cols[2], cols[3], cols[4], cols[5], config));
    }
    return out;
  }

  /** How many findings of the case's own rule the engine produces. */
  private int findingsFor(Case c) throws Exception {
    String source = resourceText("/hardcases/" + c.id() + ".java");
    assertThat(source).as("the program of case " + c.id() + " is on the classpath").isNotNull();
    Path file = tmp.resolve(c.id() + ".java");
    Files.writeString(file, source, StandardCharsets.UTF_8);

    DfaChecker checker = newChecker(c.checker());
    EngineConfig config = configFor(c);
    DfaEngine engine = new DfaEngine(config, List.of(checker), "cha", null);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();
    FindingReporter reporter = engine.run(program);

    int count = 0;
    for (Finding f : reporter.all()) {
      if (c.rule().equals(f.ruleId())) count++;
    }
    return count;
  }

  /**
   * The engine configuration the case is judged under.
   *
   * <p>The defaults, plus whatever the case's own configuration file replaces. The file is copied
   * out of the classpath first, because the loader's override is a PATH -- a resource inside a jar
   * is not one.
   */
  private EngineConfig configFor(Case c) throws Exception {
    if (c.config() == null || c.config().isEmpty()) {
      return ConfigLoader.loadFromClasspath();
    }
    String text = resourceText("/hardcases/" + c.config());
    assertThat(text).as("the configuration of case " + c.id() + " is on the classpath").isNotNull();
    Path file = tmp.resolve(c.id() + ".sanitizers.yaml");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return ConfigLoader.loadFromClasspath(Map.of("sanitizers.yaml", file));
  }

  private DfaChecker newChecker(String simpleName) throws Exception {
    Class<?> type = Class.forName("org.plumb.dfa.checkers.taint." + simpleName);
    Constructor<?> ctor = type.getDeclaredConstructor();
    ctor.setAccessible(true);
    return (DfaChecker) ctor.newInstance();
  }

  private String resourceText(String path) throws Exception {
    try (InputStream in = getClass().getResourceAsStream(path)) {
      if (in == null) return null;
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private InputStream resource(String path) {
    return getClass().getResourceAsStream(path);
  }
}
