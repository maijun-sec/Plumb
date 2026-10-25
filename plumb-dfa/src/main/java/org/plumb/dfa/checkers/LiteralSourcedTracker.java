/*
 * Shared per-function tracker for
 * literal-sourced data flow.
 *
 * <p>The hard-coded credential checkers
 * ({@link HardCodedPasswordSinkChecker}
 * and {@link HardCodedKeyChecker}) all
 * implement the same per-function
 * DFA pattern:
 *
 * <ol>
 *   <li><b>Track MOVE-from-literal</b>:
 *       a {@code MOVE} whose source is a
 *       string {@link TacOperand.Const}
 *       marks the dest var as
 *       "literal-sourced".</li>
 *   <li><b>Track derived-of-literal</b>:
 *       a {@code CALL} to
 *       {@code toCharArray()} or
 *       {@code getBytes(...)} on a
 *       literal-sourced receiver (or on
 *       an inline literal receiver, the
 *       inline case) marks the result as
 *       "derived-of-literal".</li>
 *   <li><b>Sink check</b>: at a credential
 *       sink call/ctor, the password/key
 *       slot is checked against the
 *       literal-sourced and
 *       derived-of-literal maps; if found,
 *       the literal is the source.</li>
 * </ol>
 *
 * <p>Earlier each checker reimplemented
 * this state with its own pair of
 * {@code HashMap}s. This class extracts
 * the shared logic so future credential
 * checkers (e.g. CWE-798 hard-coded
 * credentials in test data) can reuse it
 * without duplicating the maps and the
 * MOVE/CALL handling.</p>
 */
package org.plumb.dfa.checkers;

import java.util.HashMap;
import java.util.Map;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Per-function state shared by hard-coded credential checkers. Tracks "this var was last assigned
 * from a literal string" and "this var is the result of {@code toCharArray()} or {@code
 * getBytes(...)} on a literal source". Cleared at every function boundary by {@link #clear()}.
 *
 * <p>Thread safety: a single tracker is used by a single DFA pass over a single function. Each
 * function gets a fresh tracker (or an explicit {@link #clear()}).
 */
public final class LiteralSourcedTracker {

  /**
   * Default min length for a literal to be tracked. The 3-char minimum was the password-sink
   * default; longer keys use 4 chars.
   */
  public static final int MIN_TRACKED_LENGTH = 3;

  /**
   * Per-checker min length override. The default 0 means "use {@link #MIN_TRACKED_LENGTH}". Set to
   * 4 for the key checker to preserve the original key semantics; 0 (default) for the password
   * checker to use the 3-char minimum.
   */
  private final int minLengthOverride;

  /**
   * Vars whose last MOVE was from a string constant. The value is the literal (truncated at the
   * tracker's discretion, not here).
   */
  private final Map<TacOperand.TacVar, String> literalSourced = new HashMap<>();

  /**
   * line tracking: line of the MOVE that last assigned {@link TacOperand.TacVar} to a
   * literal-sourced value. Lets the v2 trace emit a {@code MARK_SOURCE} node with the correct line
   * for the literal origin, even when the {@code SecretKeySpec} / sink construction is several
   * statements later. Keyed by the same {@code TacVar} as {@link #literalSourced}.
   */
  private final Map<TacOperand.TacVar, Integer> literalSourcedLine = new HashMap<>();

  /**
   * Vars whose last assignment was a {@code toCharArray()} / {@code getBytes(...)} call on a
   * literal source. The value is the original literal (copied from {@link #literalSourced} or read
   * directly from an inline literal).
   */
  private final Map<TacOperand.TacVar, String> derivedOfLiteral = new HashMap<>();

  /**
   * line tracking: line of the {@code toCharArray} / {@code getBytes} passthrough call that last
   * assigned {@link TacOperand.TacVar} from a literal-sourced receiver. Same key space as {@link
   * #derivedOfLiteral}.
   */
  private final Map<TacOperand.TacVar, Integer> derivedOfLiteralLine = new HashMap<>();

  /** Default constructor (3-char min). */
  public LiteralSourcedTracker() {
    this(0);
  }

  /**
   * Constructor with min length override. Pass 0 to use the {@link #MIN_TRACKED_LENGTH} default;
   * pass 4 to enforce a stricter 4-char minimum (for hard-coded keys).
   */
  public LiteralSourcedTracker(int minLengthOverride) {
    this.minLengthOverride = minLengthOverride;
  }

  private int effectiveMinLength() {
    return minLengthOverride > 0 ? minLengthOverride : MIN_TRACKED_LENGTH;
  }

  /** Clear all state. Called per-function by the checker's {@code initFunction}. */
  public void clear() {
    literalSourced.clear();
    literalSourcedLine.clear();
    derivedOfLiteral.clear();
    derivedOfLiteralLine.clear();
  }

  /**
   * Handle a MOVE instruction. If the source is a string constant of at least the effective min
   * length chars, record the dest as literal-sourced.
   *
   * @return true if the MOVE was tracked
   */
  public boolean handleMove(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.MOVE) return false;
    if (!(ins.lhs1 instanceof TacOperand.Const c)) return false;
    if (c.kind() != TacOperand.Const.Kind.STRING) return false;
    if (!(ins.dest instanceof TacOperand.TacVar dv)) return false;
    String v = (String) c.value();
    if (v == null || v.length() < effectiveMinLength()) return false;
    literalSourced.put(dv, v);
    literalSourcedLine.put(dv, ins.line);
    return true;
  }

  /**
   * Handle a CALL instruction. If the callee is {@code toCharArray} or {@code getBytes} and the
   * receiver is a literal source, mark the result as derived-of-literal.
   *
   * <p>Two receiver cases are accepted (matching the inline-literal support):
   *
   * <ul>
   *   <li><b>TacVar</b>: the receiver is a tracked literal-sourced var (the MOVE-then-call case:
   *       {@code x = "literal"; t = x.toCharArray()}).
   *   <li><b>TacConst STRING</b>: the receiver is an inline literal TacConst (the inline case:
   *       {@code t = "literal".toCharArray()}; the IR builder lowers this to a CALL whose args[0]
   *       is the literal Const itself when the symbol solver doesn't resolve the String-type
   *       receiver).
   * </ul>
   *
   * @return true if the CALL was tracked
   */
  public boolean handlePassthroughCall(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.CALL) return false;
    if (ins.calleeSig == null) return false;
    if (!ins.calleeSig.contains(".toCharArray") && !ins.calleeSig.contains(".getBytes")) {
      return false;
    }
    if (!(ins.dest instanceof TacOperand.TacVar tv)) return false;
    if (ins.args.isEmpty()) return false;
    TacOperand recv = ins.args.get(0);
    String literal = null;
    if (recv instanceof TacOperand.TacVar rv) {
      literal = literalSourced.get(rv);
    } else if (recv instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING) {
      String v = (String) c.value();
      if (v != null && v.length() >= effectiveMinLength()) {
        literal = v;
      }
    }
    if (literal == null) return false;
    derivedOfLiteral.put(tv, literal);
    derivedOfLiteralLine.put(tv, ins.line);
    return true;
  }

  /**
   * Resolve an operand to its source literal, if any. The operand can be:
   *
   * <ul>
   *   <li>A {@link TacOperand.Const} STRING (inline literal at the call site).
   *   <li>A {@link TacOperand.TacVar} that's in the literal-sourced map (direct MOVE).
   *   <li>A {@link TacOperand.TacVar} that's in the derived-of-literal map (toCharArray/getBytes
   *       passthrough).
   * </ul>
   *
   * <p>Returns {@code null} if the operand is not derived from a literal.
   *
   * <p>The {@code minLength} parameter lets the caller apply a stricter length filter than the
   * tracker's own 3-char minimum. Credential checkers typically pass the same 3 here.
   */
  public String resolveLiteral(TacOperand pwd, int minLength) {
    String literal = null;
    if (pwd instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING) {
      String v = (String) c.value();
      if (v != null && v.length() >= minLength) literal = v;
    } else if (pwd instanceof TacOperand.TacVar tv) {
      String direct = literalSourced.get(tv);
      if (direct != null) literal = direct;
      else {
        String derived = derivedOfLiteral.get(tv);
        if (derived != null) literal = derived;
      }
    }
    return literal;
  }

  /** Read-only view of the literal-sourced map (for tests / debugging). */
  public Map<TacOperand.TacVar, String> literalSourcedView() {
    return java.util.Collections.unmodifiableMap(literalSourced);
  }

  /** Read-only view of the derived-of-literal map (for tests / debugging). */
  public Map<TacOperand.TacVar, String> derivedOfLiteralView() {
    return java.util.Collections.unmodifiableMap(derivedOfLiteral);
  }

  /**
   * line-of-origin lookup for a literal-sourced var. Returns the line of the MOVE that last
   * assigned {@code var} from a string constant, or {@code -1} if the var is not literal-sourced.
   * Used by v2 trace builders to emit {@code MARK_SOURCE} with the literal's actual source line,
   * not the sink line.
   */
  public int sourceLineOf(TacOperand.TacVar var) {
    Integer line = literalSourcedLine.get(var);
    return line == null ? -1 : line;
  }

  /**
   * line-of-origin lookup for a derived-of-literal var. Returns the line of the {@code toCharArray}
   * / {@code getBytes} call that last assigned {@code var} from a literal-sourced receiver, or
   * {@code -1} if the var is not derived-of-literal. Used by v2 trace builders to emit {@code MOVE}
   * or {@code FIELDLOAD} nodes with the correct passthrough line.
   */
  public int derivedLineOf(TacOperand.TacVar var) {
    Integer line = derivedOfLiteralLine.get(var);
    return line == null ? -1 : line;
  }
}
