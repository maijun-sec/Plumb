/*
 * R-802: Trace ? the structured source-to-sink trace
 * attached to a {@link org.plumb.reporter.Finding} for
 * v2-aware checkers.
 *
 * <p>A {@code Trace} holds the full DAG of {@link
 * TraceNode}s plus the canonical {@code rootId} (the
 * SOURCE node) and the {@code sinkOrViolationIds}
 * (one or more SINK / VIOLATION nodes ? a single finding
 * can have multiple sink candidates when the same
 * tainted value reaches two sinks in the same path).
 * Reporters iterate the DAG from {@code rootId} down to
 * {@code sinkOrViolationIds} following {@link
 * TraceNode#parentIds()}.</p>
 *
 * <h2>Compression</h2>
 *
 * <p>The {@link #compressionLevel()} controls how much
 * detail {@link #toFlowSteps()} keeps when flattening
 * back to the v1 {@code List<FlowStep>} for legacy
 * reporters (NDJSON, SARIF, console). The DAG is the
 * canonical store; the flattened view is a derived
 * projection. Compression is reversible ? {@link
 * CompressionLevel#FULL} always emits every node; {@link
 * CompressionLevel#SUMMARIZED} collapses runs of
 * consecutive {@code PROPAGATE}-role nodes longer than
 * {@link #SUMMARY_CHAIN_THRESHOLD} (5) into a single
 * {@code SUMMARIZER}-role node.</p>
 *
 * <h2>Migration note</h2>
 *
 * <p>During the P2 / P4 migration window, v1 checkers
 * continue to emit {@code List<FlowStep>} directly on
 * the {@link org.plumb.reporter.Finding}. v2 checkers
 * build a {@code Trace} and call
 * {@link org.plumb.reporter.Finding#withTrace(Trace)} to
 * attach it. The reporter renders both forms through
 * {@code FlowStep#toCanonicalString()} so downstream
 * consumers are unchanged.</p>
 */
package org.plumb.reporter.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Trace {

  /** Trace rendering verbosity. */
  public enum CompressionLevel {
    /** Every node, in id order. Use for debug / /trace-level=full. */
    FULL,
    /**
     * Default. SOURCE / SANITIZER / SUMMARY_FOLD / SINK / VIOLATION always kept; consecutive
     * PROPAGATE nodes longer than {@link Trace#SUMMARY_CHAIN_THRESHOLD} are collapsed to one
     * SUMMARIZER node.
     */
    SUMMARIZED,
    /**
     * Only SOURCE + sink / violation nodes are kept in the flattened view. For high-volume scans
     * where the full trace is too noisy.
     */
    HEADER_ONLY,
    /**
     * No {@code dataflow_steps} emitted. Matches the v1 default of "no trace" for checkers that
     * have not yet been ported.
     */
    NONE
  }

  /**
   * Number of consecutive {@code PROPAGATE}-role nodes above which a chain is collapsed into one
   * SUMMARIZER node in {@link CompressionLevel#SUMMARIZED}. Tuned to keep Juliet CWE-89 traces at
   * roughly 5-7 visible nodes.
   */
  public static final int SUMMARY_CHAIN_THRESHOLD = 5;

  /**
   * Upper bound on the flattened {@code FlowStep} list. Above this, the tail is dropped and a
   * {@code "<truncated>"} step is appended so the reporter output stays bounded.
   */
  public static final int MAX_FLOW_STEPS = 200;

  private final List<TraceNode> nodes;
  private final int rootId;
  private final List<Integer> sinkOrViolationIds;
  private final CompressionLevel level;

  Trace(
      List<TraceNode> nodes, int rootId, List<Integer> sinkOrViolationIds, CompressionLevel level) {
    this.nodes = Objects.requireNonNull(nodes, "nodes");
    this.rootId = rootId;
    this.sinkOrViolationIds = Objects.requireNonNull(sinkOrViolationIds, "sinkOrViolationIds");
    this.level = Objects.requireNonNull(level, "level");
  }

  public List<TraceNode> nodes() {
    return nodes;
  }

  public int rootId() {
    return rootId;
  }

  public List<Integer> sinkOrViolationIds() {
    return sinkOrViolationIds;
  }

  public CompressionLevel compressionLevel() {
    return level;
  }

  /**
   * Flatten the DAG to a v1-compatible {@code List<FlowStep>}. Honors {@link #compressionLevel()}:
   *
   * <ul>
   *   <li>{@code FULL}: every node, in id order.
   *   <li>{@code SUMMARIZED}: SOURCE / SANITIZER / SUMMARY_FOLD / SINK / VIOLATION / GUARD / ALIAS
   *       nodes always kept; consecutive PROPAGATE runs longer than {@link
   *       #SUMMARY_CHAIN_THRESHOLD} collapsed to a single SUMMARIZER node (whose {@code note} is
   *       the chain description).
   *   <li>{@code HEADER_ONLY}: only SOURCE + the sink / violation anchors.
   *   <li>{@code NONE}: empty list. Reporters will omit the {@code dataflow_steps} field entirely.
   * </ul>
   *
   * <p>The output is bounded by {@link #MAX_FLOW_STEPS} to keep reporter output (NDJSON, SARIF)
   * bounded; truncated lists are appended with a single {@code "<truncated>"} sentinel step.
   */
  public List<org.plumb.reporter.FlowStep> toFlowSteps() {
    if (level == CompressionLevel.NONE || nodes.isEmpty()) {
      return List.of();
    }
    List<org.plumb.reporter.FlowStep> out = new ArrayList<>();
    switch (level) {
      case FULL -> emitFull(out);
      case SUMMARIZED -> emitSummarized(out);
      case HEADER_ONLY -> emitHeaderOnly(out);
      case NONE -> {
        /* unreachable, handled above */
      }
    }
    // Hard cap to keep the reporter output bounded.
    if (out.size() > MAX_FLOW_STEPS) {
      List<org.plumb.reporter.FlowStep> trimmed = new ArrayList<>(MAX_FLOW_STEPS + 1);
      trimmed.addAll(out.subList(0, MAX_FLOW_STEPS));
      trimmed.add(
          new org.plumb.reporter.FlowStep(
              "violation",
              "",
              -1,
              -1,
              "",
              "<truncated: trace had "
                  + out.size()
                  + " steps, showing first "
                  + MAX_FLOW_STEPS
                  + ">"));
      out = trimmed;
    }
    return Collections.unmodifiableList(out);
  }

  private void emitFull(List<org.plumb.reporter.FlowStep> out) {
    for (TraceNode n : nodes) {
      out.add(toFlowStep(n));
    }
  }

  private void emitSummarized(List<org.plumb.reporter.FlowStep> out) {
    // Roles that are never collapsed. SOURCE comes
    // first so the trace reads "source → ... → sink".
    int chainStart = -1;
    int chainLen = 0;
    for (int i = 0; i < nodes.size(); i++) {
      TraceNode n = nodes.get(i);
      if (n.role() == TraceNode.Role.PROPAGATE && !isSoleSummary(n)) {
        // Extend a contiguous PROPAGATE run.
        if (chainStart < 0) {
          chainStart = i;
          chainLen = 1;
        } else {
          chainLen++;
        }
        continue;
      }
      // Flush any pending chain.
      if (chainStart >= 0) {
        flushChain(out, chainStart, chainLen);
        chainStart = -1;
        chainLen = 0;
      }
      out.add(toFlowStep(n));
    }
    // Flush a trailing chain.
    if (chainStart >= 0) {
      flushChain(out, chainStart, chainLen);
    }
  }

  private void flushChain(List<org.plumb.reporter.FlowStep> out, int fromId, int len) {
    if (len <= SUMMARY_CHAIN_THRESHOLD) {
      // Short chains are kept verbatim — collapsing
      // them would hide the actual data flow from
      // human reviewers.
      for (int i = 0; i < len; i++) {
        out.add(toFlowStep(nodes.get(fromId + i)));
      }
      return;
    }
    // Long chains collapse to a single SUMMARIZER
    // step carrying the chain description.
    TraceNode first = nodes.get(fromId);
    TraceNode last = nodes.get(fromId + len - 1);
    String chainExpr = "propagate chain of " + len + " steps: " + first.op() + " ... " + last.op();
    out.add(
        new org.plumb.reporter.FlowStep(
            "propagate",
            first.filePath(),
            first.line(),
            first.column(),
            first.functionSig(),
            chainExpr));
  }

  private static boolean isSoleSummary(TraceNode n) {
    // A PROPAGATE node that is itself a SUMMARIZER
    // (COMPRESSED_CHAIN op) should not be collapsed
    // further; it is already a summary.
    return n.op() == TraceNode.Op.COMPRESSED_CHAIN;
  }

  private void emitHeaderOnly(List<org.plumb.reporter.FlowStep> out) {
    // Just the root SOURCE plus the sink / violation
    // anchors. We use the first sink/violation id
    // when multiple exist; the others are dropped
    // because HEADER_ONLY is a coarse "what's the
    // bug, where, from where" view.
    if (rootId >= 0 && rootId < nodes.size()) {
      out.add(toFlowStep(nodes.get(rootId)));
    }
    if (!sinkOrViolationIds.isEmpty()) {
      int sinkId = sinkOrViolationIds.get(0);
      if (sinkId >= 0 && sinkId < nodes.size()) {
        out.add(toFlowStep(nodes.get(sinkId)));
      }
    }
  }

  private static org.plumb.reporter.FlowStep toFlowStep(TraceNode n) {
    // The role-to-FlowStep.role mapping is the same
    // one v1 used: SOURCE -> "source", PROPAGATE ->
    // "propagate", SUMMARIZER -> "propagate" (with
    // a different expr), SINK -> "sink", VIOLATION ->
    // "violation", SANITIZER / SUMMARY_FOLD / GUARD
    // / ALIAS -> "propagate" with a distinctive note.
    String flowRole =
        switch (n.role()) {
          case SOURCE -> "source";
          case PROPAGATE -> "propagate";
          case SUMMARIZER -> "propagate";
          case SINK -> "sink";
          case VIOLATION -> "violation";
          case SANITIZER, SUMMARY_FOLD, GUARD, ALIAS -> "propagate";
        };
    return new org.plumb.reporter.FlowStep(
        flowRole, n.filePath(), n.line(), n.column(), n.functionSig(), renderExpr(n));
  }

  private static String renderExpr(TraceNode n) {
    StringBuilder sb = new StringBuilder();
    sb.append(n.op().name());
    if (!n.operands().isEmpty()) {
      sb.append(' ');
      for (int i = 0; i < n.operands().size(); i++) {
        if (i > 0) sb.append(", ");
        sb.append(n.operands().get(i).render());
      }
    }
    if (!n.note().isEmpty()) {
      if (sb.length() > 0) sb.append(" — ");
      sb.append(n.note());
    }
    return sb.toString();
  }

  /**
   * Test-only: build a {@code Trace} directly from a flat {@code List<FlowStep>} (the v1 shape).
   * Each step becomes a {@code PROPAGATE}-role node, except the first step (which becomes {@code
   * SOURCE} if its role is {@code "source"}) and the last step (which becomes {@code VIOLATION} /
   * {@code SINK} based on its role). Used by the v1-compat tests to assert that the round-trip is
   * stable.
   */
  public static Trace fromFlowSteps(List<org.plumb.reporter.FlowStep> steps) {
    if (steps == null || steps.isEmpty()) {
      return new Trace(List.of(), -1, List.of(), CompressionLevel.FULL);
    }
    TraceBuilder tb = new TraceBuilder();
    int previousId = -1;
    List<Integer> tailIds = new ArrayList<>(2);
    for (int i = 0; i < steps.size(); i++) {
      org.plumb.reporter.FlowStep s = steps.get(i);
      TraceNode.Role role = mapRole(s.role());
      TraceNode.Op op = TraceNode.Op.UNKNOWN;
      List<Integer> parents =
          (previousId >= 0 && role != TraceNode.Role.SOURCE)
              ? List.of(previousId)
              : List.<Integer>of();
      int id =
          tb.addNodeForCompat(
              role,
              op,
              List.of(new OperandRef.Free(s.expr())),
              "",
              s.filePath(),
              s.line(),
              s.column(),
              s.functionSig(),
              parents);
      if (role == TraceNode.Role.VIOLATION || role == TraceNode.Role.SINK) {
        tailIds.add(id);
      }
      previousId = id;
    }
    return tb.build(tailIds, CompressionLevel.FULL);
  }

  private static TraceNode.Role mapRole(String flowStepRole) {
    if (flowStepRole == null) {
      return TraceNode.Role.PROPAGATE;
    }
    return switch (flowStepRole) {
      case "source" -> TraceNode.Role.SOURCE;
      case "sink" -> TraceNode.Role.SINK;
      case "violation" -> TraceNode.Role.VIOLATION;
      case "summary-fold" -> TraceNode.Role.SUMMARY_FOLD;
      // "propagate" and anything else collapse to
      // PROPAGATE; the SUMMARIZER and SANITIZER
      // roles are introduced in v2 and don't have
      // a v1 analogue.
      default -> TraceNode.Role.PROPAGATE;
    };
  }

  @Override
  public String toString() {
    return "Trace{level="
        + level
        + " root="
        + rootId
        + " sinks="
        + sinkOrViolationIds
        + " nodes="
        + nodes.size()
        + "}";
  }
}
