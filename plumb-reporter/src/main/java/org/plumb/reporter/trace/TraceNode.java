/*
 * R-802: TraceNode ? a single node in a structured
 * source-to-sink trace (DAG, not list).
 *
 * <p>Replaces {@code List<FlowStep>} for new DFA
 * checkers; existing v1 callers continue to emit
 * {@link FlowStep} which {@link Trace#toFlowSteps()}
 * flattens back. The two coexist during the P2 / P4
 * migration window; v2 supersedes v1 once all checkers
 * have been ported.</p>
 *
 * <h2>Identity</h2>
 *
 * <p>Each node has an int {@link #id()} that is unique
 * within a single {@link Trace}. The {@code id} is
 * assigned by {@link TraceBuilder} at construction time
 * and is the handle used by parents / children / prune
 * links. Cross-trace id reuse is fine; reporters serialize
 * one trace at a time.</p>
 *
 * <h2>Role vs Op</h2>
 *
 * <p>{@link Role} describes <em>where in the trace</em>
 * the node sits (source / propagate / sanitizer / sink /
 * violation). {@link Op} describes <em>what concrete
 * operation</em> the node represents (MOVE / BINOP_ADD /
 * CALL_INSTANCE / SINK_SQL / etc). The two are orthogonal
 * — a {@code PROPAGATE}-role node can have any {@code
 * Op}, and the same {@code Op} can be reused across roles
 * (e.g. {@code CALL_INSTANCE} as a propagate step vs as
 * a sink step).</p>
 *
 * <h2>DAG shape</h2>
 *
 * <p>{@link #parentIds()} are the immediate data-flow
 * predecessors of this node. A node may have multiple
 * parents (e.g. a {@code BINOP_ADD} of two tainted
 * operands has both tainted operands as parents). A
 * single parent is the common case (MOVE / FIELDLOAD).
 * {@link #pruneTargetId()} is the id of the
 * {@link Role#SUMMARIZER}-role node that represents this
 * node in compressed views; {@code -1} when the node is
 * not part of any compressed segment.</p>
 */
package org.plumb.reporter.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TraceNode {

  /** Where in the trace the node sits. */
  public enum Role {
    /**
     * Entry point: function param, input-source call, explicit {@code markSource}, or an {@code
     * UntrustedSource} fact.
     */
    SOURCE,
    /** Internal data-flow step: MOVE / BINOP / FIELDLOAD / UNARYOP / CAST / etc. */
    PROPAGATE,
    /**
     * Sanitizer (parameterised binding / encode / allow-list) seen on the path. AI dedup can use
     * this as a TP→FP signal.
     */
    SANITIZER,
    /**
     * Cross-function summary op applied: {@code SUMMARY_PERSISTS_PARAM} / {@code
     * SUMMARY_WRITE_TO_FIELD} / {@code SUMMARY_TAINT_PROPAGATE} / etc.
     */
    SUMMARY_FOLD,
    /**
     * Two data-flow paths merged here. The node itself is a {@code PROPAGATE} op (typically MOVE or
     * BINOP) but it is also the convergence point of multiple tainted operands.
     */
    ALIAS,
    /** Path-condition guard (e.g. {@code if (i < n)}) observed on the path. */
    GUARD,
    /** The data-flow sink — the call site where the taint reaches a sensitive API. */
    SINK,
    /**
     * The single-step violation point for a structural finding where the checker does not propagate
     * data (e.g. use-after-free, resource-leak, dead-code-by-typo).
     */
    VIOLATION,
    /**
     * Compressed-segment entry point. Used to represent a chain of propagated nodes that the {@link
     * Trace.CompressionLevel#SUMMARIZED} view collapses into a single node. The full chain is still
     * stored (linked via {@link #pruneTargetId()}) so {@link Trace.CompressionLevel#FULL} can
     * expand it.
     */
    SUMMARIZER
  }

  /**
   * Concrete operation the node represents. Enums (not strings) so consumers (AI dedup, SARIF
   * renderer) can dispatch on the value without regex parsing {@code expr}. New ops are added as
   * new checkers are ported; the set is intentionally not sealed because v1 checkers are still
   * being migrated.
   */
  public enum Op {
    // ---- propagation ops ----
    MOVE,
    BINOP_ADD,
    BINOP_SUB,
    BINOP_MUL,
    BINOP_DIV,
    BINOP_MOD,
    BINOP_SHL,
    BINOP_SHR,
    BINOP_USHR,
    BINOP_AND,
    BINOP_OR,
    BINOP_XOR,
    BINOP_EQ,
    BINOP_NE,
    BINOP_LT,
    BINOP_LE,
    BINOP_GT,
    BINOP_GE,
    UNARY_NEG,
    UNARY_NOT,
    UNARY_CAST,
    UNARY_PRE_INC,
    UNARY_POST_INC,
    UNARY_PRE_DEC,
    UNARY_POST_DEC,
    FIELDLOAD,
    FIELDSTORE,
    ARRAYLOAD,
    ARRAYSTORE,
    // ---- call ops ----
    CALL_STATIC,
    CALL_INSTANCE,
    CALL_CTOR,
    // ---- input sources ----
    INPUT_READLINE,
    INPUT_PARSEINT,
    INPUT_PARSE_LONG,
    INPUT_GETPARAMETER,
    INPUT_GETQUERYSTRING,
    INPUT_GETINPUTSTREAM,
    INPUT_REQUEST_BODY,
    INPUT_GETHEADER,
    INPUT_GETCOOKIE,
    // ---- sinks (mapped from sink-families.yaml) ----
    SINK_SQL,
    SINK_SHELL,
    SINK_FILE,
    SINK_NETWORK,
    SINK_LOG,
    SINK_RESPONSE_HEADER,
    SINK_REDIRECT,
    SINK_REFLECTION,
    SINK_JNI,
    SINK_JCR,
    SINK_OGNL,
    SINK_EL,
    SINK_XPATH,
    SINK_XSS,
    SINK_FORMAT_STRING,
    SINK_LDAP,
    SINK_JSP_INCLUDE,
    SINK_CLEARTEXT_TX,
    SINK_MEM_ALLOC,
    SINK_ARRAY_INDEX,
    SINK_DIV_ZERO,
    SINK_INT_OVERFLOW,
    SINK_INT_UNDERFLOW,
    SINK_LOOP_BOUND,
    SINK_NUMERIC_TRUNCATION,
    SINK_SENSITIVE_EXFIL,
    SINK_PII_EXFIL,
    SINK_SESSION,
    SINK_TRUST_BOUNDARY,
    SINK_NULL_DEREF,
    SINK_CRYPTO_INIT,
    SINK_SQL_INJECTION,
    // ---- single-step structural findings ----
    BAD_SHIFT,
    INSUFFICIENT_PERMISSIONS,
    EXPOSED_ENDPOINT,
    DEADLOCK_PAIR,
    ITERATOR_CME,
    COMPARE_WITH_SELF,
    COLLECTION_SELF_REF,
    OBSOLETE_API,
    COMPARE_CLASS_BY_NAME,
    CORS_WILDCARD,
    LOAD_NOT_NULL,
    UNCHECKED_RETURN,
    // R-812 — OrmUnnecessaryGetDfaChecker: a JPA
    // get/find call whose result is never used in a
    // content access. The DB hit is wasted; the
    // caller should use Session.load() /
    // EntityManager.getReference() instead.
    UNUSED_GET_RESULT,
    // R-812 — InsecureCryptoChecker: a
    // Cryptographic.getInstance("MD5" / "DES" / etc.)
    // call (direct literal OR via const variable
    // flow). Distinct from SINK_CRYPTO_INIT (which
    // is for missing init on a KeyGenerator etc.).
    WEAK_CRYPTO_ALGO,
    UNINIT_NONNULL,
    USE_AFTER_FREE,
    RESOURCE_LEAK,
    RECURSION,
    UNLOCK_NOT_LOCKED,
    INLINE_LITERAL_PASSWORD,
    INLINE_LITERAL_KEY,
    STATIC_API_KEY,
    HARDCODED_FIELD_PASSWORD,
    WEAK_PRNG,
    PREDICTABLE_RANDOM_SEED,
    PROCESS_CONTROL,
    SUSPICIOUS_COMMENT,
    CLEAR_TEXT_SOCKET,
    ERROR_MESSAGE_LEAK,
    INSUFFICIENT_SESSION_EXPIRATION,
    XXE,
    REGEX_DOS,
    NULL_DEREF_GENERIC,
    // ---- summary folds ----
    SUMMARY_PERSISTS_PARAM,
    SUMMARY_WRITE_TO_FIELD,
    SUMMARY_TAINT_PROPAGATE,
    SUMMARY_PASS_THROUGH,
    SUMMARY_SINK,
    SUMMARY_ALLOC,
    // ---- R-810: static-field declaration site ----
    // Emitted by the engine when a FIELDLOAD's
    // receiver is a class reference (e.g.
    // {@code Level.WARNING}, {@code IO.logger}) and
    // the {@link org.plumb.dfa.resolve.StaticFieldResolver}
    // can resolve the field's initializer to a
    // literal. Lets the v2 trace attach a real
    // parent to a static field access instead of
    // falling through to the orphan-SOURCE shape.
    STATIC_FIELD_DECL,
    // ---- sanitizer ops ----
    SANITIZER_PREPARED_STATEMENT,
    SANITIZER_URL_ENCODE,
    SANITIZER_HTML_ESCAPE,
    SANITIZER_FILENAME_NORMALIZE,
    SANITIZER_PATH_CANONICALIZE,
    SANITIZER_ALLOW_LIST,
    SANITIZER_INTEGER_RANGE_CHECK,
    // ---- guards ----
    GUARD_BOUND,
    GUARD_NULL_CHECK,
    GUARD_INSTANCEOF,
    // ---- misc ----
    PARAM,
    LITERAL,
    MARK_SOURCE,
    COMPRESSED_CHAIN,
    // ---- fallbacks for ops the engine has not been
    //      taught to encode structurally yet ----
    UNKNOWN
  }

  private final int id;
  private final Role role;
  private final Op op;
  private final List<OperandRef> operands;
  private final String note;
  private final String filePath;
  private final int line;
  private final int column;
  private final String functionSig;
  private final List<Integer> parentIds;
  private final List<Integer> childIds;
  // Intentionally not final: {@link TraceBuilder}
  // wires the prune target during {@code build()}
  // (the chain-summarisation pass runs after every
  // node is in the list). The field is only ever
  // written by {@link #setPruneTargetIdInternal}
  // which is package-private — outside the package
  // the field is effectively immutable once the
  // {@link Trace} is built.
  private int pruneTargetId;

  private TraceNode(Builder b) {
    this.id = b.id;
    this.role = Objects.requireNonNull(b.role, "role");
    this.op = Objects.requireNonNull(b.op, "op");
    this.operands = b.operands == null ? List.of() : List.copyOf(b.operands);
    this.note = b.note == null ? "" : b.note;
    this.filePath = b.filePath == null ? "" : b.filePath;
    this.line = b.line;
    this.column = b.column;
    this.functionSig = b.functionSig == null ? "" : b.functionSig;
    this.parentIds = b.parentIds == null ? List.of() : List.copyOf(b.parentIds);
    this.childIds = new ArrayList<>(2);
    this.pruneTargetId = b.pruneTargetId;
  }

  /** Internal builder; {@link TraceBuilder} is the only legitimate caller. */
  static final class Builder {
    final int id;
    Role role;
    Op op;
    List<OperandRef> operands;
    String note;
    String filePath;
    int line;
    int column;
    String functionSig;
    List<Integer> parentIds;
    int pruneTargetId = -1;

    Builder(int id) {
      this.id = id;
    }
  }

  static TraceNode build(Builder b) {
    return new TraceNode(b);
  }

  public int id() {
    return id;
  }

  public Role role() {
    return role;
  }

  public Op op() {
    return op;
  }

  public List<OperandRef> operands() {
    return operands;
  }

  public String note() {
    return note;
  }

  public String filePath() {
    return filePath;
  }

  public int line() {
    return line;
  }

  public int column() {
    return column;
  }

  public String functionSig() {
    return functionSig;
  }

  public List<Integer> parentIds() {
    return parentIds;
  }

  public List<Integer> childIds() {
    return Collections.unmodifiableList(childIds);
  }

  public int pruneTargetId() {
    return pruneTargetId;
  }

  /**
   * Internal: record that {@code childId} has this node as a parent. Called by {@link TraceBuilder}
   * as it wires up edges.
   */
  void addChildIdInternal(int childId) {
    this.childIds.add(childId);
  }

  /**
   * Internal: set the prune target id. Called by {@link TraceBuilder#summarizeChain(int, int,
   * String)} as it marks a chain of nodes as represented by a single SUMMARIZER. The field is
   * mutable only at the package boundary (settable until {@link Trace} is constructed; read-only
   * thereafter from outside the package).
   */
  void setPruneTargetIdInternal(int pruneTargetId) {
    this.pruneTargetId = pruneTargetId;
  }

  @Override
  public String toString() {
    return "TraceNode{"
        + id
        + " "
        + role
        + " "
        + op
        + " parents="
        + parentIds
        + " children="
        + childIds
        + (pruneTargetId >= 0 ? " pruneTarget=" + pruneTargetId : "")
        + "}";
  }
}
