/*
 * One step of a structured source-to-sink trace. It lives in
 * {@code plumb.reporter} so that the reporter, the NDJSON emitter, and the
 * unified engine all share a single trace type.
 *
 * <p>Each step records:</p>
 * <ul>
 *   <li>the role ({@code "source"} for the taint
 *       entry point -- function param, input source
 *       call, or explicit
 *       {@code UntrustedSource} fact;
 *       {@code "propagate"} for intra-procedural
 *       MOVE / BINOP / FIELDLOAD;
 *       {@code "summary-fold"} for cross-function
 *       summary-applied propagation; {@code "sink"}
 *       for the violation point in a taint-style
 *       checker; {@code "violation"} for a
 *       single-step structural finding where the
 *       check does not propagate data -- e.g.
 *       use-after-free, resource-leak,
 *       empty-catch),</li>
 *   <li>the source file (best-effort; may be empty for
 *       synthetic cross-TU paths),</li>
 *   <li>the line / column within that file,</li>
 *   <li>the function signature (so the trace can be
 *       disambiguated when the same .java file has
 *       multiple methods with the same simple
 *       name),</li>
 *   <li>and a free-form {@code expr} field that
 *       captures the operand(s) involved (e.g.
 *       {@code "request.getParameter(\"name\")"} or
 *       {@code "param:0:user.id"}).</li>
 * </ul>
 *
 * <p>Emitted in the
 * {@link Finding#dataflowSteps} list so reporters
 * (NDJSON, SARIF, console) can render the full
 * source-to-sink path. The legacy single-string
 * {@link Finding#dataflowHead()} field is kept for
 * backwards compatibility -- its value is
 * conventionally the {@code expr} of the last
 * {@code "sink"} step.</p>
 */
package org.plumb.reporter;

import java.util.Objects;

public record FlowStep(
    String role, // source | propagate | summary-fold | sink | violation
    String filePath,
    int line, // 1-based; -1 if unknown
    int column, // 0-based; -1 if unknown
    String functionSig, // e.g. "Foo#bar(int):int"; "" if unknown
    String expr) { // human-readable operand / statement

  public FlowStep {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(expr, "expr");
  }

  /** Convenience constructor for an "unknown location" step. */
  public static FlowStep of(String role, String expr) {
    return new FlowStep(role, "", -1, -1, "", expr);
  }

  /**
   * convert a v2 {@link org.plumb.reporter.trace.TraceNode} into the canonical {@code FlowStep}
   * form used by the v1 reporters (NDJSON, SARIF, console). The role mapping is the one documented
   * in {@link org.plumb.reporter.trace.Trace#toFlowSteps()}: {@code SOURCE} -> {@code "source"},
   * {@code SINK} -> {@code "sink"}, {@code VIOLATION} -> {@code "violation"}, everything else ->
   * {@code "propagate"}. The {@code expr} is built as {@code "<OpName> <op1>, <op2>, ... — <note>"}
   * (or the empty string if no operands and no note). Used by v2 checkers to emit findings that the
   * legacy reporter pipeline can still consume without modification.
   */
  public static FlowStep of(org.plumb.reporter.trace.TraceNode n) {
    org.plumb.reporter.trace.TraceNode.Role role = n.role();
    String flowRole =
        switch (role) {
          case SOURCE -> "source";
          case SINK -> "sink";
          case VIOLATION -> "violation";
          case SUMMARIZER, SANITIZER, SUMMARY_FOLD, GUARD, ALIAS, PROPAGATE -> "propagate";
        };
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
    return new FlowStep(
        flowRole, n.filePath(), n.line(), n.column(), n.functionSig(), sb.toString());
  }

  /**
   * Format a single step in the canonical {@code "<role> | <file>:<line>:<col> | <expr>"} form,
   * matching the legacy single-string format the NDJSON emitter used before unified {@code
   * FlowStep} into the reporter. Used by {@code FindingFormatter} and the SARIF renderer for
   * human-readable output.
   */
  public String toCanonicalString() {
    String loc =
        (filePath == null || filePath.isEmpty()) ? "" : (filePath + ":" + line + ":" + column);
    return role + " | " + loc + " | " + expr;
  }
}
