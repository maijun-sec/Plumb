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
package org.plumb.main;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.io.IrSerializer;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * The consumer half of the front/back split: <b>IR in, findings out</b>.
 *
 * <p><b>The split this class exists for.</b> A producer prepares the source list, the dependency
 * list and the entity files, translates them to IR with {@link
 * org.plumb.tac.translate.TacTranslator} (or the Kotlin / Scala frontends), and stores the IR. A
 * consumer -- possibly on another machine, with no source, no classpath, and no Java toolchain --
 * loads the IR and scans it.
 *
 * <p>For that to work, the IR has to be a CONTRACT rather than a cache. A cache is only useful next
 * to the thing it caches; a contract is useful on its own. The difference is entirely in what the
 * IR carries, and this class is the proof that it carries enough: it runs the real analysis over IR
 * that was loaded from disk, and it resolves findings back to their source files using ONLY the
 * path information the IR itself holds.
 *
 * <p><b>What the consumer does NOT need.</b>
 *
 * <ul>
 *   <li>the source files -- they may be gone;
 *   <li>a classpath -- the analysis works on the IR's own type information;
 *   <li>a Java toolchain -- no compilation happens here.
 * </ul>
 *
 * <p><b>What the consumer DOES need from the IR.</b> The instructions, the source path map, the
 * class hierarchy, the annotation metadata, and the problem channel. This class reads all of them
 * and refuses to pretend they are there when they are not: if the IR carries no source paths, the
 * findings say {@code filePath=unknown} rather than inventing one, and the result carries the
 * problems so a caller can see that the analysis was incomplete.
 *
 * <p><b>Directional rule.</b> The problem channel is metadata about completeness. It is not a
 * finding, and it never suppresses a finding.
 */
public final class IrAnalysisRunner {

  private final EngineConfig config;
  private final List<DfaChecker> checkers;

  /**
   * A runner with the default engine configuration and the default checker set.
   *
   * <p>The default checker set is the one {@link ScanRunner} uses, minus the checkers that need
   * source-level information a source-less consumer does not have. The DFA checkers all work on the
   * IR alone.
   */
  public IrAnalysisRunner() {
    this(ConfigLoader.loadFromClasspath(), defaultCheckers());
  }

  /**
   * A runner with an explicit configuration and checker set.
   *
   * @param config the engine configuration
   * @param checkers the checkers to run
   */
  public IrAnalysisRunner(EngineConfig config, List<DfaChecker> checkers) {
    this.config = Objects.requireNonNull(config, "config");
    this.checkers = List.copyOf(Objects.requireNonNull(checkers, "checkers"));
  }

  /**
   * The default checker set for a source-less consumer.
   *
   * <p>These are the DFA checkers: they consume {@link TacFunction} and nothing else, so they work
   * on an IR with no source behind it.
   */
  public static List<DfaChecker> defaultCheckers() {
    List<DfaChecker> checkers = new ArrayList<>();
    checkers.add(new org.plumb.dfa.checkers.taint.CommandInjectionChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.SqlInjectionChecker());
    // CWE-566: tainted data reaches a DATABASE, including as a BOUND PARAMETER.
    // A different defect from CWE-89 SQL injection -- see the checker's javadoc.
    checkers.add(new org.plumb.dfa.checkers.taint.DatabaseAccessChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.PathTraversalChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.XssInjectionChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.RegexInjectionChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.FilePermissionChecker());
    checkers.add(new org.plumb.dfa.checkers.taint.NullDerefChecker());
    checkers.add(new org.plumb.dfa.checkers.controlflow.TryCatchFinallyChecker());
    return checkers;
  }

  /**
   * The result of analysing an IR: the findings, plus what the IR said about its own completeness.
   *
   * <p>The problems are carried alongside the findings rather than folded into them, because they
   * are two different kinds of thing. A finding says "this code does X badly". A problem says "I
   * could not fully translate this code, so my findings are a lower bound". A consumer that only
   * sees a list of findings cannot tell a clean program from a program the analyser could not read.
   */
  public static final class IrAnalysisResult {
    private final List<Finding> findings;
    private final List<TacProgram.FunctionProblem> problems;
    private final int analyzedFunctions;

    IrAnalysisResult(
        List<Finding> findings, List<TacProgram.FunctionProblem> problems, int analyzedFunctions) {
      this.findings = List.copyOf(findings);
      this.problems = List.copyOf(problems);
      this.analyzedFunctions = analyzedFunctions;
    }

    /** The findings the analysis produced. */
    public List<Finding> findings() {
      return findings;
    }

    /**
     * The problems the IR carried: the constructs the frontend could not lower and the source
     * problems the parser recovered from.
     *
     * <p>A non-empty list means the findings are a LOWER BOUND on what is really there.
     */
    public List<TacProgram.FunctionProblem> problems() {
      return problems;
    }

    /** How many functions the analysis looked at. */
    public int analyzedFunctions() {
      return analyzedFunctions;
    }

    /** Whether the IR said anything about its own incompleteness. */
    public boolean isComplete() {
      return problems.isEmpty();
    }
  }

  /**
   * Analyse a merged IR file.
   *
   * <p>This is the entry point for a source-less consumer: hand it the path of a {@code .tac} file
   * written by {@link IrSerializer#writeMerged} and it returns findings.
   *
   * @param irFile the merged IR file
   * @return the findings and the IR's problems
   * @throws java.io.IOException if the file cannot be read
   */
  public IrAnalysisResult analyzeMerged(Path irFile) throws java.io.IOException {
    Objects.requireNonNull(irFile, "irFile");
    if (!Files.exists(irFile)) {
      throw new java.io.IOException("IR file does not exist: " + irFile);
    }
    MergedProgram merged = IrSerializer.readMerged(irFile);
    return analyze(merged);
  }

  /**
   * Analyse a single-program IR file.
   *
   * @param irFile the IR file, written by {@link IrSerializer#writeProgram}
   * @return the findings and the IR's problems
   * @throws java.io.IOException if the file cannot be read
   */
  public IrAnalysisResult analyzeProgram(Path irFile) throws java.io.IOException {
    Objects.requireNonNull(irFile, "irFile");
    if (!Files.exists(irFile)) {
      throw new java.io.IOException("IR file does not exist: " + irFile);
    }
    TacProgram program = IrSerializer.readProgram(irFile);
    return analyze(program);
  }

  /**
   * Analyse an in-memory merged program.
   *
   * @param merged the merged IR
   * @return the findings and the IR's problems
   */
  public IrAnalysisResult analyze(MergedProgram merged) {
    Objects.requireNonNull(merged, "merged");
    List<TacProgram> sources = merged.sources();

    // The per-class source path map, built from the IR's OWN source paths.
    //
    // WHY THIS IS BUILT HERE AND NOT PASSED IN. The merged program's synthetic
    // view has a null source path, so the engine's own fallback would leave every
    // finding's filePath as "unknown". The map is derived from the per-program
    // source paths the IR carries -- the IR is the only input, so this works on
    // a machine that has never seen the source.
    Map<String, Path> sourcePaths = new HashMap<>();
    for (TacProgram src : sources) {
      Path path = src.sourcePath();
      if (path == null) continue;
      for (TacFunction fn : src.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          sourcePaths.putIfAbsent(enc, path);
        }
      }
    }

    // NOTE: no `withStaticFieldResolverRoot(...)` here, and there must not be
    // one. Cross-file `public static final` resolution reads .java files from
    // disk; a source-less consumer that did that would be reading source it was
    // explicitly given no access to, and its answer would change depending on
    // what happened to be lying around on the machine. The IR is the contract.
    //
    // The consequence is honest and bounded: a FIELDLOAD of a cross-file
    // constant resolves to the orphan-SOURCE shape instead of the constant's
    // value. That is a loss of precision, not of soundness -- and it is the
    // price of the source-less guarantee, paid knowingly.
    DfaEngine engine =
        new DfaEngine(config, checkers, "global", null).withFunctionSourcePaths(sourcePaths);
    FindingReporter reporter = engine.runWithGlobalStore(sources, 0);

    List<TacProgram.FunctionProblem> problems = new ArrayList<>();
    int analyzed = 0;
    for (TacProgram src : sources) {
      problems.addAll(src.programProblems());
      analyzed += src.functions().size();
    }
    return new IrAnalysisResult(new ArrayList<>(reporter.all()), problems, analyzed);
  }

  /**
   * Analyse a single in-memory program.
   *
   * @param program the IR
   * @return the findings and the IR's problems
   */
  public IrAnalysisResult analyze(TacProgram program) {
    Objects.requireNonNull(program, "program");
    Map<String, Path> sourcePaths = new HashMap<>();
    Path path = program.sourcePath();
    if (path != null) {
      for (TacFunction fn : program.functions()) {
        String enc = fn.enclosingClass();
        if (enc != null && !enc.isEmpty()) {
          sourcePaths.putIfAbsent(enc, path);
        }
      }
    }
    DfaEngine engine =
        new DfaEngine(config, checkers, "global", null).withFunctionSourcePaths(sourcePaths);
    FindingReporter reporter = engine.runWithGlobalStore(List.of(program), 0);

    List<TacProgram.FunctionProblem> problems = new ArrayList<>(program.programProblems());
    return new IrAnalysisResult(
        new ArrayList<>(reporter.all()), problems, program.functions().size());
  }
}
