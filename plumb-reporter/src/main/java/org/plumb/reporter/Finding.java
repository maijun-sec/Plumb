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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.plumb.reporter.trace.Trace;
import org.plumb.rules.RuleDef;

/**
 * One issue produced by a checker. Canonical home for the unified finding model: file + line +
 * severity rule + message + optional flow trace.
 *
 * <p><b> unification</b>: the legacy {@code org.plumb.dfa.engine.Finding} type was deleted (moved
 * here). DFA checkers now construct findings via the {@link #dfa(String, String, String, String,
 * int, int, org.plumb.rules.RuleDef.Severity, String, String, java.util.List) dfa(...)} factory
 * which carries the DFA-only fields {@code checkerName / ruleId / cwe / flow}. The reporter-only
 * fields {@code rule(RuleDef) / dataflowHead / sinkFunction / aiVerdict / aiReason} stay on the
 * canonical type. Both styles share the same {@link #dataflowSteps} list (now always {@code
 * List<FlowStep>}).
 *
 * <p>When a {@link RuleDef} is available (style checkers / post-DFA enrichment in {@code
 * ScanRunner}), it carries the canonical rule identity for downstream reporters (NDJSON, AI dedup).
 * When a finding is emitted by a DFA checker that does not own a RuleDef, the {@link #rule} field
 * is null and reporters fall back to {@link #ruleId}/{@link #checkerName} when emitting.
 */
public final class Finding {
  // ---- canonical location / severity / message ----
  private final Path file;
  private final int line;
  private final int column;

  // ---- rule identity (two supported flavors) ----
  /**
   * Canonical {@link RuleDef}, when available (style checkers, post-DFA enrichment). {@code null}
   * when the producer is a DFA checker that does not own a RuleDef -- reporters fall back to {@link
   * #ruleId}/{@link #checkerName}.
   */
  private final RuleDef rule;

  /**
   * DFA-only rule id (e.g. {@code "integer-overflow"}). Equivalent to {@code rule().ruleId().id()}
   * when {@link #rule} is non-null. Kept on the canonical type so DFA checkers don't have to fake a
   * RuleDef.
   */
  private final String ruleId;

  /**
   * DFA-only checker name (e.g. {@code "IntUnderflowChecker"}). Equivalent to {@code
   * rule().checkerName()} when {@link #rule} is non-null.
   */
  private final String checkerName;

  private final String message;
  private final RuleDef.Severity severity;

  // ---- DFA-only fields ----
  /**
   * CWE family ({@code "CWE-191"}, {@code "CWE-89"}, ...). {@code null} when not applicable.
   * Carried on the canonical type so the GT-matcher in the eval pipeline can classify findings by
   * CWE without going through RuleDef.
   */
  private final String cwe;

  /**
   * Human-readable single-line flow summary (legacy back-compat field; the structured {@link
   * #dataflowSteps} is the source of truth). Conventionally the {@code expr} of the last {@code
   * "sink"} step.
   */
  private final String flow;

  // ---- reporter fields ----
  /**
   * Legacy back-compat single-string head summary. Conventionally the {@code expr} of the last
   * {@code "sink"} step.
   */
  private final String dataflowHead;

  /**
   * Structured source-to-sink trace (type changed from {@code List<String>} to {@code
   * List<FlowStep>}). Each step is a {@link FlowStep} record carrying the role, file, line, column,
   * function signature, and a free-form {@code expr}. Reporters flatten each step to its canonical
   * {@code "<role> | <file>:<line>:<col> | <expr>"} string via {@link
   * FlowStep#toCanonicalString()}. May be empty for findings whose checker is a simple style /
   * resource check that does not propagate data.
   */
  private final List<FlowStep> dataflowSteps;

  /**
   * Full signature of the sink function (the function IS the signature). Format: {@code
   * "FQN#method(args):ret"}. Examples:
   *
   * <ul>
   *   <li>{@code "com.example.MyClass#bad(String,int):void"}
   *   <li>{@code "Cwe89_SqlInjection__File__executeQuery_01#bad(Connection):void"}
   *   <li>{@code "MyClass#action(byte):void"}
   * </ul>
   *
   * {@code null} if the checker did not record a flow step. Empty string if unknown. The NDJSON
   * emitter writes this as {@code "sink_function"} and the AI dedup pass uses it to scope the
   * source context to just the relevant method.
   */
  private final String sinkFunction;

  /**
   * P3 PATCH: optional v2 structured trace. {@code null} for findings that have not been
   * dual-written (the v1 {@code dataflowSteps} list remains the source of truth and reporters fall
   * back to the legacy flat-path rendering). Threaded through {@code withFilePath} / {@code
   * withRuleId} / {@code withAiVerdict} so the side-channel trace survives the reporter's withX
   * rewrites. (Future P3 PATCH may fold this into the record itself; the explicit field is the
   * minimum-invasive shape that keeps the v1 record API unchanged for tests that don't care about
   * v2.)
   */
  private final Trace trace;

  /**
   * LLM-assigned verdict ({@code "TP"} / {@code "FP"} / {@code "UNKNOWN"}). Set by the AI dedup
   * pass when {@code --ai-dedup} is enabled; {@code null} when AI dedup was not run.
   */
  private final String aiVerdict;

  /** One-sentence justification from the LLM. */
  private final String aiReason;

  // ===================================================================
  //  Constructors — style-check / RuleDef-backed path
  // ===================================================================

  /**
   * pull the first CWE id from a {@link RuleDef}'s CWE list, so AST findings carry the same CWE tag
   * that the rule definition declares. Returns null when the rule is null or its CWE list is empty
   * / null. The Finding CWE field is single-valued (one CWE per Finding); when a rule covers
   * multiple CWEs the downstream eval heuristic keys on the first.
   */
  private static String firstCwe(RuleDef rule) {
    if (rule == null) return null;
    java.util.List<String> cwes = rule.cwe();
    if (cwes == null || cwes.isEmpty()) return null;
    return cwes.get(0);
  }

  public Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead) {
    this(
        file,
        line,
        column,
        rule,
        /* ruleId */ (rule == null ? null : rule.ruleId().id()),
        /* checkerName */ null,
        message,
        severity,
        /* cwe */ firstCwe(rule), /* flow */
        null,
        dataflowHead,
        List.<FlowStep>of(),
        /* sinkFunction */ null,
        /* aiVerdict */ null,
        /* aiReason */ null,
        /* trace */ null);
  }

  public Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead,
      List<FlowStep> dataflowSteps) {
    this(
        file,
        line,
        column,
        rule,
        (rule == null ? null : rule.ruleId().id()),
        null,
        message,
        severity,
        firstCwe(rule),
        null,
        dataflowHead,
        dataflowSteps,
        null,
        null,
        null,
        null);
  }

  public Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead,
      List<FlowStep> dataflowSteps,
      String aiVerdict,
      String aiReason) {
    this(
        file,
        line,
        column,
        rule,
        (rule == null ? null : rule.ruleId().id()),
        null,
        message,
        severity,
        firstCwe(rule),
        null,
        dataflowHead,
        dataflowSteps,
        null,
        aiVerdict,
        aiReason,
        null);
  }

  /**
   * Full constructor for style / RuleDef-backed findings. The RuleDef carries the canonical
   * identity; the DFA-only fields {@code ruleId/checkerName/cwe/flow} are null.
   */
  public Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead,
      List<FlowStep> dataflowSteps,
      String aiVerdict,
      String aiReason,
      String sinkFunction) {
    this(
        file,
        line,
        column,
        rule,
        message,
        severity,
        dataflowHead,
        dataflowSteps,
        aiVerdict,
        aiReason,
        sinkFunction,
        null);
  }

  /**
   * P3 PATCH: same as the 11-arg constructor but with an optional v2 {@link Trace} attached. Used
   * by {@code ScanRunner.convertUnifiedFinding} to thread the post-rewrite trace from a DFA finding
   * through to the reporter pipeline (NdjsonEmitter, SARIF v2 extension). The {@code trace}
   * parameter is null for findings that have not been dual-written.
   */
  public Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String message,
      RuleDef.Severity severity,
      String dataflowHead,
      List<FlowStep> dataflowSteps,
      String aiVerdict,
      String aiReason,
      String sinkFunction,
      Trace trace) {
    this(
        file,
        line,
        column,
        /* rule */ rule,
        /* ruleId */ (rule == null ? null : rule.ruleId().id()),
        /* checkerName */ (rule == null ? null : rule.checkerClass()),
        message,
        severity,
        /* cwe */ firstCwe(rule), /* flow */
        null,
        dataflowHead,
        dataflowSteps,
        sinkFunction,
        aiVerdict,
        aiReason,
        trace);
  }

  // ===================================================================
  //  DFA-only constructor — RuleDef-less path
  // ===================================================================

  /**
   * DFA-only constructor. DFA checkers emit findings that carry a {@code checkerName / ruleId / cwe
   * / flow} identity but no {@link RuleDef} (RuleDef is only constructed at the {@code
   * ScanRunner.convertUnifiedFinding} stage where the {@link org.plumb.rules.RuleRegistry} is
   * available). The {@code File} is passed as a string path because DFA checkers don't always have
   * a {@link Path} (they get the file path from the engine via {@code withFilePath} later).
   *
   * <p>{@link #rule} is null on the resulting instance; {@link #ruleId}, {@link #checkerName},
   * {@link #cwe}, and {@link #flow} are populated. Reporters that need a RuleDef fall back to
   * {@code ruleId / checkerName} when serializing.
   */
  public static Finding dfa(
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String filePath,
      int line,
      int column,
      String flow,
      List<FlowStep> flowSteps) {
    // Preserve "empty filePath" as the empty string,
    // not "unknown". The DFA-side FindingReporter
    // (org.plumb.dfa.engine.FindingReporter) checks
    // filePath() for emptiness to decide whether to
    // enrich with ctx.sourcePathForEnclosingClass(...).
    // Writing "unknown" here short-circuits that
    // enrichment and leaves the finding with file=""
    // in NDJSON. By keeping the filePath as "" we
    // let FindingReporter fill it in via withFilePath.
    Path file = (filePath == null) ? null : (filePath.isEmpty() ? Path.of("") : Path.of(filePath));
    return new Finding(
        file,
        line,
        column,
        /* rule */ null,
        /* ruleId */ ruleId,
        /* checkerName */ checkerName,
        message,
        severity,
        cwe,
        flow,
        /* dataflowHead */ flow,
        flowSteps,
        /* sinkFunction */ null,
        /* aiVerdict */ null,
        /* aiReason */ null,
        /* trace */ null);
  }

  /**
   * DFA convenience factory without an explicit {@link FlowStep} list. Use this when the checker
   * emits a finding but does not track a structured trace (typically when the legacy DFA Finding
   * constructor was called with only 9 positional args). Equivalent to {@code dfa(...)} with {@code
   * flowSteps = List.of()}.
   */
  public static Finding dfa(
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String filePath,
      int line,
      int column,
      String flow) {
    return dfa(
        checkerName, ruleId, message, severity, cwe, filePath, line, column, flow, List.of());
  }

  /**
   * 10-arg overload that takes a {@code sinkFunction} string (e.g. {@code "bad():void"}). Used by
   * DFA checkers that can resolve the enclosing method name (e.g. via {@code
   * StyleContext.findEnclosingMethod} semantics) so the eval harness can classify the finding by
   * Juliet's bad/good* method heuristic.
   *
   * <p>{@code sinkFunction} may be null (falls back to the 9-arg behaviour). When non-null, the
   * downstream JSON writer emits it under the {@code sink_function} field.
   */
  public static Finding dfa(
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String filePath,
      int line,
      int column,
      String flow,
      String sinkFunction) {
    // The 11-arg ctor (with Trace) accepts sinkFunction.
    // For the simple 10-arg case, go through the existing
    // 9-arg then set sinkFunction via the package-private
    // path. Easiest: route to the rich 11-arg ctor by
    // passing trace=null.
    // Use the 12-arg rich ctor: file, line, column, rule, ruleId,
    // checkerName, message, severity, cwe, flow, dataflowHead,
    // dataflowSteps, sinkFunction, aiVerdict, aiReason, trace.
    // But we don't have a RuleDef here, so build a minimal
    // one from ruleId+checkerName.
    org.plumb.rules.RuleId rid = new org.plumb.rules.RuleId(ruleId, checkerName);
    org.plumb.rules.RuleDef rule =
        new org.plumb.rules.RuleDef(
            rid,
            message,
            severity == null ? RuleDef.Severity.INFO : severity,
            org.plumb.rules.RuleDef.Type.BUG,
            java.util.List.of(cwe),
            checkerName,
            true);
    return new Finding(
        java.nio.file.Paths.get(filePath == null ? "" : filePath),
        line,
        column,
        rule,
        message,
        severity == null ? RuleDef.Severity.INFO : severity,
        /* dataflowHead */ flow,
        /* dataflowSteps */ List.<FlowStep>of(),
        /* aiVerdict */ null,
        /* aiReason */ null,
        sinkFunction);
  }

  /**
   * DFA-only convenience factory for a single-step "violation" finding (no full trace, just one
   * FlowStep at the violation site). Used by structural checkers that don't propagate data — every
   * finding still has a non-empty {@link #dataflowSteps} list, so the trace renderer can show
   * "violation at this line".
   *
   * <p>Returns a {@link Finding} with a single {@link FlowStep} of role {@code "violation"} at the
   * given line / column / function signature, with {@code expr} equal to the {@code flow} argument.
   * The {@link #flow()} / {@link #dataflowHead()} fields are both set to {@code flow} for
   * back-compat with reporters that haven't migrated to structured steps.
   */
  public static Finding violation(
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      int line,
      int column,
      String functionSig,
      String flow) {
    // Preserve empty filePath so the DFA-side
    // FindingReporter can enrich via
    // ctx.sourcePathForEnclosingClass(...). The
    // pre- factory wrote Path.of("unknown") here
    // which short-circuited the enrichment gate (same
    // Lesson 1138 as Finding.dfa in ). Finding.violation
    // is the legacy 9-arg path used by structural checkers
    // that do not know the source path at construction time
    // (UncheckedReturnChecker, InsecureCommunicationChecker,
    // CatchGenericExceptionChecker, etc.); they receive
    // the filePath via FindingReporter.withFilePath after
    // report() resolves the current function's enclosing
    // class.
    List<FlowStep> steps =
        List.of(
            new FlowStep(
                "violation", "", line, column, functionSig == null ? "" : functionSig, flow));
    return new Finding(
        Path.of(""),
        line,
        column,
        /* rule */ null,
        /* ruleId */ ruleId,
        /* checkerName */ checkerName,
        message,
        severity,
        cwe,
        flow,
        /* dataflowHead */ flow,
        java.util.Collections.unmodifiableList(steps),
        /* sinkFunction */ null,
        /* aiVerdict */ null,
        /* aiReason */ null,
        /* trace */ null);
  }

  // ===================================================================
  // — v2 structured-trace factory
  // ===================================================================

  /**
   * v2-aware DFA factory. The caller passes a {@link org.plumb.reporter.trace.Trace} (a structured
   * DAG of {@link org.plumb.reporter.trace.TraceNode}s) and an explicit {@link
   * org.plumb.reporter.trace.Trace.CompressionLevel}; the factory flattens the trace to a {@code
   * List<FlowStep>} via {@link org.plumb.reporter.trace.Trace#toFlowSteps()} and stores it in the
   * same {@link #dataflowSteps} field v1 checkers populate. This means the v1 reporter pipeline
   * (NDJSON, SARIF, console) is unchanged -- the {@code dataflow_steps} array produced from a v2
   * trace is byte-identical in shape to the v1 {@code dataflow_steps} array.
   *
   * <p>The trade-off is that v2 traces are flattened to {@code FlowStep}s at construction time. The
   * full DAG is <em>not</em> retained on the {@code Finding} -- callers that need the structured
   * trace (e.g. SARIF 2.1 codeFlows with a threadflow JSON tree) must keep their own reference.
   * This is intentional for P1 -- the P3 / round will revisit {@link org.plumb.reporter.Finding} to
   * persist the {@code Trace} when the v2 reporter lands.
   *
   * <p>Like {@link #dfa}, {@code filePath} is preserved as the empty string (not {@code "unknown"})
   * so the DFA-side {@code FindingReporter} can enrich via {@code
   * ctx.sourcePathForEnclosingClass(...)}. See the comment on {@link #dfa} for the reasoning.
   */
  public static Finding dfaWithTrace(
      String checkerName,
      String ruleId,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String filePath,
      int line,
      int column,
      String flow,
      org.plumb.reporter.trace.Trace trace) {
    // Flatten the v2 trace to v1 FlowSteps using the
    // trace's chosen compression level. This is the
    // ONE spot in the codebase where a Trace meets
    // the legacy FlowStep pipeline; all downstream
    // consumers see the flattened list.
    List<FlowStep> steps = (trace == null) ? List.of() : trace.toFlowSteps();
    Path file = (filePath == null) ? null : (filePath.isEmpty() ? Path.of("") : Path.of(filePath));
    return new Finding(
        file,
        line,
        column,
        /* rule */ null,
        /* ruleId */ ruleId,
        /* checkerName */ checkerName,
        message,
        severity,
        cwe,
        flow,
        /* dataflowHead */ flow,
        steps,
        /* sinkFunction */ null,
        /* aiVerdict */ null,
        /* aiReason */ null,
        /* trace */ trace);
  }

  // ===================================================================
  //  Private full constructor (used by both paths)
  // ===================================================================

  private Finding(
      Path file,
      int line,
      int column,
      RuleDef rule,
      String ruleId,
      String checkerName,
      String message,
      RuleDef.Severity severity,
      String cwe,
      String flow,
      String dataflowHead,
      List<FlowStep> dataflowSteps,
      String sinkFunction,
      String aiVerdict,
      String aiReason,
      Trace trace) {
    this.file = Objects.requireNonNull(file);
    this.line = line;
    this.column = column;
    this.rule = rule;
    this.ruleId = ruleId;
    this.checkerName = checkerName;
    this.message = Objects.requireNonNull(message);
    this.severity = severity == null ? RuleDef.Severity.INFO : severity;
    this.cwe = cwe;
    this.flow = flow == null ? "" : flow;
    this.dataflowHead = dataflowHead;
    this.dataflowSteps =
        dataflowSteps == null
            ? List.<FlowStep>of()
            : Collections.unmodifiableList(new ArrayList<>(dataflowSteps));
    this.aiVerdict = aiVerdict;
    this.aiReason = aiReason;
    this.sinkFunction = sinkFunction;
    this.trace = trace;
  }

  // ===================================================================
  //  Accessors — location / severity / message
  // ===================================================================

  public Path file() {
    return file;
  }

  /**
   * alias for {@link #file()} returning the path as a string. Kept for source-compat with tests
   * that used the legacy DFA Finding API (which exposed {@code filePath()} as a String).
   */
  public String filePath() {
    return file == null ? "" : file.toString();
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  public RuleDef rule() {
    return rule;
  }

  public String message() {
    return message;
  }

  public RuleDef.Severity severity() {
    return severity;
  }

  /**
   * DFA-only rule id (e.g. {@code "integer-overflow"}). Equivalent to {@code rule().ruleId().id()}
   * when {@link #rule} is non-null.
   */
  public String ruleId() {
    return ruleId;
  }

  /** DFA-only checker name. */
  public String checkerName() {
    return checkerName;
  }

  /** CWE family ({@code "CWE-191"}, ...). {@code null} when not applicable. */
  public String cwe() {
    return cwe;
  }

  /** Legacy back-compat single-line flow summary. */
  public String flow() {
    return flow;
  }

  /** Legacy single-string head summary (back-compat). */
  public String dataflowHead() {
    return dataflowHead;
  }

  /** Structured source-to-sink trace. */
  public List<FlowStep> dataflowSteps() {
    return dataflowSteps;
  }

  /**
   * alias for {@link #dataflowSteps()} -- kept for source-compat with tests that used the legacy
   * DFA Finding API.
   */
  public List<FlowStep> flowSteps() {
    return dataflowSteps;
  }

  public String aiVerdict() {
    return aiVerdict;
  }

  public String aiReason() {
    return aiReason;
  }

  /**
   * Full signature of the sink function (the function IS the signature), e.g. {@code
   * "com.example.MyClass#bad(String,int):void"}. Format: {@code "FQN#method(args):ret"}. {@code
   * null} if unknown. Consumers that need just the method name can split at {@code '#'} and strip
   * from {@code '('} onward.
   */
  public String sinkFunction() {
    return sinkFunction;
  }

  /**
   * P3 PATCH: optional v2 structured trace (DAG of {@code TraceNode}s). Returns {@code null} for
   * findings that have not been dual-written. Consumers (NdjsonEmitter, SARIF v2 extension) call
   * this to emit the {@code v2_trace} field; v1-only consumers ignore it and fall back to {@code
   * dataflowSteps}.
   */
  public Trace trace() {
    return trace;
  }

  /**
   * P3 PATCH: return a copy with the given v2 trace attached. Used by {@code
   * FindingReporter.reportWithTrace} to fold the trace into the post-rewrite Finding record
   * (eliminating the side-channel IdentityHashMap lookup that P2 had to do with thread-local
   * handoff). Idempotent: passing the same {@code trace} returns {@code this}.
   */
  public Finding withTrace(Trace newTrace) {
    if (java.util.Objects.equals(newTrace, trace)) return this;
    return new Finding(
        file,
        line,
        column,
        rule,
        ruleId,
        checkerName,
        message,
        severity,
        cwe,
        flow,
        dataflowHead,
        dataflowSteps,
        sinkFunction,
        aiVerdict,
        aiReason,
        newTrace);
  }

  // ===================================================================
  //  Mutators — return a copy with one field changed
  // ===================================================================

  /**
   * Return a new Finding identical to this one but with {@link #file} set to {@code newPath}. Also
   * rewrites every flow step's filePath so the trace stays consistent. Used by {@code
   * FindingReporter} to fill in the path for findings whose checker left it empty.
   */
  public Finding withFilePath(String newPath) {
    if (newPath == null || newPath.isEmpty()) return this;
    Path newFile = Path.of(newPath);
    if (newFile.equals(file)) return this;
    List<FlowStep> rewritten = new ArrayList<>(dataflowSteps.size());
    for (FlowStep s : dataflowSteps) {
      rewritten.add(
          new FlowStep(s.role(), newPath, s.line(), s.column(), s.functionSig(), s.expr()));
    }
    return new Finding(
        newFile,
        line,
        column,
        rule,
        ruleId,
        checkerName,
        message,
        severity,
        cwe,
        flow,
        dataflowHead,
        rewritten,
        sinkFunction,
        aiVerdict,
        aiReason,
        trace);
  }

  /**
   * Return a copy with the given ruleId. Used by {@code FindingReporter} for legacy-id →
   * canonical-id alias resolution. Does NOT rewrite FlowStep entries (FlowStep records carry the
   * rule name but not the rule id).
   */
  public Finding withRuleId(String newRuleId) {
    if (newRuleId == null || newRuleId.equals(ruleId)) return this;
    return new Finding(
        file,
        line,
        column,
        rule,
        newRuleId,
        checkerName,
        message,
        severity,
        cwe,
        flow,
        dataflowHead,
        dataflowSteps,
        sinkFunction,
        aiVerdict,
        aiReason,
        trace);
  }

  /** Return a copy with the given AI verdict + reason. */
  public Finding withAiVerdict(String verdict, String reason) {
    if (java.util.Objects.equals(verdict, aiVerdict) && java.util.Objects.equals(reason, aiReason))
      return this;
    return new Finding(
        file,
        line,
        column,
        rule,
        ruleId,
        checkerName,
        message,
        severity,
        cwe,
        flow,
        dataflowHead,
        dataflowSteps,
        sinkFunction,
        verdict,
        reason,
        trace);
  }

  @Override
  public String toString() {
    return file
        + ":"
        + line
        + ":"
        + column
        + "  "
        + (ruleId != null ? ruleId : (rule != null ? rule.ruleId().name() : "?"))
        + "  "
        + severity
        + "  "
        + message
        + (dataflowHead == null ? "" : "  [flow: " + dataflowHead + "]")
        + (sinkFunction == null ? "" : "  [in: " + sinkFunction + "]")
        + (dataflowSteps.isEmpty() ? "" : "  [steps: " + dataflowSteps.size() + "]");
  }
}
