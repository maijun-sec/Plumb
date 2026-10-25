/*
 * TraceBuilder ? the single construction entry
 * point for v2 structured traces.
 *
 * <p>Checkers (and {@code TaintedCheckerBase}) call
 * {@code addSource} / {@code addPropagate} / {@code
 * addSink} / etc. on a per-function or per-finding
 * builder. The builder assigns sequential {@code id}s,
 * wires up the parent / child edges, and produces a
 * fully-realised {@link Trace} on {@link #build}.</p>
 *
 * <p>Why a builder rather than {@code new TraceNode(...)}:
 * the v1 free-for-all ({@code new FlowStep(...)} at
 * arbitrary call sites) is the exact thing that made the
 * 91-checker codebase inconsistent ? some checkers have
 * full traces, some have a single violation step, most
 * have nothing. A builder is the single chokepoint that
 * (a) guarantees id uniqueness, (b) maintains the
 * parent / child edges automatically, and (c) makes the
 * compression step a one-line {@link
 * #summarizeChain(int, int, String)} call rather than
 * ad-hoc post-processing.</p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *   TraceBuilder tb = new TraceBuilder();
 *   int src = tb.addSource(
 *       Op.INPUT_GETPARAMETER,
 *       List.of(OperandRef.CallRef("request.getParameter(\"id\")")),
 *       "Foo.java", 1, 5, "Foo#bad()");
 *   int mv = tb.addPropagate(
 *       Op.MOVE,
 *       List.of(OperandRef.Var("id", 1), OperandRef.Var("data", 1)),
 *       List.of(src),
 *       "Foo.java", 2, 9, "Foo#bad()");
 *   int sink = tb.addSink(
 *       Op.SINK_SQL,
 *       List.of(OperandRef.CallRef("Statement.executeQuery(data)")),
 *       List.of(mv),
 *       "Foo.java", 4, 9, "Foo#bad()");
 *   int viol = tb.addViolation(
 *       Op.SINK_SQL_INJECTION,
 *       "tainted data flows to executeQuery",
 *       "Foo.java", 4, 9, "Foo#bad()",
 *       List.of(sink));
 *   Trace trace = tb.build(List.of(viol), Trace.CompressionLevel.SUMMARIZED);
 * }</pre>
 */
package org.plumb.reporter.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TraceBuilder {

  private final List<TraceNode> nodes = new ArrayList<>();
  private int nextId = 0;

  /**
   * Allocate a new id; package-private so {@link Trace} can iterate nodes in id order without
   * leaking the builder's internal counter.
   */
  int nextId() {
    return nextId;
  }

  // ---- node creation ----

  /**
   * Append a {@link TraceNode.Role#SOURCE} node and return its id. {@code operands} typically
   * carries the input-source call (a {@link OperandRef.CallRef}) or the parameter reference (a
   * {@link OperandRef.ParamRef}). The {@code note} is a human-readable one-liner
   * ("request.getParameter" or "param:0:user.id") used by the legacy {@code FlowStep.expr} compat
   * view.
   */
  public int addSource(
      TraceNode.Op op,
      List<OperandRef> operands,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.SOURCE, op, operands, note, filePath, line, column, functionSig, List.of());
  }

  /**
   * Append a {@link TraceNode.Role#PROPAGATE} node and return its id. {@code parents} is the list
   * of immediate data-flow predecessors; pass an empty list for a root propagation (rare ? usually
   * only SOURCE nodes have no parents).
   */
  public int addPropagate(
      TraceNode.Op op,
      List<OperandRef> operands,
      List<Integer> parents,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.PROPAGATE, op, operands, note, filePath, line, column, functionSig, parents);
  }

  /**
   * Convenience overload for single-parent propagation ? the common case (MOVE, FIELDLOAD,
   * ARRAYLOAD, CALL passthrough).
   */
  public int addPropagate(
      TraceNode.Op op,
      List<OperandRef> operands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.PROPAGATE,
        op,
        operands,
        note,
        filePath,
        line,
        column,
        functionSig,
        List.of(parentId));
  }

  /**
   * Append a {@link TraceNode.Role#SANITIZER} node. Used to mark a TP→FP pivot (e.g. {@code
   * PreparedStatement.setString} on the path). AI dedup consumers treat SANITIZER nodes as strong
   * evidence for "the data was parameterised, the finding is likely FP".
   */
  public int addSanitizer(
      TraceNode.Op op,
      List<OperandRef> operands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.SANITIZER,
        op,
        operands,
        note,
        filePath,
        line,
        column,
        functionSig,
        List.of(parentId));
  }

  /**
   * Append a {@link TraceNode.Role#SUMMARY_FOLD} node recording a cross-function summary op being
   * applied (e.g. {@code Operation.PersistsParam(0)} from {@code UserService.save} into the
   * caller's dest). {@code calleeSig} / {@code paramIndex} are encoded in the {@link
   * OperandRef.CallRef} and a free {@link OperandRef.Free} ("param:0") so consumers do not need to
   * regex the note.
   */
  public int addSummaryFold(
      TraceNode.Op op,
      String calleeSig,
      int paramIndex,
      List<OperandRef> extraOperands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    List<OperandRef> operands = new ArrayList<>(3);
    operands.add(new OperandRef.CallRef(calleeSig));
    if (paramIndex >= 0) {
      operands.add(new OperandRef.Free("param:" + paramIndex));
    }
    if (extraOperands != null) {
      operands.addAll(extraOperands);
    }
    return addNode(
        TraceNode.Role.SUMMARY_FOLD,
        op,
        operands,
        note,
        filePath,
        line,
        column,
        functionSig,
        List.of(parentId));
  }

  /**
   * Append a {@link TraceNode.Role#SINK} node recording where the taint reaches a sensitive API.
   * The {@code op} is one of the {@code SINK_*} enum constants.
   */
  public int addSink(
      TraceNode.Op op,
      List<OperandRef> operands,
      List<Integer> parents,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.SINK, op, operands, note, filePath, line, column, functionSig, parents);
  }

  /** Convenience single-parent sink. */
  public int addSink(
      TraceNode.Op op,
      List<OperandRef> operands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.SINK,
        op,
        operands,
        note,
        filePath,
        line,
        column,
        functionSig,
        List.of(parentId));
  }

  /**
   * Append a {@link TraceNode.Role#ALIAS} node. The underlying {@code op} should be the propagation
   * op (MOVE / BINOP) the alias is realised by. Two or more {@code parents} identify the data-flow
   * paths that converge here.
   */
  public int addAlias(
      TraceNode.Op op,
      List<OperandRef> operands,
      List<Integer> parents,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.ALIAS, op, operands, note, filePath, line, column, functionSig, parents);
  }

  /**
   * Append a {@link TraceNode.Role#GUARD} node. Path guards (range checks, null checks, instanceof
   * checks) are recorded as GUARD-role nodes so consumers can see "this path was guarded before the
   * sink" without re-parsing the source.
   */
  public int addGuard(
      TraceNode.Op op,
      List<OperandRef> operands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.GUARD,
        op,
        operands,
        note,
        filePath,
        line,
        column,
        functionSig,
        List.of(parentId));
  }

  /**
   * Append a {@link TraceNode.Role#VIOLATION} node ? the single-step structural-finding point (e.g.
   * use-after-free, resource-leak). Pass an empty {@code parents} for a root violation, or the
   * observation that triggered the finding (e.g. the close call site for a UAF).
   */
  public int addViolation(
      TraceNode.Op op,
      List<OperandRef> operands,
      List<Integer> parents,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addNode(
        TraceNode.Role.VIOLATION, op, operands, note, filePath, line, column, functionSig, parents);
  }

  /**
   * Single-parent convenience for {@link #addViolation(TraceNode.Op, List, List, String, String,
   * int, int, String)}.
   */
  public int addViolation(
      TraceNode.Op op,
      List<OperandRef> operands,
      int parentId,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig) {
    return addViolation(op, operands, List.of(parentId), note, filePath, line, column, functionSig);
  }

  // ---- compression ----

  /**
   * Mark nodes {@code [fromId..toId]} (inclusive on both ends) as part of a single compressed
   * segment represented by a fresh {@link TraceNode.Role#SUMMARIZER} node. The new node's {@code
   * note} carries the human-readable chain description; its {@code pruneTargetId} points to {@code
   * fromId} (the first node in the chain) so {@link Trace.CompressionLevel#FULL} can expand the
   * chain when requested.
   *
   * <p>This is a no-op when {@code fromId > toId} or when the range contains no nodes (the helper
   * asserts the bounds against the current node list).
   *
   * @return the id of the new SUMMARIZER node, or {@code -1} if the range was empty / invalid.
   */
  public int summarizeChain(int fromId, int toId, String note) {
    if (fromId < 0 || toId >= nodes.size() || fromId > toId) {
      return -1;
    }
    int summaryId =
        addNode(
            TraceNode.Role.SUMMARIZER,
            TraceNode.Op.COMPRESSED_CHAIN,
            List.of(),
            note == null ? "" : note,
            /* filePath */ nodes.get(fromId).filePath(),
            /* line */ nodes.get(fromId).line(),
            /* column */ nodes.get(fromId).column(),
            nodes.get(fromId).functionSig(),
            List.of(/* first node in chain as parent */ fromId));
    // Mark each node in the chain as pruned.
    for (int i = fromId; i <= toId; i++) {
      TraceNode n = nodes.get(i);
      n.setPruneTargetIdInternal(summaryId);
    }
    return summaryId;
  }

  // ---- materialisation ----

  /**
   * Build a {@link Trace} rooted at {@code sinkOrViolationIds}. Each id must currently be a {@code
   * SINK}-role or {@code VIOLATION}-role node. The trace walks back from each via {@link
   * TraceNode#parentIds()} to find the {@code SOURCE}-role root. The compression level controls how
   * many intermediate nodes are kept in the flattened {@code List<FlowStep>} view ({@link
   * Trace#toFlowSteps()}).
   *
   * <p>All parent/child edges are wired up by this method; before {@code build} is called the
   * {@link TraceNode#childIds()} lists are empty.
   */
  public Trace build(List<Integer> sinkOrViolationIds, Trace.CompressionLevel level) {
    Objects.requireNonNull(sinkOrViolationIds, "sinkOrViolationIds");
    Objects.requireNonNull(level, "level");
    // Wire up parent -> child edges. The DAG is acyclic
    // by construction (a node's id is strictly greater
    // than any parent's id because we never look back
    // when adding a node), so a single forward pass
    // suffices.
    for (TraceNode n : nodes) {
      for (int parentId : n.parentIds()) {
        // Sentinel: parentId == -1 means
        // "no parent / orphan root" — used
        // by eager dual-write when
        // a cross-fn summary fold is
        // added without a recovered
        // source handle. The node stands
        // alone in the DAG; the SARIF
        // / Plumb-UI consumer renders
        // it as a standalone trust
        // boundary. We silently drop
        // the orphan edge rather than
        // throwing.
        if (parentId == -1) continue;
        if (parentId < 0 || parentId >= nodes.size()) {
          throw new IllegalStateException(
              "TraceNode "
                  + n.id()
                  + " references unknown parent "
                  + parentId
                  + " (have "
                  + nodes.size()
                  + " nodes)");
        }
        nodes.get(parentId).addChildIdInternal(n.id());
      }
    }
    // The "root" of the trace is the SOURCE-role node
    // reachable from any of the sink/violation ids.
    // We pick the one with the longest ancestor chain
    // (deepest source) as the canonical root; this
    // matches the v1 behaviour where the SOURCE was
    // the first FlowStep appended.
    int rootId = findRootId(sinkOrViolationIds);
    return new Trace(List.copyOf(nodes), rootId, List.copyOf(sinkOrViolationIds), level);
  }

  private int findRootId(List<Integer> sinkOrViolationIds) {
    // Defensive: if no sinks were passed, return the
    // first node in the trace (if any) so the result
    // is still a valid (degenerate) Trace.
    if (sinkOrViolationIds.isEmpty()) {
      return nodes.isEmpty() ? -1 : 0;
    }
    int bestRoot = -1;
    int bestDepth = -1;
    for (int sinkId : sinkOrViolationIds) {
      int depth = 0;
      int cursor = sinkId;
      while (cursor >= 0 && depth < nodes.size()) {
        TraceNode n = nodes.get(cursor);
        if (n.role() == TraceNode.Role.SOURCE) {
          break;
        }
        if (n.parentIds().isEmpty()) {
          break;
        }
        // Walk the leftmost parent to keep the
        // search deterministic. When a node has
        // multiple parents we have to pick one —
        // we pick the first by construction
        // (TraceBuilder wires them in order).
        cursor = n.parentIds().get(0);
        depth++;
      }
      if (cursor >= 0 && nodes.get(cursor).role() == TraceNode.Role.SOURCE && depth > bestDepth) {
        bestDepth = depth;
        bestRoot = cursor;
      }
    }
    if (bestRoot < 0 && !nodes.isEmpty()) {
      // Fallback: any SOURCE we can find, else node 0.
      for (int i = 0; i < nodes.size(); i++) {
        if (nodes.get(i).role() == TraceNode.Role.SOURCE) {
          return i;
        }
      }
      return 0;
    }
    return bestRoot;
  }

  // ---- internal ----

  private int addNode(
      TraceNode.Role role,
      TraceNode.Op op,
      List<OperandRef> operands,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig,
      List<Integer> parents) {
    int id = nextId++;
    TraceNode.Builder b = new TraceNode.Builder(id);
    b.role = role;
    b.op = op;
    b.operands = operands;
    b.note = note;
    b.filePath = filePath;
    b.line = line;
    b.column = column;
    b.functionSig = functionSig;
    b.parentIds = parents;
    nodes.add(TraceNode.build(b));
    return id;
  }

  /**
   * Package-private entry point used by {@link Trace#fromFlowSteps(List)} to construct a
   * v1-imported node. Mirrors {@link #addNode} but is callable from outside the {@code
   * TraceBuilder} class. Not part of the public API ? v2 checkers should use the {@code addSource}
   * / {@code addPropagate} / etc. helpers above, which wire parent / child edges consistently.
   */
  int addNodeForCompat(
      TraceNode.Role role,
      TraceNode.Op op,
      List<OperandRef> operands,
      String note,
      String filePath,
      int line,
      int column,
      String functionSig,
      List<Integer> parents) {
    return addNode(role, op, operands, note, filePath, line, column, functionSig, parents);
  }
}
