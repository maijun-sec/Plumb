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
package org.plumb.dfa.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.plumb.dfa.cg.CallGraphBuilder;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.semantics.LanguageSemantics;
import org.plumb.reporter.ModelCoverage;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * Counts how much of the analysed code the engine actually modelled.
 *
 * <h2>Why this is computed by walking the IR, and not by watching the analysis</h2>
 *
 * <p>The obvious way to count is to count inside the engine's own walk: every time the engine meets
 * a call it cannot explain, increment a counter. That count is wrong, and wrong in the direction
 * that flatters the tool. The engine's walk is a DATA-FLOW walk: it revisits instructions when the
 * data changes, it skips instructions on paths it prunes, and it never visits the instructions of a
 * function the budget cut short. A counter fed by that walk measures "the calls the data flow
 * happened to reach", which is not the same question as "the calls the scan looked at".
 *
 * <p>So this walks the IR directly. Every instruction of every analysed function is visited exactly
 * once, and the numbers are a property of the analysed programs rather than of the traversal.
 *
 * <h2>What counts as a call site</h2>
 *
 * <p>A CALL and an UNINTERPRETED both count. The difference between them is where the "I cannot see
 * this" came from -- the engine's caller-side resolution or the front end -- but a reader of the
 * report does not care which: in both cases the call's effect on the data had to be assumed rather
 * than derived, and that is the question the ratio answers.
 *
 * <p>A NEW does NOT count. A constructor's effect on taint is decided by the argument rules and by
 * the sink catalogue, not by a body the engine summarises, and the IR publishes a constructor under
 * a different arity than the call site names it by -- so counting it would report constructors as
 * unmodelled even when their class was in the scanned set.
 *
 * <h2>Why the call resolution is the engine's own</h2>
 *
 * <p>"Does this call have a body?" is answered by {@link CallGraphBuilder}'s resolution, not by a
 * second implementation of it. A call names its callee in one spelling ("buildQuery/1",
 * "demo.JavaSink.run/1", "App\\Services\\Mailer::send") and the declaration is registered under
 * another ("buildQuery(String):String"), and the rules that connect them -- bare name, owner by
 * simple name, the Scala module-class spelling, overload disambiguation on the argument types --
 * are four rules that have to agree with each other or the coverage number describes a different
 * program from the one the engine analysed. The engine already has one implementation of those
 * rules, and this uses it.
 */
public final class ModelCoverageComputer {

  private ModelCoverageComputer() {}

  /**
   * Compute the coverage of a set of analysed programs.
   *
   * @param programs the programs the scan analysed; null or empty means "nothing was analysed"
   * @param config the engine configuration, for the semantics and passthrough tables; null means
   *     "the engine had no tables", which makes every call unmodelled unless it had a body
   * @return the coverage
   */
  public static ModelCoverage compute(List<TacProgram> programs, EngineConfig config) {
    if (programs == null || programs.isEmpty()) {
      return ModelCoverage.nothingAnalysed();
    }

    // The declarations, indexed the way the call graph indexes them. Two indexes, because the
    // engine needs two: one keyed by bare name and arity (a call that names no class), and one
    // keyed by the owner qualified by SIMPLE NAME (a call that names its class, possibly from
    // another language).
    Map<String, List<String>> bareNameToCanonical = new HashMap<>();
    Map<String, List<String>> ownerKeyToCanonical = new HashMap<>();
    Set<String> declared = new HashSet<>();
    for (TacProgram program : programs) {
      if (program == null) continue;
      for (TacFunction fn : program.functions()) {
        declared.add(fn.signature());
        String bare = CallGraphBuilder.bareNameAndArity(fn.signature());
        if (bare != null) {
          bareNameToCanonical.computeIfAbsent(bare, k -> new ArrayList<>()).add(fn.signature());
        }
        for (String key : CallGraphBuilder.ownerKeysOf(fn)) {
          ownerKeyToCanonical.computeIfAbsent(key, k -> new ArrayList<>()).add(fn.signature());
        }
      }
    }

    int functions = 0;
    int functionsAtFullPrecision = 0;
    int declaredProblems = 0;
    int callSites = 0;
    int withBody = 0;
    int withSemantics = 0;
    int withPassthrough = 0;
    int unmodelled = 0;
    int instructions = 0;
    int havoc = 0;
    int uninterpreted = 0;

    // The drops, grouped by the construct id.  The two scopes are kept apart: a function-level
    // drop already moved the precision count, while a program-level drop moved nothing and would
    // be invisible if the two were mixed into one pile.
    Map<String, Integer> drops = new TreeMap<>();
    Map<String, Integer> programDrops = new TreeMap<>();

    for (TacProgram program : programs) {
      if (program == null) continue;
      declaredProblems += program.programProblems().size();
      for (org.plumb.tac.ir.TacDrop drop : program.programDrops()) {
        programDrops.merge(drop.construct(), 1, Integer::sum);
      }
      for (TacFunction fn : program.functions()) {
        functions++;
        if (fn.irPrecision() == IrPrecision.SOURCE_FULL) {
          functionsAtFullPrecision++;
        }
        for (org.plumb.tac.ir.TacDrop drop : fn.drops()) {
          drops.merge(drop.construct(), 1, Integer::sum);
        }
        LanguageSemantics semantics = semanticsFor(config, fn.sourceLanguage());
        for (TacInstruction ins : fn.instructions()) {
          instructions++;
          if (ins.kind == TacInstruction.Kind.HAVOC) {
            havoc++;
          } else if (ins.kind == TacInstruction.Kind.UNINTERPRETED) {
            uninterpreted++;
          }
          if (ins.kind != TacInstruction.Kind.CALL
              && ins.kind != TacInstruction.Kind.UNINTERPRETED) {
            continue;
          }
          callSites++;
          String calleeSig = ins.calleeSig;
          if (hasBody(calleeSig, ins.args, declared, bareNameToCanonical, ownerKeyToCanonical)) {
            withBody++;
          } else if (semantics != null
              && semantics.behaviourOf(calleeSig) != LanguageSemantics.TaintBehaviour.UNKNOWN) {
            withSemantics++;
          } else if (hasPassthrough(config, calleeSig, fn.sourceLanguage())) {
            withPassthrough++;
          } else {
            unmodelled++;
          }
        }
      }
    }

    return new ModelCoverage(
        functions,
        functionsAtFullPrecision,
        declaredProblems,
        callSites,
        withBody,
        withSemantics,
        withPassthrough,
        unmodelled,
        instructions,
        havoc,
        uninterpreted,
        new ModelCoverage.DropCounts(drops, programDrops));
  }

  /**
   * Whether the call resolves to a function the scan analysed.
   *
   * @param calleeSig the call's callee signature
   * @param args the call's arguments, for overload disambiguation
   * @param declared the signatures the analysed programs declare
   * @param bareNameToCanonical the bare-name index
   * @param ownerKeyToCanonical the owner index
   * @return true when the callee has a body in the analysed set
   */
  private static boolean hasBody(
      String calleeSig,
      List<org.plumb.tac.ir.TacOperand> args,
      Set<String> declared,
      Map<String, List<String>> bareNameToCanonical,
      Map<String, List<String>> ownerKeyToCanonical) {
    if (calleeSig == null || calleeSig.isEmpty()) {
      return false;
    }
    String resolved =
        CallGraphBuilder.r48ResolveCalleeSigCore(
            declared::contains, calleeSig, bareNameToCanonical, ownerKeyToCanonical, args);
    return declared.contains(resolved);
  }

  /**
   * Whether one of the passthrough tables names the callee.
   *
   * @param config the engine configuration
   * @param calleeSig the call's callee signature
   * @param language the language of the calling function
   * @return true when a passthrough table knows this callee
   */
  private static boolean hasPassthrough(
      EngineConfig config, String calleeSig, SourceLanguage language) {
    if (config == null || calleeSig == null || calleeSig.isEmpty()) {
      return false;
    }
    if (config.isReceiverPassthroughEligible(calleeSig, language)) {
      return true;
    }
    return !config.argumentPassthrough().argIndexesFor(calleeSig, language).isEmpty();
  }

  /**
   * The semantics table of the language the function was written in.
   *
   * @param config the engine configuration
   * @param language the language of the function
   * @return the table, or null when there is none
   */
  private static LanguageSemantics semanticsFor(EngineConfig config, SourceLanguage language) {
    if (config == null || language == null) {
      return null;
    }
    return config.languageSemantics().forLanguage(language);
  }
}
