/*
 * ? shared v2 trace helpers for DFA checkers
 * that detect patterns at a single call site
 * (no data flow, no cross-function propagation).
 *
 * <p>The shared shape is a single-node VIOLATION
 * trace: one {@link TraceNode.Role#VIOLATION} node
 * with the chosen {@link TraceNode.Op} and an
 * optional {@link OperandRef.CallRef} for the
 * offending call signature. Lets the AI dedup
 * layer recognise the pattern shape without
 * regex-parsing the legacy {@code flow} string.</p>
 *
 * <p>Find-by-name: when the checker is a "find
 * the API call" detector (WeakPrng, ProcessControl,
 * ObsoleteFunction, Recursion, ...), the trace
 * is exactly the shape the AI dedup prompt is
 * already conditioned on. When the checker is a
 * "track data flow" detector (HardCodedKey,
 * HardCodedPasswordSink, ...), the per-class
 * tracking code uses {@code TraceBuilder.addPropagate}
 * directly ? this helper is for the
 * single-node pattern case.</p>
 */
package org.plumb.dfa.trace;

import java.util.List;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

public final class PatternTrace {

  private PatternTrace() {}

  /**
   * Emit a single-node v2 trace as a {@code VIOLATION} and report the finding via {@code
   * reportWithTrace}. The legacy v1 {@code dataflowSteps} are populated by {@link
   * Finding#dfaWithTrace} flattening the single-node trace ? v1 consumers see a one-step path,
   * identical to the pre- shape (a single violation step).
   */
  public static void report(
      AnalysisContext ctx,
      TacFunction fn,
      TacInstruction ins,
      TraceNode.Op op,
      String calleeSig,
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String flow) {
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            op,
            List.of(new OperandRef.CallRef(calleeSig)),
            List.of(),
            "pattern match: " + op,
            "",
            ins.line,
            ins.column,
            fn.signature());
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    Finding f =
        Finding.dfaWithTrace(
            checkerName, ruleId, message, severity, cwe, "", ins.line, ins.column, flow, v2);
    ctx.reporter().reportWithTrace(f, v2);
  }

  /**
   * follow-up: same as {@link #report} but the operand is a {@link OperandRef.Free} tag (e.g.
   * {@code "receiver:foo"}) rather than a {@link OperandRef.CallRef}. Used by checkers that detect
   * "the receiver of a known-NULL flow" or "the value of a field compared with itself" ? the
   * pattern is still a single-node VIOLATION but the "what's the call" question doesn't apply.
   */
  public static void reportFree(
      AnalysisContext ctx,
      TacFunction fn,
      TacInstruction ins,
      TraceNode.Op op,
      String freeTag,
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String flow) {
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            op,
            List.of(new OperandRef.Free(freeTag)),
            List.of(),
            "pattern match: " + op + " — " + freeTag,
            "",
            ins.line,
            ins.column,
            fn.signature());
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    Finding f =
        Finding.dfaWithTrace(
            checkerName, ruleId, message, severity, cwe, "", ins.line, ins.column, flow, v2);
    ctx.reporter().reportWithTrace(f, v2);
  }

  /**
   * file-level scan variant. Used by DFA checkers that detect patterns in the source TEXT (regex /
   * comment / string literal scan) rather than in the TAC IR. Examples: {@code Cwe382SystemExit},
   * {@code Cwe526EnvVar}, {@code Cwe570AlwaysFalse}, {@code Cwe571AlwaysTrue}. The shared shape is
   * still a single-node VIOLATION trace ? the only difference is that we don't have a {@link
   * TacInstruction} to point at; instead we record the source line / column discovered by the regex
   * matcher.
   */
  public static void reportFileLevel(
      AnalysisContext ctx,
      String fileLevelFnSig,
      TraceNode.Op op,
      String calleeSig,
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      int line,
      int column,
      String flow) {
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            op,
            List.of(new OperandRef.CallRef(calleeSig)),
            List.of(),
            "file-level pattern: " + op,
            "",
            line,
            column,
            fileLevelFnSig);
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    Finding f =
        Finding.dfaWithTrace(
            checkerName, ruleId, message, severity, cwe, "", line, column, flow, v2);
    ctx.reporter().reportWithTrace(f, v2);
  }

  /**
   * follow-up: a checker fired in {@code finishFunction} (or similar) where there's no {@link
   * TacInstruction} to point at, but we still have a {@link TacFunction} signature + line/column.
   * The {@code detail} string flows into the v2 trace {@code note} + a {@link OperandRef.Free}
   * operand so the AI dedup layer can see "this is a per- function rollup, not a per-call finding".
   *
   * <p>Used by {@code UninitNonnullDfaChecker}, {@code OrmLostUpdateDfaChecker}, and any other
   * checker that batches findings at {@code finishFunction} time.
   */
  public static void reportPerFunction(
      AnalysisContext ctx,
      TacFunction fn,
      int line,
      int column,
      TraceNode.Op op,
      String detail,
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String flow) {
    TraceBuilder b = new TraceBuilder();
    int handle =
        b.addViolation(
            op,
            List.of(new OperandRef.Free(detail)),
            List.of(),
            "per-function pattern: " + op + " — " + detail,
            "",
            line,
            column,
            fn.signature());
    Trace v2 = b.build(List.of(handle), Trace.CompressionLevel.FULL);
    Finding f =
        Finding.dfaWithTrace(
            checkerName, ruleId, message, severity, cwe, "", line, column, flow, v2);
    ctx.reporter().reportWithTrace(f, v2);
  }
}
