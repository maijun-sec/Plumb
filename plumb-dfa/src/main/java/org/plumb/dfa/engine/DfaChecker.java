package org.plumb.dfa.engine;

import org.plumb.dfa.config.CheckerConfig;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * Base interface for unified DFA checkers.
 *
 * <p>Each checker extends this interface and implements the per-function analysis. The engine calls
 * {@link #initEngine} once before any function is visited, then for each function in reverse
 * post-order (RPO): {@link #initFunction} + {@link #checkInstruction} + {@link #finishFunction} +
 * {@link #summarizeFunction}. Finally, {@link #finish} once at the end. The lifecycle is mirrored
 * from Fathom's {@code DfaChecker}.
 *
 * <h2>Lifecycle</h2>
 *
 * <pre>
 *   for each cfg in engine:      // RPO order, callee before caller
 *     checker.initFunction(ctx)
 *     for each ins in cfg:
 *       checker.checkInstruction(ins, fn, ctx)
 *     checker.finishFunction(ctx)
 *     summary = checker.summarizeFunction(ctx)
 *     cache.put(cfg.signature, summary)
 *   checker.finish(ctx)
 * </pre>
 *
 * <p>Checkers are stateless per-function; any cross-function state (e.g. summary table) lives in
 * the {@link AnalysisContext}.
 *
 * <h2>The summary contract</h2>
 *
 * <p>The engine calls {@link #summarizeFunction} after {@code finishFunction} for the same
 * function. The returned {@link FunctionSummary} describes what the checker has observed about the
 * function's parameters and return value. The engine merges summaries across all registered
 * checkers and stores the merged result in the shared {@link AnalysisContext#summaryCache()}, keyed
 * by function signature. A caller-side checker that later sees a {@code CALL} instruction to this
 * function looks the summary up and projects the parts it cares about (e.g. a SQL-injection checker
 * projects the {@code Sink("sql.sinks", ...)} operations).
 *
 * <p>By default, {@code summarizeFunction} returns {@link FunctionSummary#empty()}. Checkers that
 * want to contribute should override and build a {@link FunctionSummary.Builder}.
 *
 * <p><b>Direct findings don't go in the summary.</b> A bug that lives entirely inside the function
 * body (dead code, an unconditional {@code System.exit}) is reported via {@code
 * ctx.reporter().report(...)} during {@code checkInstruction} and never reaches the summary. The
 * summary is only for effects that matter to a caller.
 *
 * <h2>AI dedup (Step 7)</h2>
 *
 * <p>Each checker owns its own rule prompt. The runner calls {@link #getAiPrompt(Finding)} for
 * every finding the checker emitted, passes the prompt + finding context to a Re-Act agent (in
 * plumb-ai), and tags the verdict on the finding. The default returns {@code null} which means "no
 * AI dedup for this rule".
 */
public interface DfaChecker {

  /** Human-readable name; used in logs and SARIF. */
  String name();

  /**
   * Called once at engine startup. The checker may use this to read the engine config (e.g. check
   * which fact kinds are declared) and to build any global state.
   */
  default void initEngine(AnalysisContext ctx) {}

  /**
   * Called once per function before any instruction is visited. The checker can pre-compute
   * per-function state here.
   */
  default void initFunction(AnalysisContext ctx, TacFunction fn) {}

  /**
   * Called once per instruction. The default implementation does nothing; checkers override to
   * inspect the instruction and emit findings.
   */
  default void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {}

  /** Called once per function after all instructions are visited. The default is a no-op. */
  default void finishFunction(AnalysisContext ctx, TacFunction fn) {}

  /**
   * Called once per function after {@link #finishFunction}, in the same RPO order. The returned
   * {@link FunctionSummary} describes the param-bound effects this checker has observed for {@code
   * fn}. The engine merges this with the summaries returned by the other registered checkers and
   * stores the result in the shared {@code AnalysisContext.summaryCache()}, keyed by {@code
   * fn.signature()}. A return of {@link FunctionSummary#empty()} means "I have no observations to
   * contribute" — the default for checkers that don't model cross-function effects.
   */
  default FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  /**
   * Called once at engine shutdown, after every function has been visited. Used for cross-function
   * post-processing (e.g. emit-aggregated findings).
   */
  default void finish(AnalysisContext ctx) {}

  /**
   * The {@link CheckerConfig} for this checker. Provided by the engine via the {@link
   * org.plumb.dfa.config.CheckerRegistry}.
   */
  default CheckerConfig config(AnalysisContext ctx) {
    return ctx.config().checkers().byName(name()).orElse(null);
  }

  /**
   * Step 7 -- The rule-specific knowledge the LLM is told about this checker before it judges a
   * finding. The runner prepends the system prompt (FP reducer / bug scanner role) + the dataflow
   * trace + the source context, and sends the whole thing through the Re-Act agent.
   *
   * <p>Default: {@code null} (no AI dedup for this rule). Checkers that want AI dedup override this
   * to return a short, focused description of the rule: what the bug is, when it does NOT fire, and
   * what a sanitizer looks like. The runner treats a non-null prompt as the "rule prompt" the LLM
   * sees.
   */
  default String getAiPrompt(Finding finding) {
    return null;
  }
}
