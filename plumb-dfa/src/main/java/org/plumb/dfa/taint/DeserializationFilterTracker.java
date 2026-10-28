package org.plumb.dfa.taint;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The deserialization filter: a JEP-290 {@code ObjectInputFilter} on an {@code ObjectInputStream}.
 *
 * <p><b>Why this exists.</b> {@code UnsafeDeserializationChecker}'s own specification names {@code
 * ObjectInputFilter} and {@code resolveClass} as sanitizers it must honour, and no such recognition
 * existed in the engine -- the names appeared in prose and nowhere else. A build that shipped the
 * fix for a deserialization CVE was still reported, which is a false positive on the one family
 * whose severity is CRITICAL.
 *
 * <p><b>What the mitigation has to look like.</b> Not every filter is a mitigation, and the
 * difference is the whole point. These three shapes are real code, and only the first two are safe:
 *
 * <pre>
 *   ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
 *       "java.security.KeyPair;java.security.PrivateKey;!*"));   // SAFE: a pattern
 *
 *   ois.setObjectInputFilter(info -&gt; {                          // SAFE: an allowlist
 *     ... if (name.startsWith("com.example.safe.")) return ALLOWED;
 *     return REJECTED;
 *   });
 *
 *   ois.setObjectInputFilter(info -&gt; {                          // NOT SAFE
 *     ... if (name.startsWith("java.lang.")) return ALLOWED;
 *     return UNDECIDED;          // everything else is still allowed through
 *   });
 * </pre>
 *
 * The third one is the partial mitigation: it filters the obvious classes and lets the rest
 * through, so the gadget classes still reach their constructors. A checker that only asked "is
 * there a filter?" would call it safe, and would be wrong.
 *
 * <h2>What counts as a restriction</h2>
 *
 * <p>The filter is a restriction when it can <em>reject</em>. A filter whose only outcomes are
 * ALLOWED and UNDECIDED cannot reject anything, so it restricts nothing -- that is the partial
 * mitigation, and it must still be reported.
 *
 * <p>Two shapes are recognised:
 *
 * <ul>
 *   <li>the <b>pattern</b> form, {@code ObjectInputFilter.Config.createFilter("...")}, whose
 *       pattern ends in {@code !*} -- the JEP-290 idiom for "reject everything else". A pattern
 *       without it allows every class the pattern does not mention;
 *   <li>the <b>lambda</b> form, whose body can return {@code REJECTED}.
 * </ul>
 *
 * <p>The lambda's body is read from the TAC: the filter argument is the destination of a call to
 * the synthetic {@code lambda$N}, and the lambda's own function is in the program. The body is
 * scanned for a return of {@code ObjectInputFilter.Status.REJECTED}, and a body that returns {@code
 * UNDECIDED} on every path is not a restriction.
 *
 * <h2>Why the filter is tracked per stream and not per value</h2>
 *
 * <p>A filter is a property of the STREAM, not of the bytes: the same untrusted bytes are safe
 * through a filtered stream and unsafe through an unfiltered one. So the tracker records, per
 * stream variable, whether that stream has a restricting filter -- and the sink check asks about
 * the RECEIVER it is about to report on.
 */
public final class DeserializationFilterTracker {

  /**
   * The call that installs a filter on a stream.
   *
   * <p>Matched on the SUFFIX, because the TAC's callee signature is not uniformly qualified: it
   * reads {@code java.io.ObjectInputStream.setObjectInputFilter/1} for a top-level class, but
   * {@code ObjectInputFilter.Config.createFilter/1} for a nested one -- the outer package is not
   * repeated. A prefix match would therefore fire for one shape and never for the other.
   */
  private static final String SET_FILTER = "ObjectInputStream.setObjectInputFilter";

  /** The call that builds a pattern filter. See {@link #SET_FILTER} for the matching rule. */
  private static final String CREATE_FILTER = "ObjectInputFilter.Config.createFilter";

  /** The status that means "this class is refused". */
  private static final String STATUS_REJECTED = "REJECTED";

  /**
   * The streams that have a filter which can reject.
   *
   * <p>Keyed by the stream variable's name. The name is the identity the TAC gives a local, and a
   * filter and the read that follows it are in the same function, so the name is enough to connect
   * them.
   */
  private final Map<String, String> restrictingStreams = new LinkedHashMap<>();

  /**
   * The filter arguments that are known to be pattern filters, and their pattern.
   *
   * <p>Keyed by the temporary that holds the built filter, so the {@code setObjectInputFilter} call
   * can look up the pattern that was built for it.
   */
  private final Map<String, String> patternFilters = new LinkedHashMap<>();

  /**
   * The lambda bodies that can reject, by the lambda's function name.
   *
   * <p>Populated by {@link #indexLambdaBodies}, which runs over the whole program before the
   * per-function walk, because a filter's lambda may be defined in a different function than the
   * one that installs it.
   */
  private final Map<String, String> rejectingLambdas = new LinkedHashMap<>();

  /**
   * Index the lambda bodies that can reject a class.
   *
   * <p>A body qualifies when it returns {@code ObjectInputFilter.Status.REJECTED}. A body that only
   * ever returns ALLOWED or UNDECIDED does not, and neither does one that returns nothing at all.
   *
   * @param program the whole program
   */
  public void indexLambdaBodies(Iterable<TacFunction> functions) {
    for (TacFunction fn : functions) {
      if (!isFilterLambdaName(fn.name())) {
        continue;
      }
      if (bodyCanReject(fn)) {
        rejectingLambdas.put(fn.name(), fn.name());
      }
    }
  }

  /**
   * Whether this lambda's body can reject a class.
   *
   * <p>The status constants are static fields of {@code ObjectInputFilter.Status}, so the TAC holds
   * them as a FIELDLOAD whose field name is the constant. A body qualifies when it reads {@code
   * REJECTED} at all: a filter that can reject on some path restricts on that path, and a filter
   * that never reads REJECTED cannot reject anything.
   *
   * <p>That is exactly the distinction that matters. The partial mitigation reads ALLOWED and
   * UNDECIDED and never REJECTED, so it is not a restriction and the finding must stand.
   */
  private static boolean bodyCanReject(TacFunction fn) {
    for (TacInstruction ins : fn.instructions()) {
      if (ins.kind != TacInstruction.Kind.FIELDLOAD) {
        continue;
      }
      if (STATUS_REJECTED.equals(ins.fieldName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a lambda's name is one the translator generated for a filter.
   *
   * <p>The translator names a lambda {@code lambda$N}, and the binding is by the variable it is
   * assigned to. A filter's argument is always such a binding, so the name is enough.
   */
  private static boolean isFilterLambdaName(String functionName) {
    return functionName != null && functionName.contains("lambda$");
  }

  /**
   * Record a call that installs a filter, and decide whether it restricts.
   *
   * @param ins the instruction
   */
  public void observe(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.CALL) {
      return;
    }
    String callee = ins.calleeSig;
    if (callee == null) {
      return;
    }

    if (isCallTo(callee, CREATE_FILTER)) {
      // A built pattern filter. The pattern is the argument; a pattern that
      // ends in "!*" refuses everything it does not list, and that is the
      // JEP-290 idiom for a restriction.
      String pattern = literalArgument(ins, 1);
      if (pattern == null) {
        return;
      }
      if (ins.dest instanceof TacOperand.TacVar dest) {
        patternFilters.put(dest.name(), pattern);
      }
      return;
    }

    if (!isCallTo(callee, SET_FILTER)) {
      return;
    }
    // The stream is the receiver, which is the FIRST argument in the TAC's
    // encoding of an instance call. The filter is the second.
    if (ins.args.size() < 2) {
      return;
    }
    TacOperand receiver = ins.args.get(0);
    TacOperand filter = ins.args.get(1);
    if (!(receiver instanceof TacOperand.TacVar stream)) {
      return;
    }
    if (isRestricting(filter)) {
      restrictingStreams.put(stream.name(), "filter");
    }
  }

  /**
   * Whether a callee signature names the given method.
   *
   * <p>The signature is {@code scope.method/arity}, and the scope is not uniform: a top-level class
   * carries its full package ({@code java.io.ObjectInputStream.setObjectInputFilter/1}), while a
   * nested class does not repeat the outer package ({@code
   * ObjectInputFilter.Config.createFilter/1}). So the method name is matched as a SUFFIX of the
   * scope-qualified part, with the arity stripped.
   *
   * <p>The arity is stripped rather than compared, because the same method can be called with
   * different arities across overloads, and the mitigation does not depend on which overload was
   * chosen.
   *
   * @param callee the callee signature
   * @param method the method name to look for
   * @return true when the signature names that method
   */
  private static boolean isCallTo(String callee, String method) {
    if (callee == null || method == null) {
      return false;
    }
    int slash = callee.indexOf('/');
    String withoutArity = slash < 0 ? callee : callee.substring(0, slash);
    return withoutArity.endsWith(method);
  }

  /**
   * Whether the filter argument restricts.
   *
   * <p>Two shapes count, and they are read from the TAC:
   *
   * <ul>
   *   <li>a pattern filter whose pattern ends in {@code !*};
   *   <li>a lambda whose body can return {@code REJECTED}.
   * </ul>
   */
  private boolean isRestricting(TacOperand filter) {
    if (filter instanceof TacOperand.TacVar v) {
      String pattern = patternFilters.get(v.name());
      if (pattern != null) {
        return isRestrictingPattern(pattern);
      }
    }
    // A lambda filter: the argument is the temporary that the synthetic
    // lambda call wrote to, so the lambda's name is in the CALL that
    // produced it. The tracker records the mapping when it sees that call.
    String lambdaName = lambdaFor(filter);
    if (lambdaName != null) {
      return rejectingLambdas.containsKey(lambdaName);
    }
    return false;
  }

  /**
   * The name of the lambda a temporary holds, or {@code null}.
   *
   * <p>Populated by {@link #observeLambdaBindings}, which sees the {@code lambda$N} call and
   * remembers which temporary it wrote.
   */
  private final Map<String, String> lambdaOfTemp = new LinkedHashMap<>();

  /** Record which temporary a synthetic lambda call wrote to. */
  public void observeLambdaBindings(TacInstruction ins) {
    if (ins.kind != TacInstruction.Kind.CALL) {
      return;
    }
    String callee = ins.calleeSig;
    if (callee == null || !callee.contains("lambda$")) {
      return;
    }
    if (ins.dest instanceof TacOperand.TacVar dest) {
      String name = lambdaFunctionName(callee);
      if (name != null) {
        lambdaOfTemp.put(dest.name(), name);
      }
    }
  }

  /**
   * The lambda function's name from a callee signature.
   *
   * <p>The signature is {@code scope.lambda$0/1}, so the function's name is the part before the
   * slash, and the scope prefix is dropped.
   */
  private static String lambdaFunctionName(String callee) {
    int slash = callee.indexOf('/');
    String withoutArity = slash < 0 ? callee : callee.substring(0, slash);
    int dot = withoutArity.lastIndexOf('.');
    return dot < 0 ? withoutArity : withoutArity.substring(dot + 1);
  }

  /** The lambda a temporary holds, or {@code null}. */
  private String lambdaFor(TacOperand operand) {
    if (!(operand instanceof TacOperand.TacVar v)) {
      return null;
    }
    return lambdaOfTemp.get(v.name());
  }

  /**
   * Whether a JEP-290 pattern restricts.
   *
   * <p>The idiom for a restriction is a trailing {@code !*}: "allow these, refuse everything else".
   * A pattern without it allows every class it does not mention, so it restricts nothing.
   */
  private static boolean isRestrictingPattern(String pattern) {
    String trimmed = pattern.trim();
    return trimmed.endsWith("!*");
  }

  /** The literal value of an argument, or {@code null} when it is not a literal. */
  private static String literalArgument(TacInstruction ins, int index) {
    if (index >= ins.args.size()) {
      return null;
    }
    TacOperand arg = ins.args.get(index);
    if (arg instanceof TacOperand.Const c && c.kind() == TacOperand.Const.Kind.STRING) {
      Object value = c.value();
      return value == null ? null : String.valueOf(value);
    }
    return null;
  }

  /**
   * Whether this stream has a filter that can reject.
   *
   * <p>The sink check asks this about the RECEIVER it is about to report on, because the filter is
   * a property of the stream and the bytes are the same either way.
   *
   * @param stream the stream variable
   * @return true when the stream's filter restricts
   */
  public boolean restricts(TacOperand.TacVar stream) {
    return stream != null && restrictingStreams.containsKey(stream.name());
  }

  /** Forget everything. Called at every function boundary. */
  public void clear() {
    restrictingStreams.clear();
    patternFilters.clear();
    lambdaOfTemp.clear();
  }

  /** The streams that currently have a restricting filter. For the tests. */
  public Set<String> restrictedStreams() {
    return new LinkedHashSet<>(restrictingStreams.keySet());
  }
}
