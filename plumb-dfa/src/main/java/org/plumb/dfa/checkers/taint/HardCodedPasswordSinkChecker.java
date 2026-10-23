/*
 * Hard-coded password in credential
 * sink (CWE-259), extended.
 *
 * <p>DFA-style pattern detector. Walks the IR
 * looking for a credential-sink call whose
 * password argument is a literal source (direct
 * or via {@code toCharArray()} /
 * {@code getBytes()} passthrough):</p>
 *
 * <h3>Canonical Juliet CWE-259 patterns caught</h3>
 *
 * <h4>1. {@code DriverManager.getConnection} (3-arg)</h4>
 * <pre>{@code
 *   String data = "7e5tc4s3";                  // FLAW
 *   Connection c = DriverManager.getConnection(
 *       "data-url", "root", data);
 *
 *   // OR — inline literal detection:
 *   Connection c = DriverManager.getConnection(
 *       "data-url", "root", "7e5tc4s3");
 *
 *   // OR — toCharArray passthrough:
 *   String data = "7e5tc4s3";
 *   char[] pwd = data.toCharArray();
 *   Connection c = DriverManager.getConnection(
 *       "data-url", "root", new String(pwd));
 * }</pre>
 *
 * <h4>2. {@code new KerberosKey(...)}</h4>
 * <pre>{@code
 *   String data = "7e5tc4s3";
 *   KerberosPrincipal principal = new KerberosPrincipal("test");
 *   KerberosKey key = new KerberosKey(principal, data.toCharArray(), null);
 * }</pre>
 *
 * <h4>3. {@code new PasswordAuthentication(...)}</h4>
 * <pre>{@code
 *   String data = "7e5tc4s3";
 *   PasswordAuthentication credentials =
 *       new PasswordAuthentication("user", data.toCharArray());
 * }</pre>
 *
 * <p>Detection strategy (per-function):</p>
 * <ol>
 *   <li>Track which {@link TacOperand.TacVar}s
 *       were last assigned from a string
 *       constant (MOVE v = "literal").</li>
 *   <li>Track which vars are the result of
 *       {@code v.toCharArray()} or
 *       {@code v.getBytes(...)} on a tracked
 *       var.</li>
 *   <li>On the credential-sink call (3 forms:
 *       {@code DriverManager.getConnection/3},
 *       {@code new KerberosKey/3},
 *       {@code new PasswordAuthentication/2}),
 *       check the password slot:
 *       <ul>
 *         <li>If the slot is a literal
 *             {@link TacOperand.Const} STRING
 *             (inline-literal detection).</li>
 *         <li>If the slot is a tracked var
 *             (literal-sourced or
 *             toCharArray/getBytes-of-literal).</li>
 *       </ul>
 *       Fire CWE-259 on the first match.</li>
 * </ol>
 *
 * <p>The Juliet CWE-259 dir has 180 files
 * split across 3 sink families: 60 each in
 * {@code driverManager} / {@code kerberosKey}
 * / {@code passwordAuth}. The driverManager
 * detector fires on 18/60; the kerberosKey +
 * passwordAuth detectors add the
 * 120 remaining files.</p>
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.rules.RuleDef;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.dfa.checkers.EntropyDetector;
import org.plumb.dfa.checkers.LiteralSourcedTracker;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceBuilder;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

public final class HardCodedPasswordSinkChecker implements DfaChecker {
  private static final String RULE_ID = "dfa-taint-hard-coded-password-sink";

  /**
   * Per-function literal-sourced tracker shared with {@link HardCodedKeyChecker}. Encapsulates the
   * MOVE-from-literal + toCharArray/getBytes passthrough state. The shared tracker keeps the same
   * semantics as the earlier inlined two-HashMap state.
   */
  private final LiteralSourcedTracker tracker = new LiteralSourcedTracker();

  /**
   * R-812 v2 trace state. Per-function map from each literal-sourced (or derived-of-literal) var to
   * its v2 handle. Lets each sink fire wire its parent edge to the literal's MOVE source —
   * preserving the "literal flowed into the sink" data path that AI dedup consumers need.
   */
  private final java.util.Map<TacOperand.TacVar, Integer> v2HandleByVar = new java.util.HashMap<>();

  private TraceBuilder v2 = new TraceBuilder();
  private final java.util.List<Integer> sinkHandles = new java.util.ArrayList<>();

  /** Per-function dedup so we don't double-report. */
  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "HardCodedPasswordSink";
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
    // 1) Mark literal-sourced vars.
    //    Delegates to the shared tracker.
    boolean trackedMove = tracker.handleMove(ins);
    // 1b) Track toCharArray() /
    //    getBytes() passthrough. Shared
    //    tracker handles the inline-literal
    //    receiver case (TacConst STRING) and
    //    the TacVar receiver case.
    boolean trackedPassthrough = tracker.handlePassthroughCall(ins);

    // R-812: emit v2 nodes for the tracked
    // MOVE / passthrough in parallel with the
    // tracker's bookkeeping. Each tracked MOVE
    // becomes a MARK_SOURCE; each passthrough
    // becomes a MOVE chained to the receiver's
    // v2 handle.
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
    // 3a) CALL DriverManager.getConnection/3 —
    //     the canonical 3-arg DB credential
    //     sink. Per TAC IR convention, every
    //     CALL (instance or static) prepends a
    //     "this" slot at args[0]; for static
    //     methods it's a placeholder TacVar of
    //     type <unknown>. So the 3 user args
    //     (url, user, password) live at
    //     args[1], args[2], args[3]. The
    //     password is therefore args[3] (NOT
    //     args[2] as the Java arity would
    //     suggest). Also accepts inline
    //     literal at args[3] (no MOVE).
    if (ins.kind == TacInstruction.Kind.CALL
        && ins.calleeSig != null
        && (ins.calleeSig.startsWith("java.sql.DriverManager.getConnection/3")
            || ins.calleeSig.startsWith("DriverManager.getConnection/3"))
        && ins.args.size() >= 4) {
      TacOperand pwd = ins.args.get(3);
      String sourceLiteral = resolvePwdLiteral(pwd);
      if (sourceLiteral == null) return;
      String key = fn.signature() + "|" + ins.line + "|259|driverManager";
      if (!reported.add(key)) return;
      reportSink(ins, fn, ctx, pwd, sourceLiteral, "DriverManager.getConnection");
      return;
    }
    // 3b) NEW KerberosKey.<init>/3 sink.
    //     Pattern: new KerberosKey(
    //     principal, data.toCharArray(),
    //     null). args[0] = principal,
    //     args[1] = password (the
    //     toCharArray-of-literal), args[2] =
    //     null. The ctor type is short class
    //     name (TacBuilder uses
    //     oce.getType().asString()), so we
    //     match by suffix.
    if (ins.kind == TacInstruction.Kind.NEW
        && ins.ctorType != null
        && ins.ctorType.kind() == TacType.Kind.CLASS
        && ins.ctorType.className() != null
        && ins.ctorType.className().endsWith("KerberosKey")
        && ins.args.size() >= 2) {
      TacOperand pwd = ins.args.get(1);
      String sourceLiteral = resolvePwdLiteral(pwd);
      if (sourceLiteral == null) return;
      String key = fn.signature() + "|" + ins.line + "|259|kerberosKey";
      if (!reported.add(key)) return;
      reportSink(ins, fn, ctx, pwd, sourceLiteral, "new KerberosKey");
      return;
    }
    // 3c) NEW PasswordAuthentication.<init>/2
    //     sink. Pattern: new
    //     PasswordAuthentication("user",
    //     data.toCharArray()). args[0] =
    //     username (literal), args[1] =
    //     password (the toCharArray-of-literal).
    if (ins.kind == TacInstruction.Kind.NEW
        && ins.ctorType != null
        && ins.ctorType.kind() == TacType.Kind.CLASS
        && ins.ctorType.className() != null
        && ins.ctorType.className().endsWith("PasswordAuthentication")
        && ins.args.size() >= 2) {
      TacOperand pwd = ins.args.get(1);
      String sourceLiteral = resolvePwdLiteral(pwd);
      if (sourceLiteral == null) return;
      String key = fn.signature() + "|" + ins.line + "|259|passwordAuth";
      if (!reported.add(key)) return;
      reportSink(ins, fn, ctx, pwd, sourceLiteral, "new PasswordAuthentication");
      return;
    }
  }

  /**
   * Resolve the password slot to its source literal, if any. Handles three cases:
   *
   * <ol>
   *   <li><b>Inline literal</b>: the slot is a {@link TacOperand.Const} STRING (no MOVE).
   *   <li><b>Literal-sourced var</b>: the slot is a var that was last assigned from a literal (MOVE
   *       pattern).
   *   <li><b>Derived-of-literal var</b>: the slot is a var that was last assigned from {@code
   *       toCharArray()} or {@code getBytes(...)} on a literal-sourced var.
   * </ol>
   *
   * Returns {@code null} if the slot is not derived from a literal (e.g. a parameter, a runtime
   * read, or a non-string constant).
   */
  private String resolvePwdLiteral(TacOperand pwd) {
    // Delegate to the shared
    // LiteralSourcedTracker. Same 3-char
    // minimum length as before.
    String literal = tracker.resolveLiteral(pwd, 3);
    if (literal == null) return null;
    // Entropy-based FP suppression.
    // A literal like "password" or "test" is
    // not a real password; suppressing it
    // here drops obvious FPs on real codebases
    // without affecting recall on Juliet
    // test cases (which use 8-char random
    // strings like "7e5tc4s3"). Short
    // literals (< 6 chars) bypass the
    // entropy check because entropy is
    // unreliable for very short strings.
    if (EntropyDetector.isLikelyPlaceholder(literal)) {
      return null;
    }
    return literal;
  }

  /**
   * R-812 sink-emit helper. Builds a v2 sink node parented to the password-slot var's v2 handle
   * (the literal MOVE that flowed into the sink), records it on the per- function builder, and
   * reports the finding with the v2 trace attached.
   *
   * <p>For the inline-literal case (the slot is a {@link TacOperand.Const} STRING, not a var) we
   * use a fresh orphan {@code MARK_SOURCE} so the AI dedup layer still sees the literal as the
   * trace origin.
   */
  private void reportSink(
      TacInstruction ins,
      TacFunction fn,
      AnalysisContext ctx,
      TacOperand pwd,
      String sourceLiteral,
      String sinkFamily) {
    // Compute parent handle.
    Integer parent = null;
    String inlineLiteral = null;
    if (pwd instanceof TacOperand.TacVar tv) {
      parent = v2HandleByVar.get(tv);
    } else if (pwd instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING) {
      inlineLiteral = c.value() instanceof String s ? s : "";
    }
    // Build the sink node + optional inline
    // source handle.
    java.util.List<Integer> parents;
    java.util.List<OperandRef> sinkOps = new java.util.ArrayList<>(2);
    sinkOps.add(new OperandRef.CallRef(sinkFamily));
    if (pwd instanceof TacOperand.TacVar tv) {
      sinkOps.add(new OperandRef.Var(tv.name(), 0));
    }
    if (inlineLiteral != null) {
      int inlineSrc =
          v2.addSource(
              TraceNode.Op.MARK_SOURCE,
              List.of(
                  new OperandRef.ConstStr(
                      inlineLiteral.length() > 32
                          ? inlineLiteral.substring(0, 32) + "..."
                          : inlineLiteral)),
              "inline literal at sink call site",
              "",
              ins.line,
              ins.column,
              fn.signature());
      parents = java.util.List.of(inlineSrc);
    } else {
      parents = parent == null ? java.util.List.of() : java.util.List.of(parent);
    }
    int sinkHandle =
        v2.addSink(
            TraceNode.Op.SINK_SQL_INJECTION, // closest credential-sink enum
            sinkOps,
            parents,
            sinkFamily + " sink with literal password",
            "",
            ins.line,
            ins.column,
            fn.signature());
    sinkHandles.add(sinkHandle);
    Trace trace = v2.build(sinkHandles, Trace.CompressionLevel.FULL);
    String display = truncateForReport(sourceLiteral);
    EntropyDetector.Confidence conf = EntropyDetector.classify(sourceLiteral);
    double entropy = EntropyDetector.shannonBitsPerChar(sourceLiteral);
    Finding f =
        Finding.dfaWithTrace(
            name(),
            RULE_ID,
            "Hard-coded password: "
                + sinkFamily
                + " called with "
                + "a literal string of length "
                + sourceLiteral.length()
                + " as the password (literal: \""
                + display
                + "\", confidence: "
                + conf
                + ")",
            RuleDef.Severity.HIGH,
            "CWE-259",
            "",
            ins.line,
            ins.column,
            "hard-coded-pwd: "
                + sinkFamily
                + "(<...>, <literal len="
                + sourceLiteral.length()
                + ">, entropy="
                + String.format("%.2f", entropy)
                + ")",
            trace);
    ctx.reporter().reportWithTrace(f, trace);
  }

  /**
   * Truncate the literal for safe reporting. Some Juliet CWE-259 patterns use 8-char random strings
   * ("7e5tc4s3"), but real-world keys/passwords may be long. We cap at 32 chars and add "…" to
   * signal truncation, so the finding remains useful without dumping sensitive material into a CI
   * log.
   */
  private static String truncateForReport(String s) {
    if (s == null) return "";
    if (s.length() <= 32) return s;
    return s.substring(0, 32) + "…";
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static final String AI_PROMPT =
      """
        RULE: Hard-coded password in credential sink (CWE-259).
        This rule fires when a credential sink is called with a
        string literal as the password (3 forms caught):
          (1) DriverManager.getConnection(url, user, "literal")
              — including the inline-literal form
          (2) new KerberosKey(principal, "literal".toCharArray(), null)
          (3) new PasswordAuthentication("user", "literal".toCharArray())

        Each finding carries a confidence bucket based on
        Shannon entropy of the literal:
          - HIGH (> 4.0 bits/char): looks like a real random
            password (e.g. "7e5tc4s3"). Almost always TP.
          - MEDIUM (3.0 - 4.0 bits/char): possibly a password.
            Verify against the codebase context.
          - LOW (< 3.0 bits/char, length >= 6): usually a
            placeholder, but kept for completeness. Often FP.

        A finding is a TRUE POSITIVE (TP) when the literal is a
        real-looking password in production code. A finding is a
        FALSE POSITIVE (FP) when the literal is a placeholder
        ("password", "root", "test", "sa"), a JCA test vector,
        or in a test fixture. UNKNOWN when ambiguous.
""";

  @Override
  public String getAiPrompt(org.plumb.reporter.Finding finding) {
    return AI_PROMPT;
  }
}
