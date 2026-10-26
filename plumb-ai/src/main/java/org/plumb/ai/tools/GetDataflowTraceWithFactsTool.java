package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;
import org.plumb.reporter.Finding;
import org.plumb.reporter.FlowStep;
import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceNode;

/**
 * return the current finding's dataflow trace, re-formatted with each step labelled by role and a
 * one-line annotation of the Z3 path condition when one is available. The "with facts" half of the
 * name refers to the symbolic facts the unified DFA engine emits alongside the trace (currently:
 * source location, branch conditions, summary-folds across function boundaries).
 *
 * <p>The LLM uses this instead of the raw {@code Finding.dataflowSteps()} string when it needs to
 * reason about WHY the static analyzer flagged a finding -- e.g. "the value at the sink was tainted
 * because the upstream BRANCH on line 32 was not negating the reachability".
 *
 * <p>when the finding carries a v2 structured {@code Trace}, this tool renders the DAG with the
 * specific {@link TraceNode.Op} enum (MOVE / BINOP_ADD / INPUT_READLINE / SINK_SQL / FIELDLOAD /
 * SUMMARY_TAINT_PROPAGATE / ...) instead of falling back to a v1 {@code FlowStep} string
 * projection. The v2 path is materially more precise -- the LLM can distinguish {@code MOVE x -> y}
 * from {@code BINOP_ADD rhs -> t} from {@code FIELDLOAD this.IO -> t}, all of which collapsed to a
 * generic "propagate" string in v1. The v1 path is kept as a fallback for findings emitted by
 * checkers that don't dual-write.
 */
public class GetDataflowTraceWithFactsTool implements Tool {

  @Override
  public String name() {
    return "get_dataflow_trace_with_facts";
  }

  @Override
  public String description() {
    return "Return the current finding's source-to-sink trace, "
        + "re-formatted with each step labelled by role "
        + "(source / propagate / summary-fold / sink / "
        + "violation) and the Z3 path condition / branch "
        + "facts when present. When the v2 structured "
        + "Trace is attached, each step carries the specific "
        + "operation enum (MOVE / BINOP_ADD / INPUT_READLINE "
        + "/ SINK_SQL / FIELDLOAD / SUMMARY_TAINT_PROPAGATE "
        + "/ ...) instead of a generic 'propagate' string. "
        + "The single most useful tool for reasoning about "
        + "WHY the static analyzer fired.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(name(), description(), Map.of(), List.of());
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (ctx.currentFinding() == null) {
      return ToolResult.error("no current finding");
    }
    Finding f = ctx.currentFinding();
    StringBuilder sb = new StringBuilder();
    sb.append("Dataflow trace for ")
        .append(ruleNameOf(f))
        .append(" @ ")
        .append(f.file())
        .append(":")
        .append(f.line())
        .append(":\n\n");
    // prefer the v2 structured Trace when present
    // ( P3.1 dual-write + eager). The v2 path
    // exposes specific op enums; v1 is a flat string list
    // with heuristic role classification.
    Trace v2 = f.trace();
    if (v2 != null && v2.nodes() != null && !v2.nodes().isEmpty()) {
      renderV2Trace(sb, f, v2);
    } else {
      renderV1Steps(sb, f);
    }
    if (f.rule() != null) {
      sb.append("\nZ3 path-condition facts (if present in the " + "finding's extras):\n");
      sb.append("  rule: ").append(f.rule().ruleId().name()).append('\n');
      sb.append("  cwe: ").append(String.join(",", f.rule().cwe())).append('\n');
    }
    sb.append("  dataflow_head: ")
        .append(f.dataflowHead() == null ? "(none)" : f.dataflowHead())
        .append('\n');
    sb.append("\nHow to read this:\n");
    sb.append(
        "  - source: where the tainted value originated (with op=INPUT_READLINE, INPUT_ARGS,"
            + " ...)\n");
    sb.append("  - propagate: taint flowed through a MOVE/ASSIGN, BINOP_ADD, CALL_INSTANCE, ...\n");
    sb.append(
        "  - summary-fold: taint was inferred via a callee summary (cross-function)"
            + " op=SUMMARY_TAINT_PROPAGATE\n");
    sb.append("  - sink: the call site the analyzer flagged (op=SINK_SQL / SINK_COMMAND / ...)\n");
    sb.append("  - violation: the finding itself (last step, op=VIOLATION)\n");
    return ToolResult.ok(sb.toString());
  }

  /**
   * Render the v2 structured trace by walking the DAG from the root via parentIds/childIds, and
   * label each visited node with its {@link TraceNode.Op} enum + operands (typed via {@link
   * OperandRef}). Sinks (nodes in {@code trace.sinkOrViolationIds()}) are marked explicitly.
   * Multi-parent ALIAS nodes are rendered once (the first time they're visited) to avoid
   * duplicating shared sub-paths.
   */
  private static void renderV2Trace(StringBuilder sb, Finding f, Trace v2) {
    // Build id -> node index for fast parent/child walk
    Map<Integer, TraceNode> byId = new HashMap<>();
    for (TraceNode n : v2.nodes()) byId.put(n.id(), n);

    // DFS from rootId through childIds. The DAG may
    // branch (ALIAS role) or converge; we render in
    // a depth-first pre-order with visited-set to
    // avoid re-emitting convergence points.
    int root = v2.rootId();
    Set<Integer> sinkIds = new HashSet<>(v2.sinkOrViolationIds());

    List<TraceNode> ordered = new ArrayList<>();
    Deque<int[]> stack = new ArrayDeque<>();
    if (byId.containsKey(root)) {
      stack.push(new int[] {root, 0});
    }
    Set<Integer> visited = new HashSet<>();
    while (!stack.isEmpty()) {
      int[] top = stack.pop();
      int id = top[0];
      int childIdx = top[1];
      TraceNode n = byId.get(id);
      if (n == null) continue;
      if (childIdx == 0) {
        if (!visited.add(id)) {
          // already rendered this node — skip
          continue;
        }
        ordered.add(n);
      }
      List<Integer> kids = n.childIds();
      if (childIdx < kids.size()) {
        // re-push with advanced index, then push next child
        stack.push(new int[] {id, childIdx + 1});
        int nextKid = kids.get(childIdx);
        stack.push(new int[] {nextKid, 0});
      }
    }

    sb.append("v2 trace — ")
        .append(ordered.size())
        .append(" nodes (rootId=")
        .append(v2.rootId())
        .append(", sinks=")
        .append(sinkIds.size())
        .append(", ")
        .append("compression=")
        .append(v2.compressionLevel())
        .append("):\n");
    for (int i = 0; i < ordered.size(); i++) {
      TraceNode n = ordered.get(i);
      String role = roleOf(n, sinkIds.contains(n.id()));
      sb.append(
          String.format(
              "  %3d. %-13s | op=%-22s | %s%n", i + 1, role, n.op().name(), renderNode(n)));
    }
    if (ordered.isEmpty()) {
      sb.append("  (rootId=")
          .append(root)
          .append(" not found in " + "nodes — orphan / cross-fn trust boundary)\n");
    }
  }

  /** ? render a single v2 node: file:line:col + functionSig + typed operands + note. */
  private static String renderNode(TraceNode n) {
    StringBuilder sb = new StringBuilder();
    if (n.filePath() != null && !n.filePath().isEmpty()) {
      sb.append(n.filePath()).append(':').append(n.line());
      if (n.column() >= 0) sb.append(':').append(n.column());
    } else {
      sb.append("(line ").append(n.line()).append(')');
    }
    if (n.functionSig() != null && !n.functionSig().isEmpty()) {
      sb.append("  sig=").append(n.functionSig());
    }
    if (!n.operands().isEmpty()) {
      sb.append("  ops=[");
      for (int i = 0; i < n.operands().size(); i++) {
        if (i > 0) sb.append(", ");
        OperandRef op = n.operands().get(i);
        sb.append(op.getClass().getSimpleName()).append('(').append(op.render()).append(')');
      }
      sb.append(']');
    }
    if (n.note() != null && !n.note().isEmpty()) {
      sb.append("  // ").append(n.note());
    }
    return sb.toString();
  }

  /** ? translate v2 TraceNode role + sink flag into the LLM-facing role label. */
  private static String roleOf(TraceNode n, boolean isSink) {
    return switch (n.role()) {
      case SOURCE -> "source";
      case PROPAGATE -> isSink ? "sink" : "propagate";
      case ALIAS -> isSink ? "sink" : "alias";
      case SUMMARY_FOLD, SUMMARIZER -> "summary-fold";
      case VIOLATION -> "violation";
      case SANITIZER -> "sanitizer";
      case GUARD -> "guard";
      case SINK -> "sink";
    };
  }

  /**
   * v1 path -- preserved for findings emitted by checkers that don't dual-write the v2 trace ( P3.1
   * was the gating commit; pre-P3.1 findings still flow through here). Heuristic role
   * classification based on the FlowStep.role() field + canonical-string fallback.
   */
  private static void renderV1Steps(StringBuilder sb, Finding f) {
    List<FlowStep> steps = f.dataflowSteps();
    if (steps == null || steps.isEmpty()) {
      sb.append(
          "  (no structured dataflow steps — this finding "
              + "may have been produced by a non-taint checker)\n");
      if (f.dataflowHead() != null) {
        sb.append("  Short summary: ").append(f.dataflowHead()).append('\n');
      }
      return;
    }
    int n = steps.size();
    sb.append("Steps (").append(n).append("):\n");
    for (int i = 0; i < n; i++) {
      FlowStep step = steps.get(i);
      String role = classifyV1(step, i, n);
      sb.append(String.format("  %2d. %-13s | %s%n", i + 1, role, step.toCanonicalString()));
    }
  }

  /** Heuristic role classification (v1 fallback). */
  private static String classifyV1(FlowStep step, int i, int n) {
    String canonical = step.toCanonicalString();
    String role = step.role() == null ? "" : step.role();
    if (role.equalsIgnoreCase("source")) return "source";
    if (role.equalsIgnoreCase("violation")) return "violation";
    if (role.equalsIgnoreCase("propagate")) return "propagate";
    if (i == 0) return "source";
    if (i == n - 1) {
      if (canonical.toLowerCase().contains("violation")) return "violation";
      return "sink";
    }
    if (canonical.toLowerCase().contains("summary-fold")
        || canonical.toLowerCase().contains("summary:")) {
      return "summary-fold";
    }
    if (canonical.toLowerCase().contains("z3-fact")
        || canonical.toLowerCase().contains("z3 path condition")) {
      return "z3-fact";
    }
    if (canonical.toLowerCase().contains("branch")) return "branch";
    return "propagate";
  }

  private static String ruleNameOf(Finding f) {
    if (f.rule() != null) return f.rule().ruleId().name();
    if (f.ruleId() != null) return f.ruleId();
    return "(unknown-rule)";
  }
}
