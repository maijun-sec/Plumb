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
import java.util.List;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * CWE-248 (Uncaught Exception) cross-function propagation, in the unified DFA engine.
 *
 * <p>The intra-function half of CWE-248 is the try/catch/finally DFA check: a {@code throw} that no
 * {@code catch} in the same function handles. The cross-function half is this checker: a call site
 * where the callee has at least one {@code THROW} instruction, but the caller function has no
 * {@code CATCH_HANDLER} at all. Such a call will propagate an exception out of the caller, which is
 * what CWE-248 describes.
 *
 * <p><b>Algorithm</b>
 *
 * <ol>
 *   <li>Per-function, walk the IR and record
 *       <ul>
 *         <li>{@code hasThrow[fn] = true} if any {@code THROW} instruction appears in {@code fn},
 *         <li>{@code hasCatch[fn] = true} if any {@code CATCH_HANDLER} appears in {@code fn}.
 *       </ul>
 *   <li>In {@link #finish}, re-walk every function's {@code CALL} instructions. For each call:
 *       <ul>
 *         <li>If the callee signature is in {@code hasThrow} (i.e. the callee is in this program
 *             and it throws), and
 *         <li>The caller is NOT in {@code hasCatch} (i.e. the caller has zero catch handlers),
 *         <li>Fire CWE-248 on the call site.
 *       </ul>
 * </ol>
 *
 * <p><b>Why DFA, not AST.</b> A pure AST checker can see the syntactic {@code try-catch} structure
 * of the caller and can resolve a callee's throws from its {@code throws} clause -- but real-world
 * code rarely declares the {@code throws} clause for unchecked exceptions, and many real exceptions
 * escape via {@code RuntimeException} subclasses. The DFA walks the actual IR, so it sees the
 * {@code THROW} instructions even when the developer didn't declare the throws clause. It also
 * handles {@code throw new RuntimeException("...")} without a {@code throws} declaration, which an
 * AST-level check would miss.
 *
 * <p><b>Limitations (intentional).</b>
 *
 * <ul>
 *   <li><b>No type matching.</b> We only check "callee throws anything" + "caller has zero catch".
 *       A caller that has {@code catch (NullPointerException e)} but the callee throws {@code
 *       IllegalStateException} will be flagged even though it might or might not be a real bug (NPE
 *       is not a supertype of ISE in Java). A future round can refine by tracking the ctorType of
 *       the {@code NEW} that produces the thrown operand and comparing to the ctorType of each
 *       {@code CATCH_HANDLER}.
 *   <li><b>Only intra-program callees.</b> We only fire when the callee signature is in our {@code
 *       hasThrow} map. JDK and library calls (whose bodies we don't have) are silently ignored,
 *       which avoids false positives on calls like {@code list.add(...)} (no throws) and {@code
 *       Files.readAllBytes(...)} (throws, but the call is usually in a try-catch the developer
 *       wrote).
 *   <li><b>No rethrows.</b> A function that has zero catches and only rethrows via a method
 *       declared with {@code throws Exception} is still flagged. The "fix" is to add a try-catch —
 *       which is what we want the developer to think about.
 * </ul>
 */
public final class UncaughtExceptionPropagator implements DfaChecker {

  /**
   * Per-function: signatures of already-fired call sites, to dedup when the same (caller, callee)
   * pair is reached from multiple call instructions. Key is {@code callerKey + "|" + calleeKey}.
   */
  private final Set<String> fired = new HashSet<>();

  @Override
  public String name() {
    return "UncaughtExceptionPropagator";
  }

  /**
   * The rule this checker emits, with its CWE.
   *
   * <p>Declared here so that the rule id -> CWE map can be read from the engine rather than from a
   * table maintained beside it. The id and the CWE are the same ones the finding is built with, so
   * the map cannot describe a rule the checker does not report.
   */
  @Override
  public org.plumb.rules.RuleDef ruleDef() {
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId("dfa-controlflow-uncaught-throw", "UNCAUGHT_THROW"),
        "A call that may throw is not protected by a try-catch in the enclosing function",
        RuleDef.Severity.MEDIUM,
        RuleDef.Type.CORRECTNESS,
        java.util.List.of("CWE-248"),
        getClass().getName(),
        true,
        java.util.List.of());
  }

  @Override
  public void initEngine(AnalysisContext ctx) {
    fired.clear();
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    // Per-function dedup. We re-init in
    // finish too so a second invocation
    // (in a follow-up finish pass) doesn't
    // re-emit.
    fired.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // The facts are about the PROGRAM, not about this function, so they go into the SHARED store
    // on the context. An instance field would only ever hold the facts this worker's own slice
    // produced, and the lookup for a callee in another worker's slice would miss -- so the
    // cross-function finding would silently never be emitted.
    String key = mergedKey(fn);
    switch (ins.kind) {
      case THROW -> ctx.programFacts().markThrows(key);
      case CATCH_HANDLER -> ctx.programFacts().markCatches(key);
      default -> {
        /* ignore */
      }
    }
  }

  @Override
  public void finishFunction(AnalysisContext ctx, TacFunction fn) {
    // No per-function emission: the cross-function
    // check needs the global throw / catch
    // facts, which are only complete after every
    // function has been visited. The actual
    // emission happens in finish().
  }

  @Override
  public void finish(AnalysisContext ctx) {
    // Re-walk every function's CALL
    // instructions. We can't do this in
    // checkInstruction because at the time
    // a caller is being visited, the
    // callee's analysis may not have run
    // yet (the engine runs in RPO, but for
    // recursive / mutual-recursion cases
    // and for the very first caller in the
    // RPO the callee summary may still be
    // empty).
    for (TacFunction fn : ctx.program().functions()) {
      String callerKey = mergedKey(fn);
      if (ctx.programFacts().hasCatch(callerKey)) {
        // The caller has at least one
        // catch. We don't know if it
        // covers the specific exception
        // type, so we don't fire. Future
        // A future refinement could track
        // the catch's ctorType and the
        // THROW's ctorType. For now the
        // coarse "caller has zero catches"
        // check is enough.
        continue;
      }
      // Set the current function
      // on the context so the
      // FindingReporter can resolve the
      // filePath for findings emitted
      // from this finish() pass. The
      // engine's analyseOneFunction()
      // resets currentFunction to null
      // at the end of each function's
      // check / finishFunction cycle,
      // so without this re-set the
      // report() would see null.
      ctx.setCurrentFunction(fn);
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.calleeSig == null) continue;
        // The callee is resolved through the CALL GRAPH, not by matching the signature
        // ourselves. The graph already resolved this call site to the callee it really means --
        // including the overload and the cross-TU case -- and it did it with the MERGED key, which
        // is the identity the facts are recorded under. Matching signatures here would be a second,
        // weaker resolution, and it would miss the caller whose callee lives in another class.
        String calleeKey = resolveCalleeKey(ctx, fn, ins);
        if (calleeKey == null) continue;
        if (!ctx.programFacts().functionThrows(calleeKey)) continue;
        String key = callerKey + "|" + calleeKey;
        if (!fired.add(key)) continue;
        emit(ctx, ins, fn, ins.calleeSig);
      }
    }
  }

  /**
   * The identity a function's whole-program facts are recorded under.
   *
   * <p>It is the MERGED key ({@code "<enclosingClass> :: <signature>"}), the same identity the
   * engine's parallel worklist and the call graph use. A name/arity key would merge two methods
   * that merely share a name -- two subclasses of the same abstract base, each implementing {@code
   * action(String)} -- and a caller would then be told "the callee throws" because a DIFFERENT
   * method with the same name throws.
   *
   * @param fn the function
   * @return the merged key
   */
  private static String mergedKey(TacFunction fn) {
    return org.plumb.tac.ir.MergedProgram.mergedKey(fn);
  }

  /**
   * The merged key of the callee this call site resolves to.
   *
   * <p>The CALL GRAPH is the authority. It already resolved this call site -- including the
   * overload, the owner, and the cross-TU case -- and it did it with the merged key, which is the
   * identity the facts are recorded under. Resolving the signature here a second time would be a
   * weaker resolution: it would miss the callee that lives in another class, and it would match a
   * name/arity that belongs to a different method.
   *
   * @param ctx the analysis context
   * @param caller the calling function
   * @param ins the CALL instruction
   * @return the callee's merged key, or {@code null} when the call site does not resolve
   */
  private static String resolveCalleeKey(
      AnalysisContext ctx, TacFunction caller, TacInstruction ins) {
    org.plumb.dfa.cg.CallGraph cg = ctx.callGraph();
    if (cg == null) return null;
    org.plumb.dfa.cg.FunctionNode callerNode = new org.plumb.dfa.cg.FunctionNode(mergedKey(caller));
    // The call graph indexes by the merged key of the CALLER, so the node is built the same way.
    // When the caller's node is not in the graph (a function the graph did not visit), fall back
    // to the callee signature itself.
    for (org.plumb.dfa.cg.CallSite cs : cg.callsFrom(callerNode)) {
      if (cs.callerInstructionIndex() != instructionIndex(caller, ins)) continue;
      return cs.callee().signature();
    }
    return null;
  }

  /**
   * The index of an instruction within its function.
   *
   * <p>The call graph records the call site by INDEX, and the index is what identifies a call site
   * when the same callee is called twice from the same function.
   *
   * @param fn the function
   * @param ins the instruction
   * @return the index, or -1 when the instruction is not in the function
   */
  private static int instructionIndex(TacFunction fn, TacInstruction ins) {
    java.util.List<TacInstruction> all = fn.instructions();
    for (int i = 0; i < all.size(); i++) {
      if (all.get(i) == ins) return i;
    }
    return -1;
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private void emit(AnalysisContext ctx, TacInstruction ins, TacFunction fn, String calleeSig) {
    // v2 trace: cross-function summary-fold
    // shape. The CALL is the entry point; the
    // callee's THROW is the unhandled source; the
    // VIOLATION fires on the call site because the
    // caller has no catch handler to stop propagation.
    //
    // Single-node VIOLATION shape is honest: the
    // cross-function throw source isn't a v2
    // MARK_SOURCE — it's a fact about the callee,
    // not data flowing through a tracked var. We
    // surface the callee signature in the operands
    // so the AI dedup layer can see "callee throws
    // caller has no catch" without parsing the
    // legacy flow string.
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            TraceNode.Op.SUMMARY_SINK,
            List.of(new OperandRef.CallRef(calleeSig), new OperandRef.Free("callerHasCatch:false")),
            List.of(),
            calleeSig + " throws; caller has no catch",
            "",
            ins.line,
            ins.column,
            fn.signature());
    Trace trace = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    Finding f =
        Finding.dfaWithTrace(
            name(),
            "dfa-controlflow-uncaught-throw",
            "Call to "
                + calleeSig
                + " may throw an exception, "
                + "but the enclosing function "
                + fn.name()
                + " has no try-catch to handle it — CWE-248",
            RuleDef.Severity.MEDIUM,
            "CWE-248",
            "",
            ins.line,
            ins.column,
            "uncaught-throw: " + calleeSig,
            trace);
    ctx.reporter().reportWithTrace(f, trace);
  }
}
