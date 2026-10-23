/*
 * R-808 ? V2 trace summary inline-injection for the FP-reducer
 * ReAct prompt. The summarizer produces a single short line
 * (≤ 200 chars) that captures the SHAPE of a v2 structured
 * {@link Trace} so the LLM sees the trace structure on the
 * first user turn, without having to call
 * {@code get_dataflow_trace_with_facts} on its own.
 *
 * <p>The summary format is intentionally compact:</p>
 * <pre>
 *   v2:&lt;N&gt; nodes, root=&lt;role&gt;/&lt;op&gt;, ops=[&lt;op1&gt;,&lt;op2&gt;,...], sinks=[&lt;sink_id&gt;,...], const=&lt;K&gt;, orphan=&lt;J&gt;
 * </pre>
 *
 * <p>Why "shape" and not the full chain:</p>
 * <ul>
 *   <li>Token budget: a 35-node Juliet CWE-89 trace inflates
 *       the user turn by ~2 KB; a 200-char summary is a fixed
 *       cost regardless of trace size.</li>
 *   <li>Decision signal: the AI dedup verdict is mostly a
 *       function of (a) is the source user-controlled,
 *       (b) did the path go through a sanitiser,
 *       (c) is the sink exploitable. The shape — "op
 *       distribution + typed-const count + orphan count" —
 *       already gives the LLM 80% of the signal it needs to
 *       decide TP vs FP without rendering every node.</li>
 *   <li>Detail on demand: if the LLM needs more, it calls
 *       {@code get_dataflow_trace_with_facts} (R-806) which
 *       walks the full DAG.</li>
 * </ul>
 *
 * <p>Field-by-field contract:</p>
 * <ul>
 *   <li>{@code v2:<N> nodes} ? total node count; lets the
 *       LLM gauge trace complexity.</li>
 *   <li>{@code root=<role>/<op>} ? the SOURCE node's role and
 *       op. {@code SOURCE/MARK_SOURCE} with
 *       {@code ConstStr/ConstInt/ConstNull} → const-initialised
 *       local (over-fire signal, default FP per Lesson 668).
 *       {@code SOURCE/INPUT_READLINE} etc → real user
 *       input → TP candidate.</li>
 *   <li>{@code ops=[MOVE,BINOP_ADD,SINK_SQL,...]} ? the
 *       unique op enum names that appear in the trace, in
 *       insertion order. Lets the LLM see "this trace has
 *       a SUMMARY_FOLD" or "this trace has a SANITIZER"
 *       without rendering each node.</li>
 *   <li>{@code sinks=[id1,id2]} ? the sink/violation ids
 *       (one finding can reach multiple sinks). Bounded to
 *       3 ids to keep the line short; full list is in the
 *       v2 trace's {@code sinkOrViolationIds} field anyway.</li>
 *   <li>{@code const=<K>} ? number of SOURCE-role nodes with
 *       a typed {@code OperandRef.Const*} operand. {@code K=0}
 *       means no const-source; {@code K>=1} means Juliet-style
 *       fixture scaffolding. The LLM can use this to spot
 *       "data = \"\"" patterns automatically.</li>
 *   <li>{@code orphan=<J>} ? number of SOURCE-role nodes with
 *       no parents (i.e. trust-boundary nodes that the engine
 *       couldn't recover a source handle for). High orphan
 *       count is an FP signal (R-804 follow-up noted Juliet
 *       logger scaffolding produces 17 orphans per finding).</li>
 * </ul>
 */
package org.plumb.ai.tools;

import org.plumb.reporter.trace.OperandRef;
import org.plumb.reporter.trace.Trace;
import org.plumb.reporter.trace.TraceNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * produces a compact one-line summary of a v2 {@link Trace} for inline injection into the
 * FP-reducer ReAct prompt. The summary is hermetic: it depends only on the public {@code Trace} /
 * {@code TraceNode} API, no internal plumb-dfa state. Null-safe: a null trace yields an empty
 * string (caller is expected to skip injection when the result is empty).
 */
public final class V2TraceSummarizer {

    /**
     * Hard cap on the summary line length. Keeps the ReAct user turn bounded regardless of trace
     * size; the full trace is reachable via {@code get_dataflow_trace_with_facts}.
     */
    public static final int MAX_SUMMARY_CHARS = 200;

    /**
     * Maximum number of sink ids we list in the summary.
     */
    private static final int MAX_SINKS_IN_SUMMARY = 3;

    /**
     * Cap on the number of distinct op names we list ? beyond this, the chain becomes a noise signal.
     */
    private static final int MAX_OPS_IN_SUMMARY = 8;

    private V2TraceSummarizer() {
    }

    /**
     * Summarize a v2 trace into a one-line description. Returns the empty string when {@code trace}
     * is null or has no nodes ? callers should skip the inline injection in that case.
     */
    public static String summarize(Trace trace) {
        if (trace == null || trace.nodes() == null || trace.nodes().isEmpty()) {
            return "";
        }
        List<TraceNode> nodes = trace.nodes();

        // ---- (1) root source role / op ----
        int rootId = trace.rootId();
        String rootRole = "(no-root)";
        String rootOp = "(no-root)";
        if (rootId >= 0 && rootId < nodes.size()) {
            TraceNode root = nodes.get(rootId);
            rootRole = root.role().name();
            rootOp = root.op().name();
        }

        // ---- (2) unique op enum names in insertion order ----
        Set<String> ops = new LinkedHashSet<>();
        for (TraceNode n : nodes) {
            ops.add(n.op().name());
            if (ops.size() >= MAX_OPS_IN_SUMMARY) break;
        }
        String opsPart = ops.toString();

        // ---- (3) sink ids (capped) ----
        StringBuilder sinks = new StringBuilder("[");
        List<Integer> sinkIds = trace.sinkOrViolationIds();
        int listed = 0;
        for (int i = 0; i < sinkIds.size() && listed < MAX_SINKS_IN_SUMMARY; i++) {
            if (listed > 0) sinks.append(',');
            sinks.append(sinkIds.get(i));
            listed++;
        }
        if (sinkIds.size() > MAX_SINKS_IN_SUMMARY) {
            sinks.append(",...+").append(sinkIds.size() - MAX_SINKS_IN_SUMMARY);
        }
        sinks.append(']');

        // ---- (4) typed const-source count + orphan count ----
        int constSourceCount = 0;
        int orphanSourceCount = 0;
        for (TraceNode n : nodes) {
            if (n.role() != TraceNode.Role.SOURCE) continue;
            if (hasTypedConstOperand(n)) {
                constSourceCount++;
            }
            if (n.parentIds().isEmpty()) {
                orphanSourceCount++;
            }
        }

        // ---- assemble ----
        String summary =
                "v2:"
                        + nodes.size()
                        + " nodes, "
                        + "root="
                        + rootRole
                        + "/"
                        + rootOp
                        + ", "
                        + "ops="
                        + opsPart
                        + ", "
                        + "sinks="
                        + sinks
                        + ", "
                        + "const="
                        + constSourceCount
                        + ", "
                        + "orphan="
                        + orphanSourceCount;

        if (summary.length() > MAX_SUMMARY_CHARS) {
            // Defensive: the components above are bounded but
            // opsPart could be long if the chain has many
            // distinct op enums (very unlikely in practice).
            // Truncate with a trailing ellipsis to keep the
            // line under the cap.
            summary = summary.substring(0, MAX_SUMMARY_CHARS - 3) + "...";
        }
        return summary;
    }

    /**
     * True when {@code n} carries a typed {@link OperandRef.Const*} operand. We use this to count
     * "data = \"\"" / "data = null" style initialisers that propagateMOVE emitted as v2 SOURCE nodes
     * (R-807).
     */
    private static boolean hasTypedConstOperand(TraceNode n) {
        if (n.operands() == null) return false;
        for (OperandRef op : n.operands()) {
            if (op instanceof OperandRef.ConstStr
                    || op instanceof OperandRef.ConstInt
                    || op instanceof OperandRef.ConstFloat
                    || op instanceof OperandRef.ConstNull) {
                return true;
            }
        }
        return false;
    }

    // Test-only entry points (package-private for the
    // R808TraceSummaryTest hermetic coverage).

    /**
     * Visible for tests ? the orphan-counting rule.
     */
    static int countOrphanSources(List<TraceNode> nodes) {
        int j = 0;
        for (TraceNode n : nodes) {
            if (n.role() == TraceNode.Role.SOURCE && (n.parentIds() == null || n.parentIds().isEmpty())) {
                j++;
            }
        }
        return j;
    }

    /**
     * Visible for tests ? the const-source-counting rule.
     */
    static int countTypedConstSources(List<TraceNode> nodes) {
        int k = 0;
        for (TraceNode n : nodes) {
            if (n.role() == TraceNode.Role.SOURCE && hasTypedConstOperand(n)) {
                k++;
            }
        }
        return k;
    }

    // Suppress unused-import warning for ArrayList (kept
    // around for the day we add a "top-N operations" mode).
    @SuppressWarnings("unused")
    private static List<String> unused() {
        return new ArrayList<>();
    }
}
