/*
 * Hard-coded cryptographic key (CWE-321).
 *
 * <p>DFA-style pattern detector. Walks the IR
 * looking for a {@code SecretKeySpec} constructor
 * whose first argument is the byte[] of a
 * string-literal source:</p>
 *
 * <pre>{@code
 *   String data = "23 ~j;asn!@#/>as";         // FLAW
 *   byte[] keyBytes = data.getBytes("UTF-8");
 *   SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
 * }</pre>
 *
 * <p>Detection strategy (per-function):</p>
 * <ol>
 *   <li>Track which {@link TacOperand.TacVar}s
 *       were last assigned from a string constant
 *       (MOVE v = "literal").</li>
 *   <li>Track which vars are the result of
 *       {@code v.getBytes(...)} on a tracked
 *       var (CALL dest = v.getBytes(...)).</li>
 *   <li>On {@code NEW SecretKeySpec.(init)/2},
 *       fire if the first arg is a tracked
 *       (directly or transitively via getBytes)
 *       var.</li>
 * </ol>
 *
 * <p>The sibling CWE-259 (hard-coded password
 * in {@code DriverManager.getConnection}) is
 * covered by {@link HardCodedPasswordSinkChecker}.</p>
 *
 * <h2>v2 trace</h2>
 *
 * <p>The check is a per-function data flow:
 * literal MOVE → optional getBytes/toCharArray → sink
 * (the {@code new SecretKeySpec(...)} ctor). The
 * v2 trace captures that shape:</p>
 * <ol>
 *   <li>{@code MARK_SOURCE} at the literal MOVE —
 *       records the literal's source line.</li>
 *   <li>{@code MOVE} at the {@code getBytes} /
 *       {@code toCharArray} passthrough —
 *       wired to the source handle, records
 *       the byte[] derivation line.</li>
 *   <li>{@code SINK_SQL_INJECTION} (or similar
 *       credential-sink op) at the
 *       {@code new SecretKeySpec} ctor —
 *       wired to the immediate parent
 *       (literal-sourced var directly used,
 *       or derived-of-literal var).</li>
 * </ol>
 *
 * <p>The v1 {@code dataflow_steps} flattens to a
 * SOURCE → (optional MOVE) → SINK shape so
 * pre- reporters see no change.</p>
 */
package org.plumb.dfa.checkers.taint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.dfa.checkers.LiteralSourcedTracker;
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
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

public final class HardCodedKeyChecker implements DfaChecker {
  private static final String RULE_ID = "dfa-taint-hard-coded-crypto-key";

  /**
   * Per-function literal-sourced tracker shared with {@link HardCodedPasswordSinkChecker} via the
   * shared DRY refactor. Uses a 4-char minimum (vs the password checker's 3-char) to preserve the
   * original semantics -- keys need to be a bit longer to be plausibly an AES/DES key.
   */
  private final LiteralSourcedTracker tracker = new LiteralSourcedTracker(4);

  /**
   * v2 trace state. Per-function map from the literal-sourced var (or derived var) to its v2
   * handle, so the SecretKeySpec ctor can wire its parent edge to the right node.
   */
  private final Map<TacOperand.TacVar, Integer> v2HandleByVar = new HashMap<>();

  private TraceBuilder v2 = new TraceBuilder();
  private final List<Integer> sinkHandles = new ArrayList<>();

  /** Per-function dedup so we don't double-report. */
  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "HardCodedCryptoKey";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    tracker.clear();
    v2HandleByVar.clear();
    v2 = new TraceBuilder();
    sinkHandles.clear();
    reported.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    // Delegate MOVE and CALL passthrough to the
    // shared tracker.
    boolean trackedMove = tracker.handleMove(ins);
    boolean trackedPassthrough = tracker.handlePassthroughCall(ins);

    // Emit v2 nodes for tracked MOVE/CALL in
    // parallel with the tracker's per-function
    // bookkeeping. Each tracked MOVE becomes
    // a MARK_SOURCE; each passthrough becomes
    // a MOVE chained to the receiver's source
    // handle.
    if (trackedMove
        && ins.dest instanceof TacOperand.TacVar dv
        && ins.lhs1 instanceof TacOperand.Const c) {
      String value =
          c.value() instanceof String s ? s : (c.value() == null ? "" : c.value().toString());
      int handle =
          v2.addSource(
              TraceNode.Op.MARK_SOURCE,
              List.of(
                  new OperandRef.Var(dv.name(), 0),
                  new OperandRef.ConstStr(
                      value.length() > 32 ? value.substring(0, 32) + "..." : value)),
              "literal MOVE: " + dv.name() + " = <literal>",
              "",
              ins.line,
              ins.column,
              fn.signature());
      v2HandleByVar.put(dv, handle);
      return;
    }
    if (trackedPassthrough && ins.dest instanceof TacOperand.TacVar tv && !ins.args.isEmpty()) {
      TacOperand recv = ins.args.get(0);
      if (recv instanceof TacOperand.TacVar rv) {
        Integer parent = v2HandleByVar.get(rv);
        if (parent != null) {
          int handle =
              v2.addPropagate(
                  TraceNode.Op.MOVE,
                  List.of(new OperandRef.Var(tv.name(), 0), new OperandRef.Var(rv.name(), 0)),
                  parent,
                  ins.calleeSig + " passthrough",
                  "",
                  ins.line,
                  ins.column,
                  fn.signature());
          v2HandleByVar.put(tv, handle);
        }
      }
      return;
    }

    // NEW SecretKeySpec.<init>/2 — fire if
    // arg 0 is literal or bytes-of-literal.
    if (ins.kind == TacInstruction.Kind.NEW
        && ins.ctorType != null
        && ins.ctorType.kind() == TacType.Kind.CLASS
        && ins.ctorType.className() != null
        && ins.ctorType.className().endsWith("SecretKeySpec")
        && ins.args.size() >= 2) {
      TacOperand keyArg = ins.args.get(0);
      if (!(keyArg instanceof TacOperand.TacVar keyVar)) return;
      String sourceLiteral = tracker.resolveLiteral(keyArg, 4);
      if (sourceLiteral == null) return;
      String key = fn.signature() + "|" + ins.line + "|321";
      if (!reported.add(key)) return;
      // Wire the sink to the key-arg's v2 handle.
      Integer parent = v2HandleByVar.get(keyVar);
      int sinkHandle =
          v2.addSink(
              TraceNode.Op.SINK_SQL_INJECTION, // closest credential-sink enum
              List.of(
                  new OperandRef.CtorRef("SecretKeySpec"), new OperandRef.Var(keyVar.name(), 0)),
              parent == null ? List.of() : List.of(parent),
              "new SecretKeySpec(<literal len=" + sourceLiteral.length() + ">)",
              "",
              ins.line,
              ins.column,
              fn.signature());
      sinkHandles.add(sinkHandle);
      Trace trace = v2.build(sinkHandles, Trace.CompressionLevel.FULL);
      Finding f =
          Finding.dfaWithTrace(
              name(),
              RULE_ID,
              "Hard-coded cryptographic key: SecretKeySpec constructed from "
                  + "literal string of length "
                  + sourceLiteral.length(),
              RuleDef.Severity.HIGH,
              "CWE-321",
              "",
              ins.line,
              ins.column,
              "hard-coded-key: new SecretKeySpec(<literal len=" + sourceLiteral.length() + ">)",
              trace);
      ctx.reporter().reportWithTrace(f, trace);
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static final String AI_PROMPT =
"""
        RULE: Hard-coded cryptographic key (CWE-321).
        This rule fires when a SecretKeySpec is constructed
        from a string literal (or the byte[] of one) and
        used as an AES/DES key.

        A finding is a TRUE POSITIVE (TP) when the literal
        is a real-looking key in production code (any
        non-trivial length string committed to source).
        A finding is a FALSE POSITIVE (FP) when the literal
        is an obvious placeholder ("0123456789abcdef",
        "changeit"), a JCA test vector, or in a test
        fixture. UNKNOWN when ambiguous.
""";

  @Override
  public String getAiPrompt(Finding finding) {
    return AI_PROMPT;
  }
}
