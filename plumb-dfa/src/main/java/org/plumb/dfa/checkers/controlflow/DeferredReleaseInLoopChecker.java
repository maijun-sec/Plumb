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
package org.plumb.dfa.checkers.controlflow;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * A release that a loop defers to the end of the function (CWE-772).
 *
 * <p><b>The bug.</b> A {@code defer} runs the call when the function returns, not where it is
 * written. Inside a loop that runs N times, the release is deferred N times over: the resource
 * stays held until the function exits, and the loop holds N of them at once.
 *
 * <pre>
 *   for _, name := range names {
 *     f, err := os.Open(name)
 *     if err != nil {
 *       return err
 *     }
 *     defer f.Close()   // FLAW: the file stays open until the function returns
 *     process(f)
 *   }
 * </pre>
 *
 * <p>The same shape with a mutex is worse than a leak:
 *
 * <pre>
 *   for _, item := range items {
 *     mu.Lock()
 *     defer mu.Unlock()   // FLAW: the lock is held until the function returns
 *     handle(item)
 *   }
 * </pre>
 *
 * <p>The second iteration waits for a lock the first iteration still holds.
 *
 * <p><b>Why the rule is about releases and not about defers.</b> A defer of a pure computation
 * postpones nothing that matters: the value is still produced, just later. A defer of a RELEASE
 * postpones the moment the resource stops being held. That is the difference between an
 * inconvenience and a defect, so the rule fires on releases.
 *
 * <p><b>Why the release is recognised by name.</b> The engine knows what a call does only when it
 * has analysed the callee, and a deferred callee is by definition not analysed at this point -- it
 * runs after this function returns. The convention is the other evidence available: every language
 * that has a release spells it the same way, and the rule looks for that spelling.
 *
 * <p><b>What it does not claim.</b> The rule does not claim the loop runs more than once. It cannot
 * know that: the bound may be a value read from a file, and the function may be called with an
 * empty list. A loop that runs once is harmless, and the report says the defer is DEFERRED rather
 * than claiming a count.
 *
 * <p><b>How it finds the loop.</b> From the engine's own control-flow walk, the same one the
 * analysis order uses, so "inside a loop" means the same thing here as it does there.
 */
public final class DeferredReleaseInLoopChecker implements DfaChecker {

  private static final String RULE_ID = "dfa-controlflow-deferred-release-in-loop";

  /**
   * The method names that release something.
   *
   * <p>The last segment of the callee signature, matched case-sensitively against the spelling each
   * language uses. A deferred {@code close} in a loop holds the resource; a deferred {@code print}
   * holds nothing.
   */
  private static final Set<String> RELEASE_METHODS =
      Set.of("Close", "Unlock", "Done", "Release", "Stop", "Cancel", "Free", "Finish", "Dispose");

  /** The loop-body indexes of the function being analysed; computed only when a defer is seen. */
  private Set<Integer> loopIndexes;

  /** Whether the loop body has been computed for the current function. */
  private boolean loopIndexesComputed;

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "Cwe772DeferredReleaseInLoop";
  }

  @Override
  public void initEngine(AnalysisContext ctx) {
    reported.clear();
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    loopIndexes = null;
    loopIndexesComputed = false;
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // The marker is the whole test: an instruction that is not a deferred call cannot be this
    // defect, and the marker is set by the frontend that knows the language's own primitive.
    if (!ins.isDeferred()) return;
    if (ins.kind != TacInstruction.Kind.CALL) return;
    if (!isRelease(ins.calleeSig)) return;
    if (!inLoop(ctx)) return;
    String key = fn.signature() + "|" + ins.line + "|" + ins.column + "|" + ins.calleeSig;
    if (!reported.add(key)) return;
    PatternTrace.report(
        ctx,
        fn,
        ins,
        TraceNode.Op.DEFERRED_IN_LOOP,
        ins.calleeSig,
        name(),
        "plumb." + RULE_ID,
        "Function '"
            + fn.name()
            + "' defers '"
            + ins.calleeSig
            + "' inside a loop. The release runs when the function returns, not at the end of the"
            + " iteration, so every iteration holds the resource until the function exits"
            + " (CWE-772).",
        RuleDef.Severity.MEDIUM,
        "CWE-772",
        "deferred-release-in-loop: " + ins.calleeSig + " is deferred inside a loop");
  }

  /**
   * Whether the instruction sits inside a loop.
   *
   * <p>The loop body is computed on demand: a function with no deferred call never pays for the
   * control-flow walk, and a function with one pays for it once.
   */
  private boolean inLoop(AnalysisContext ctx) {
    if (!loopIndexesComputed) {
      loopIndexes = DfaEngine.loopInstructionIndexes(ctx.currentFunction());
      loopIndexesComputed = true;
    }
    if (loopIndexes.isEmpty()) return false;
    return loopIndexes.contains(ctx.currentInstructionIndex());
  }

  /**
   * Whether the callee releases something.
   *
   * <p>The method name is the last segment of the signature: {@code os.File.Close/0} ends in {@code
   * Close/0}, so the name is matched without the arity.
   */
  private static boolean isRelease(String calleeSig) {
    if (calleeSig == null) return false;
    int slash = calleeSig.lastIndexOf('/');
    String withArity = slash >= 0 ? calleeSig.substring(0, slash) : calleeSig;
    int dot = withArity.lastIndexOf('.');
    String method = dot >= 0 ? withArity.substring(dot + 1) : withArity;
    return RELEASE_METHODS.contains(method);
  }

  @Override
  public org.plumb.rules.RuleDef ruleDef() {
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId(RULE_ID, "DEFERRED_RELEASE_IN_LOOP"),
        "A release deferred inside a loop runs when the function returns, so every iteration holds"
            + " the resource until the function exits",
        RuleDef.Severity.MEDIUM,
        RuleDef.Type.RESOURCE,
        java.util.List.of("CWE-772"),
        getClass().getName(),
        true,
        java.util.List.of());
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }
}
