package org.plumb.dfa.summary;

import java.util.Objects;
import java.util.Set;

/**
 * A taint-passthrough string operation observed inside a function. Recorded in the function's
 * {@link FunctionSummary} so that cross-function taint can survive summary fold.
 *
 * <p>Background: a function like {@code String helper(String s) { return s.toLowerCase(); }}
 * returns a value derived from its parameter. The caller-side taint checker sees the call {@code
 * dest = helper(tainted_arg)} and needs the summary to tell it "the result is tainted iff the arg
 * is tainted".
 *
 * <p>Previously, the only way to encode this was {@link Operation.TaintPropagate} on the parameter
 * (with a 1.0 confidence), which worked but lost the structural information (e.g. the callee used
 * {@code toLowerCase} -- the consumer couldn't distinguish passthrough-by-toLowerCase from
 * passthrough-by-String-returned-as-is). A dedicated {@code StringOp} preserves that structural
 * information for the consumers that care (e.g. a two-pass analyzer that needs to know the chain of
 * transformations).
 *
 * <h2>Recording</h2>
 *
 * <p>Populated by {@link org.plumb.dfa.taint.TaintedCheckerBase} in its {@code checkInstruction}
 * CALL branch, after the inline {@code propagateStringPassthroughs} fires. The recording only
 * happens when the source of the passthrough is one of the function's parameters (so the caller can
 * apply the same passthrough to its own arg).
 *
 * <h2>Application</h2>
 *
 * <p>When a caller-side checker sees a CALL whose callee has a {@code StringOp} in its summary, it
 * walks the summary's {@link FunctionSummary#stringOps()} list in order, applying each passthrough
 * to the corresponding caller's arg. The actual re-application logic is the consumer's concern;
 * this class only ships the data structure + recording.
 */
public record StringOp(String methodName) {

  private static final Set<String> KNOWN_PASSTHROUGHS =
      Set.of(
          "toLowerCase",
          "toUpperCase",
          "trim",
          "strip",
          "stripLeading",
          "stripTrailing",
          "replace",
          "replaceAll",
          "replaceFirst",
          "substring",
          "concat",
          "valueOf",
          "toString",
          "split",
          "intern");

  public StringOp {
    Objects.requireNonNull(methodName, "methodName");
    if (methodName.isEmpty()) {
      throw new IllegalArgumentException("methodName must be non-empty");
    }
  }

  /**
   * @return {@code true} if this StringOp's method is in the known-passthrough list (i.e. provably
   *     returns a String derived from its argument with no new trust boundary). Conservative: a
   *     method NOT in the list is still recorded; consumers that want to apply passthroughs can use
   *     this predicate as a quick filter.
   */
  public boolean isKnownPassthrough() {
    return KNOWN_PASSTHROUGHS.contains(methodName);
  }

  /**
   * The known-passthrough method names. Exposed for tests and for the two-pass analyzer to use as a
   * quick membership check without instantiating a StringOp.
   */
  public static Set<String> knownPassthroughs() {
    return KNOWN_PASSTHROUGHS;
  }
}
