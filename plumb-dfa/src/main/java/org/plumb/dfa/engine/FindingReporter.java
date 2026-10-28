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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.plumb.reporter.Finding;
import org.plumb.reporter.trace.Trace;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleRegistry;
import org.plumb.tac.ir.TacFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects findings emitted by checkers.
 *
 * <p>Thread-safe: checkers may run in parallel. Findings are deduplicated by (file, line, ruleId,
 * cwe) so a checker that fires twice on the same location doesn't double-report.
 */
public final class FindingReporter {

  private static final Logger LOG = LoggerFactory.getLogger(FindingReporter.class);

  private final List<Finding> findings = new ArrayList<>();
  private final java.util.Set<String> seen = new java.util.HashSet<>();

  /**
   * P2: optional v2 {@link Trace} cache. When a checker attaches a v2 trace to a finding via {@link
   * #attachV2Trace(Finding, Trace)}, it is stored here keyed by the (post-rewrite) Finding
   * identity. v1 reporters (NDJSON, SARIF, console) continue to consume the flattened {@code
   * dataflowSteps} list on the Finding itself; v2-aware consumers (R-PATCH v3+ AI dedup, SARIF with
   * the {@code v2Trace} extension, future Plumb-UI trace viewer) read from this cache. The cache
   * uses identity equality (Finding is a record but v1 reporters rewrite it via {@code
   * withFilePath} / {@code withRuleId}, so the post-rewrite Finding is a different instance).
   */
  private final Map<Finding, Trace> v2Traces = new IdentityHashMap<>();

  /**
   * R-PATCH v2: optional RuleRegistry for alias resolution. When set, {@link #report(Finding)}
   * looks up the finding's rule_id in {@code byAlias} and substitutes the canonical {@link RuleDef}
   * before recording. Default null (legacy behavior).
   */
  private volatile RuleRegistry ruleRegistry;

  /**
   * Back-reference to the analysis context. Used to resolve {@code filePath} for findings whose
   * checker left it empty (the new DFA checkers in plumb-dfa don't carry the path; the IR doesn't
   * either). When a finding is reported and its filePath is "", we ask the context for the function
   * currently being analysed and use that function's enclosing class to look up the file path in
   * the per-run source-path map.
   *
   * <p>Mutable (not {@code final}) because of the chicken-and-egg between AnalysisContext (which
   * takes a reporter in its constructor) and FindingReporter (which needs the context). The engine
   * creates a context-less reporter first, then the AnalysisContext, then calls {@link #setContext}
   * to wire the back-reference.
   */
  private volatile AnalysisContext ctx;

  public FindingReporter(AnalysisContext ctx) {
    this.ctx = ctx;
  }

  /**
   * Legacy constructor. Use {@link #FindingReporter(AnalysisContext)} when an AnalysisContext is
   * available -- the filePath-filling pass needs it. The legacy constructor creates a context-less
   * reporter (filePath stays empty for all findings).
   */
  public FindingReporter() {
    this(null);
  }

  /**
   * Set the back-reference to the AnalysisContext after construction. The engine calls this once,
   * immediately after creating the context, to enable the filePath-filling pass.
   */
  public void setContext(AnalysisContext ctx) {
    this.ctx = ctx;
  }

  /**
   * R-PATCH v2: set the RuleRegistry used for alias resolution. When set, {@link #report(Finding)}
   * substitutes the canonical {@link RuleDef} for any finding whose rule_id matches an alias.
   * Optional; default null (legacy behavior).
   */
  public void setRuleRegistry(RuleRegistry registry) {
    this.ruleRegistry = registry;
  }

  public synchronized void report(Finding f) {
    Finding ff = f;
    // Fill in filePath from the current
    // function's source when the checker
    // left it empty. The checkers don't
    // carry the path themselves because the
    // IR doesn't either; the engine
    // pre-populates a per-enclosing-class
    // source-path map via
    // AnalysisContext.registerSourcePath,
    // and FindingReporter consults it on
    // every report. The current function is
    // set by DfaEngine at the start of each
    // function's analysis (and by checkers
    // that emit from finish(), which set it
    // again right before emit).
    if (ctx != null && (ff.filePath() == null || ff.filePath().isEmpty())) {
      TacFunction fn = ctx.currentFunction();
      if (fn != null) {
        String resolved = ctx.sourcePathForEnclosingClass(fn.enclosingClass());
        if (resolved != null && !resolved.isEmpty()) {
          ff = ff.withFilePath(resolved);
        } else {
          // Last-resort fallback. If the
          // enclosingClass lookup fails (e.g. for
          // synthesized functions whose enclosing class
          // wasn't registered in the path map), fall
          // back to the program-level source path. For
          // single-file scans this is the .java file;
          // for the merged cross-TU scan the synthetic
          // {@code MergedProgram.asProgram()} has a
          // null source path so the fallback resolves
          // to nothing and the finding keeps the empty
          // filePath — that case is unchanged.
          org.plumb.tac.ir.TacProgram p = ctx.program();
          if (p != null && p.sourcePath() != null) {
            String progPath = p.sourcePath().toString();
            if (progPath != null && !progPath.isEmpty()) {
              ff = ff.withFilePath(progPath);
            }
          }
        }
      }
    }
    // R-PATCH v2: alias resolution. If a RuleRegistry is set and the
    // finding's rule_id is a legacy id with a canonical entry, swap
    // the ruleId. We do this by constructing a new Finding (Finding
    // is immutable in this codebase, see Finding class).
    if (ruleRegistry != null) {
      String id = ff.ruleId();
      if (id != null && ruleRegistry.hasAlias(id)) {
        var byAlias = ruleRegistry.byAlias(id);
        if (byAlias.isPresent()) {
          RuleDef canonical = byAlias.get();
          // Finding doesn't expose ruleId-setter; we use withRuleId
          // (new in v2). If not available, fall back to setting via
          // full constructor.
          try {
            ff = ff.withRuleId(canonical.ruleId().id());
          } catch (NoSuchMethodError ex) {
            // Finding v1 (pre-v2) doesn't have withRuleId; leave
            // the finding as-is. The reporter still de-dups by the
            // legacy ruleId, so subsequent evaluations will see the
            // legacy id but the eval pipeline's alias map can resolve.
            if (LOG.isTraceEnabled()) {
              LOG.trace("Finding lacks withRuleId; legacy id {} not rewritten", id);
            }
          }
        }
      }
    }
    String key = ff.filePath() + ":" + ff.line() + ":" + ff.ruleId() + ":" + ff.cwe();
    if (!seen.add(key)) {
      // Dedup hit — the v1 path has already
      // accepted an identical finding. If a v2
      // trace was pending, attach it to the
      // already-stored one (it has the same
      // content) so the dual-write still
      // surfaces. Look up by content match
      // (file/line/rule/cwe == ff's) and re-attach
      // if a v2 entry is missing.
      Trace pending = v2TraceHandoff.get();
      if (pending != null) {
        for (int i = 0; i < findings.size(); i++) {
          Finding existing = findings.get(i);
          if (existing.filePath().equals(ff.filePath())
              && existing.line() == ff.line()
              && existing.ruleId().equals(ff.ruleId())
              && Objects.equals(existing.cwe(), ff.cwe())) {
            if (existing.trace() == null) {
              // P3 PATCH: thread the trace
              // into the existing finding
              // record via withTrace so
              // f.trace() is the canonical
              // accessor. List<Finding> is
              // mutable and the dedup-hit
              // caller doesn't see ff, so
              // we replace the existing
              // finding in-place.
              findings.set(i, existing.withTrace(pending));
            }
            v2Traces.put(existing, pending);
            break;
          }
        }
      }
      return;
    }
    // P2: if a v2 trace was stashed by
    // reportWithTrace, attach it to the
    // post-rewrite finding instance (ff, not the
    // pre-rewrite input). This is the
    // v2-aware consumer's lookup key.
    Trace pending = v2TraceHandoff.get();
    if (pending != null) {
      // P3 PATCH: thread the trace through the
      // Finding record itself (via withTrace)
      // so any consumer of ff (NdjsonEmitter,
      // SARIF v2 extension, AI dedup tool) can
      // call ff.trace() without going through
      // the v2Traces side-channel. Side-channel
      // stays as a back-compat fallback for code
      // that hasn't migrated to withTrace yet.
      ff = ff.withTrace(pending);
    }
    findings.add(ff);
    // v2Traces side-channel: kept for any consumer
    // that hasn't migrated to ff.trace() yet.
    if (pending != null) {
      v2Traces.put(ff, pending);
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Finding: {}", ff);
    }
  }

  /**
   * P2: attach a v2 {@link Trace} to a {@link Finding}. The trace is cached under the finding's
   * identity (IdentityHashMap) and made available via {@link #traceFor(Finding)} for v2-aware
   * consumers.
   *
   * <p>Call order: invoke <em>after</em> the finding has been reported (the cache key is the
   * finding instance passed in; if v1's {@link #report(Finding)} rewrote the finding via {@code
   * withFilePath} / {@code withRuleId}, the rewrite produced a new Finding instance and the v1
   * {@code final} seen by the reporter is what was actually added to {@link #all()}). The
   * TaintedCheckerBase emit path passes the post-rewrite Finding by re-calling {@link
   * #traceFor(Finding)} after {@link #report(Finding)} returns -- but the cleaner pattern (see
   * R-PATCH v3) is to thread the original Finding through {@link #reportWithTrace(Finding, Trace)}.
   *
   * <p>If {@code trace} is {@code null}, the existing cache entry (if any) for this finding is
   * removed -- the v1 path remains the source of truth and v2 is silent for this finding.
   */
  public synchronized void attachV2Trace(Finding finding, Trace trace) {
    if (finding == null) return;
    if (trace == null) {
      v2Traces.remove(finding);
    } else {
      v2Traces.put(finding, trace);
    }
  }

  /**
   * P2: read the v2 {@link Trace} previously attached via {@link #attachV2Trace(Finding, Trace)}.
   * Returns {@code null} when no v2 trace was attached (v1-only finding). v2-aware consumers (SARIF
   * with {@code v2Trace} extension, AI dedup tool) call this once per finding they render.
   */
  public synchronized Trace traceFor(Finding finding) {
    if (finding == null) return null;
    return v2Traces.get(finding);
  }

  /**
   * P2: composite v1+v2 report. Equivalent to {@code attachV2Trace(finding, trace);
   * report(finding);} but the v2 trace is attached under the <em>final</em> (post-rewrite) Finding
   * instance, so {@link #traceFor(Finding)} succeeds when called against the same instance returned
   * by {@link #all()}.
   *
   * <p>Use this from v2-aware checkers that build a Trace in parallel with the Finding. v1-only
   * checkers continue to call {@link #report(Finding)} unchanged.
   */
  public synchronized void reportWithTrace(Finding finding, Trace trace) {
    // Buffer the trace on a thread-local handoff
    // so {@link #report(Finding)} can attach it to
    // the post-rewrite finding (ff) rather than the
    // pre-rewrite input (finding). v2-aware consumers
    // (SARIF v2 extension, AI dedup) look up via
    // the Finding instance returned by
    // {@link #all()}, so the v2 cache key must be
    // the post-rewrite instance. {@code report()}
    // consumes the handoff and writes
    // {@code v2Traces.put(ff, trace)} internally;
    // the thread-local is cleared in a finally.
    v2TraceHandoff.set(trace);
    try {
      report(finding);
    } finally {
      v2TraceHandoff.remove();
    }
  }

  /**
   * P2: per-thread handoff for v2 trace so {@link #reportWithTrace(Finding, Trace)} can stash the
   * trace while {@link #report(Finding)} is rewriting the Finding, then re-key on the final
   * instance. {@link ThreadLocal} (not a global field) so parallel checkers don't trample each
   * other -- each thread sees its own handoff.
   */
  private final ThreadLocal<Trace> v2TraceHandoff = new ThreadLocal<>();

  public synchronized List<Finding> all() {
    return new ArrayList<>(findings);
  }

  public synchronized int size() {
    return findings.size();
  }

  public synchronized void clear() {
    findings.clear();
    seen.clear();
    v2Traces.clear();
    v2TraceHandoff.remove();
  }
}
