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
package org.plumb.reporter;

import java.util.Locale;

/**
 * How much of the analysed code the engine actually modelled.
 *
 * <h2>Why the report has to carry this</h2>
 *
 * <p>The completeness record answers "did the analysis finish what it was asked to do?". This
 * record answers the other half: "when it looked, did it understand what it saw?".
 *
 * <p>Those are different questions, and the second one is the one a reader cannot answer from the
 * findings. A scan that reports three SQL injections may have looked at a thousand call sites and
 * modelled two of them; a scan that reports nothing may have modelled all of them and found no
 * problem. The findings alone do not distinguish the two, and the difference is exactly the
 * difference between "this code is clean" and "this code was mostly not looked at".
 *
 * <p>So the report carries the ratio. It is a claim about the ANALYSIS, not about the code, and it
 * travels in the same channel as {@link AnalysisCompleteness}: it may only ever make the report
 * more cautious.
 *
 * <h2>What "no model" means here</h2>
 *
 * <p>A call site is <b>modelled</b> when the engine has a statement about what the call does to the
 * data:
 *
 * <ul>
 *   <li>the callee's <b>body</b> was analysed (the call resolves to a function in the scanned set),
 *       so the engine derived the call's effect instead of assuming it;
 *   <li>the per-language <b>semantics table</b> names the callee (PRESERVES / DISSOCIATES / ...);
 *   <li>a <b>passthrough table</b> names the callee (the receiver-passthrough or
 *       argument-passthrough catalogue).
 * </ul>
 *
 * <p>Every other call site is <b>unmodelled</b>: the engine had no statement about it, and whatever
 * it concluded about the data flowing through that call was a default, not a derivation. The four
 * buckets are DISJOINT and are counted in priority order (body, then semantics, then passthrough),
 * so the numbers add up exactly: {@code callSites == withBody + withSemantics + withPassthrough +
 * unmodelled}.
 *
 * <h2>What is NOT counted as "no model"</h2>
 *
 * <p>The sink catalogue, the input-source catalogue and the sanitizer list are knowledge about
 * individual CALLS, and they are real knowledge -- but they are knowledge about <i>what a rule
 * should say about this call</i>, not about <i>what the call does to the data</i>. A call that the
 * sink catalogue matches is not thereby understood: the taint flowing through it is still a guess.
 * The ratio this record reports is therefore about the data flow, which is the part a reader has to
 * trust before acting on a finding.
 */
public final class ModelCoverage {

  /** The number of functions in the analysed programs. */
  private final int functions;

  /** The functions whose analysis was at full source precision. */
  private final int functionsAtFullPrecision;

  /** The problems the frontend declared: the constructs it could not lower. */
  private final int declaredProblems;

  /** The call instructions in the analysed programs: the CALLs and the UNINTERPRETEDs. */
  private final int callSites;

  /** The call sites whose callee was analysed (its body was in the scanned set). */
  private final int callSitesWithBody;

  /** The call sites the per-language semantics table names. */
  private final int callSitesWithSemantics;

  /** The call sites a passthrough table names. */
  private final int callSitesWithPassthrough;

  /** The call sites the engine had no statement about. */
  private final int callSitesUnmodelled;

  /** The instructions in the analysed programs. */
  private final int instructions;

  /** The instructions whose value the frontend could not model. */
  private final int havocInstructions;

  /** The instructions that are calls whose body the frontend could not see. */
  private final int uninterpretedInstructions;

  /**
   * A coverage record.
   *
   * @param functions the functions analysed
   * @param functionsAtFullPrecision the functions analysed at full source precision
   * @param declaredProblems the problems the frontend declared
   * @param callSites the call sites
   * @param callSitesWithBody the call sites whose callee was analysed
   * @param callSitesWithSemantics the call sites the semantics table names
   * @param callSitesWithPassthrough the call sites a passthrough table names
   * @param callSitesUnmodelled the call sites with no statement at all
   * @param instructions the instructions
   * @param havocInstructions the instructions whose value could not be modelled
   * @param uninterpretedInstructions the calls whose body could not be seen
   */
  public ModelCoverage(
      int functions,
      int functionsAtFullPrecision,
      int declaredProblems,
      int callSites,
      int callSitesWithBody,
      int callSitesWithSemantics,
      int callSitesWithPassthrough,
      int callSitesUnmodelled,
      int instructions,
      int havocInstructions,
      int uninterpretedInstructions) {
    this.functions = functions;
    this.functionsAtFullPrecision = functionsAtFullPrecision;
    this.declaredProblems = declaredProblems;
    this.callSites = callSites;
    this.callSitesWithBody = callSitesWithBody;
    this.callSitesWithSemantics = callSitesWithSemantics;
    this.callSitesWithPassthrough = callSitesWithPassthrough;
    this.callSitesUnmodelled = callSitesUnmodelled;
    this.instructions = instructions;
    this.havocInstructions = havocInstructions;
    this.uninterpretedInstructions = uninterpretedInstructions;
  }

  /** A record for an analysis that modelled nothing: no functions, no call sites. */
  public static ModelCoverage nothingAnalysed() {
    return new ModelCoverage(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  /** The functions in the analysed programs. */
  public int functions() {
    return functions;
  }

  /** The functions whose analysis was at full source precision. */
  public int functionsAtFullPrecision() {
    return functionsAtFullPrecision;
  }

  /** The functions whose analysis was NOT at full source precision. */
  public int functionsBelowFullPrecision() {
    return functions - functionsAtFullPrecision;
  }

  /** The problems the frontend declared: the constructs it could not lower. */
  public int declaredProblems() {
    return declaredProblems;
  }

  /** The call instructions in the analysed programs: the CALLs and the UNINTERPRETEDs. */
  public int callSites() {
    return callSites;
  }

  /** The call sites whose callee was analysed (its body was in the scanned set). */
  public int callSitesWithBody() {
    return callSitesWithBody;
  }

  /** The call sites the per-language semantics table names. */
  public int callSitesWithSemantics() {
    return callSitesWithSemantics;
  }

  /** The call sites a passthrough table names. */
  public int callSitesWithPassthrough() {
    return callSitesWithPassthrough;
  }

  /** The call sites the engine had no statement about. */
  public int callSitesUnmodelled() {
    return callSitesUnmodelled;
  }

  /** The instructions in the analysed programs. */
  public int instructions() {
    return instructions;
  }

  /** The instructions whose value the frontend could not model. */
  public int havocInstructions() {
    return havocInstructions;
  }

  /** The instructions that are calls whose body the frontend could not see. */
  public int uninterpretedInstructions() {
    return uninterpretedInstructions;
  }

  /**
   * The share of the call sites the engine had no statement about.
   *
   * @return a number in [0, 1]; zero when there were no call sites at all
   */
  public double unmodelledCallRatio() {
    return ratio(callSitesUnmodelled, callSites);
  }

  /**
   * The share of the functions analysed at full source precision.
   *
   * @return a number in [0, 1]; zero when there were no functions at all
   */
  public double fullPrecisionFunctionRatio() {
    return ratio(functionsAtFullPrecision, functions);
  }

  /**
   * The share of the instructions whose value could not be modelled.
   *
   * @return a number in [0, 1]; zero when there were no instructions at all
   */
  public double havocInstructionRatio() {
    return ratio(havocInstructions, instructions);
  }

  private static double ratio(int part, int whole) {
    if (whole <= 0) return 0.0;
    return (double) part / (double) whole;
  }

  /**
   * A one-line summary, for the log.
   *
   * @return the summary
   */
  public String summary() {
    if (callSites == 0 && functions == 0) {
      return "no data flow was analysed, so there is no coverage to report";
    }
    return String.format(
        Locale.ROOT,
        "%d of %d call sites had no model (%.1f%%); %d of %d functions were analysed below full"
            + " source precision (%.1f%%)",
        callSitesUnmodelled,
        callSites,
        unmodelledCallRatio() * 100.0,
        functionsBelowFullPrecision(),
        functions,
        (1.0 - fullPrecisionFunctionRatio()) * 100.0);
  }

  /**
   * The coverage as a JSON object.
   *
   * <p>The shape is flat on purpose: every number is a count, and every count has a name that says
   * what it counts. A consumer that wants the ratio computes it, rather than trusting a ratio that
   * was rounded somewhere.
   *
   * @return the JSON text, without a trailing newline
   */
  public String toJson() {
    StringBuilder sb = new StringBuilder(512);
    sb.append('{');
    number(sb, "functions", functions);
    sb.append(',');
    number(sb, "functionsAtFullPrecision", functionsAtFullPrecision);
    sb.append(',');
    number(sb, "functionsBelowFullPrecision", functionsBelowFullPrecision());
    sb.append(',');
    number(sb, "declaredProblems", declaredProblems);
    sb.append(',');
    number(sb, "callSites", callSites);
    sb.append(',');
    number(sb, "callSitesWithBody", callSitesWithBody);
    sb.append(',');
    number(sb, "callSitesWithSemantics", callSitesWithSemantics);
    sb.append(',');
    number(sb, "callSitesWithPassthrough", callSitesWithPassthrough);
    sb.append(',');
    number(sb, "callSitesUnmodelled", callSitesUnmodelled);
    sb.append(',');
    number(sb, "unmodelledCallRatio", unmodelledCallRatio());
    sb.append(',');
    number(sb, "instructions", instructions);
    sb.append(',');
    number(sb, "havocInstructions", havocInstructions);
    sb.append(',');
    number(sb, "uninterpretedInstructions", uninterpretedInstructions);
    sb.append(',');
    number(sb, "havocInstructionRatio", havocInstructionRatio());
    sb.append('}');
    return sb.toString();
  }

  private static void number(StringBuilder sb, String key, long value) {
    sb.append('"').append(key).append("\":").append(value);
  }

  private static void number(StringBuilder sb, String key, double value) {
    // Three decimals is enough to tell "almost everything" from "almost nothing", and it keeps the
    // number exactly comparable: the same coverage always prints the same bytes.
    sb.append('"').append(key).append("\":").append(String.format(Locale.ROOT, "%.3f", value));
  }
}
