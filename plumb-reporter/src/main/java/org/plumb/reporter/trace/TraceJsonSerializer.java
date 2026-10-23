/*
 * R-803 P3.2: serialize a {@link Trace} (DAG of
 * {@link TraceNode}s) to a JSON object literal.
 *
 * <p>Used by {@code NdjsonEmitter} to emit the
 * {@code v2_trace} field next to the legacy
 * {@code dataflow_steps} field. Hand-rolled JSON
 * to avoid pulling in a third-party dependency —
 * the reporter module already follows the same
 * pattern (see {@code NdjsonEmitter.esc}).</p>
 *
 * <h2>Shape</h2>
 *
 * <pre>
 * {
 *   "level": "FULL" | "SUMMARIZED" | "HEADER_ONLY" | "NONE",
 *   "rootId": int,
 *   "sinkOrViolationIds": [int, ...],
 *   "nodes": [
 *     {
 *       "id": int,
 *       "role": "SOURCE" | "PROPAGATE" | "...",
 *       "op":   "MOVE" | "INPUT_READLINE" | "...",
 *       "operands": [
 *         {"type": "Var" | "ParamRef" | "ConstInt" | "ConstStr" |
 *          "ConstNull" | "ConstFloat" | "CallRef" | "CtorRef" |
 *          "FieldRef" | "Free",
 *          "render": "<canonical human-readable form>"}
 *       ],
 *       "note": "...",
 *       "filePath": "...",
 *       "line": int,
 *       "column": int,
 *       "functionSig": "...",
 *       "parentIds": [int, ...],
 *       "childIds": [int, ...],
 *       "pruneTargetId": int (-1 = not in a compressed segment)
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>The {@code type} discriminator lets v2-aware
 * consumers (AI dedup agent, SARIF v2 extension,
 * Plumb-UI trace viewer) dispatch on the concrete
 * {@link OperandRef} shape without re-parsing the
 * {@code render} string. The {@code render} field
 * is included for human-readable fallback.</p>
 */
package org.plumb.reporter.trace;

import java.util.List;
import org.plumb.reporter.Finding;

public final class TraceJsonSerializer {

  private TraceJsonSerializer() {}

  /**
   * Render a {@link Trace} as a JSON object literal (no trailing newline). Pass {@code null} to get
   * the empty string (the caller decides whether to emit a {@code v2_trace: null} or omit the field
   * entirely).
   */
  public static String toJson(Trace trace) {
    if (trace == null) return "null";
    StringBuilder sb = new StringBuilder(256);
    sb.append('{');
    kv(sb, "level");
    sb.append('"').append(trace.compressionLevel().name()).append('"');
    sb.append(',');
    kv(sb, "rootId");
    sb.append(trace.rootId());
    sb.append(',');
    kv(sb, "sinkOrViolationIds");
    sb.append('[');
    List<Integer> sinks = trace.sinkOrViolationIds();
    for (int i = 0; i < sinks.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(sinks.get(i));
    }
    sb.append(']');
    sb.append(',');
    kv(sb, "nodes");
    sb.append('[');
    List<TraceNode> nodes = trace.nodes();
    for (int i = 0; i < nodes.size(); i++) {
      if (i > 0) sb.append(',');
      appendNode(sb, nodes.get(i));
    }
    sb.append(']');
    sb.append('}');
    return sb.toString();
  }

  /**
   * R-803 P3.2: extract the v2 trace attached to a finding via the {@code
   * FindingReporter.traceFor()} side channel. Convenience wrapper so the {@code NdjsonEmitter}
   * doesn't have to know about the reporter.
   *
   * <p>Returns {@code null} when no v2 trace was attached (v1-only finding) ? the caller should
   * omit the {@code v2_trace} field in that case to keep back-compat with consumers that don't
   * recognise the new key.
   */
  public static Trace traceOf(Finding f) {
    // The NdjsonEmitter runs in the same process as
    // the FindingReporter; the Finding is just a
    // record. The trace is attached to the Finding
    // instance via a side-channel (IdentityHashMap
    // keyed on the post-rewrite Finding). For the
    // DfaEngine path, the finding is the post-rewrite
    // instance (FindingReporter.report() returns ff,
    // not the input). For direct NdjsonEmitter use
    // (e.g. tests), the Finding is the input as-is.
    //
    // We expose a setter hook below (withTrace) so
    // the report pipeline can wire the trace in.
    return f.trace();
  }

  private static void appendNode(StringBuilder sb, TraceNode n) {
    sb.append('{');
    kv(sb, "id");
    sb.append(n.id());
    sb.append(',');
    kv(sb, "role");
    sb.append('"').append(n.role().name()).append('"');
    sb.append(',');
    kv(sb, "op");
    sb.append('"').append(n.op().name()).append('"');
    sb.append(',');
    kv(sb, "operands");
    appendOperands(sb, n.operands());
    sb.append(',');
    kv(sb, "note");
    sb.append('"').append(esc(n.note())).append('"');
    sb.append(',');
    kv(sb, "filePath");
    sb.append('"').append(esc(n.filePath())).append('"');
    sb.append(',');
    kv(sb, "line");
    sb.append(n.line());
    sb.append(',');
    kv(sb, "column");
    sb.append(n.column());
    sb.append(',');
    kv(sb, "functionSig");
    sb.append('"').append(esc(n.functionSig())).append('"');
    sb.append(',');
    kv(sb, "parentIds");
    appendInts(sb, n.parentIds());
    sb.append(',');
    kv(sb, "childIds");
    appendInts(sb, n.childIds());
    sb.append(',');
    kv(sb, "pruneTargetId");
    sb.append(n.pruneTargetId());
    sb.append('}');
  }

  private static void appendOperands(StringBuilder sb, List<OperandRef> ops) {
    sb.append('[');
    for (int i = 0; i < ops.size(); i++) {
      if (i > 0) sb.append(',');
      OperandRef op = ops.get(i);
      String type = op.getClass().getSimpleName();
      sb.append('{');
      kv(sb, "type");
      sb.append('"').append(type).append('"');
      sb.append(',');
      kv(sb, "render");
      sb.append('"').append(esc(op.render())).append('"');
      sb.append('}');
    }
    sb.append(']');
  }

  private static void appendInts(StringBuilder sb, List<Integer> ints) {
    sb.append('[');
    for (int i = 0; i < ints.size(); i++) {
      if (i > 0) sb.append(',');
      sb.append(ints.get(i));
    }
    sb.append(']');
  }

  private static void kv(StringBuilder sb, String k) {
    sb.append('"').append(esc(k)).append("\":");
  }

  private static String esc(String s) {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length() + 4);
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
          else sb.append(c);
        }
      }
    }
    return sb.toString();
  }
}
