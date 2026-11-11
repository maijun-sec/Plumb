/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.dfa.summary;

import java.util.LinkedHashSet;
import java.util.List;
import org.plumb.tac.ir.CalleeKeyNormalizer;

/**
 * The summary-cache keys one call site may find its callee's summary under.
 *
 * <h2>Why the class is part of the key</h2>
 *
 * <p>A summary describes ONE method. Two methods of the same name and arity in two classes are two
 * different methods, so a key that leaves the class out addresses both of them at once. Whichever
 * summary was written last (or first) is the one that comes back, and the caller gets another
 * method's facts.
 *
 * <p>That is not a theoretical hazard. A merged scan of a corpus that has one file per test case
 * holds dozens of classes that each declare a method with the same name -- {@code bad_source()} is
 * the classic one -- and every one of them writes its summary under the same bare key. The bare key
 * therefore holds ONE class's summary, and every other class's call site reads it.
 *
 * <h2>What the two ends of a lookup have to agree on</h2>
 *
 * <p>The engine writes a summary under the class that DECLARES the method, spelled the way the IR
 * spells the declaring class. The call site has to ask under the same string, or it finds nothing.
 * So:
 *
 * <ul>
 *   <li>the write end uses the declaring class verbatim -- its package included, because that is
 *       what the declaration says;
 *   <li>the read end uses the class it can actually name. A call that names no class at all names
 *       the caller's own class, and the caller knows that one. A call that names its receiver's
 *       class by its simple name cannot be resolved to a package here, so the simple name is used
 *       as far as it goes.
 * </ul>
 *
 * <p>The order the candidates are tried in is the whole point: the most specific key first, so the
 * ambiguous ones are only reached when the specific one is absent.
 *
 * <h2>Why this lives in one place</h2>
 *
 * <p>Four checkers read the summary cache, and each of them used to spell its own fallback chain. A
 * chain written four times is a chain that drifts four ways, and the drift is invisible: the scan
 * still succeeds, it just answers a question about a different method. So the candidate list is
 * built here, once, and every reader uses it.
 */
public final class CalleeSummaryKeys {

  private CalleeSummaryKeys() {}

  /**
   * A summary a call site resolved to, and the key it was found under.
   *
   * <p>The key matters as much as the summary: it is what the context-sensitive projection is
   * cached under, so two call sites that reach DIFFERENT methods of the same name keep their
   * projections apart.
   *
   * @param key the cache key the summary was found under, or null when nothing was found
   * @param summary the summary, never null
   */
  public record Resolution(String key, FunctionSummary summary) {

    /** Whether the resolution carries no usable summary. */
    public boolean isEmpty() {
      return summary == null || (summary.isTrivial() && !summary.hasBody());
    }
  }

  /**
   * The key the engine stores a summary under when the function has an ALIAS name.
   *
   * <p>The alias is the method's name and arity ({@code setData/1}); the class that declares it
   * goes in front of it, separated by {@code " :: "}. The class is spelled the way the IR spells
   * the declaring class -- with its package -- because that is the identity of the class, and the
   * package is what tells two same-named classes apart.
   *
   * @param enclosingClass the declaring class, or null/empty when the function has no class
   * @param alias the method's name and arity
   * @return the cache key
   */
  public static String aliasKey(String enclosingClass, String alias) {
    if (enclosingClass == null || enclosingClass.isEmpty()) return alias;
    return enclosingClass + " :: " + alias;
  }

  /**
   * The key the engine stores a summary under when the key names the class and the method.
   *
   * @param className the declaring class, or null/empty when there is none
   * @param methodAndArity the method's name and arity
   * @return the cache key
   */
  public static String qualifiedKey(String className, String methodAndArity) {
    if (className == null || className.isEmpty()) return methodAndArity;
    return className + "." + methodAndArity;
  }

  /**
   * The candidate keys a call site may find its callee's summary under, most specific first.
   *
   * <p>The order is:
   *
   * <ol>
   *   <li>the call site's own spelling, when it carries a parameter list -- such a signature names
   *       the declaration itself, so nothing can be more specific than it;
   *   <li>the class-qualified keys, with the class spelled as fully as the call site can spell it;
   *   <li>the class-qualified keys, with the class reduced to its simple name -- these address
   *       every class of that name in every package, so they come after the specific ones;
   *   <li>the bare key, which addresses every method of that name and arity in every class.
   * </ol>
   *
   * <p>A call that names no class is a call to a method of the CALLER's own class -- that is what
   * an unqualified call means -- so the caller's class is the class the lookup asks under. That is
   * the only way a call site can name its own class precisely, and it is what keeps two classes
   * that each declare a method of the same name from reading each other's summary.
   *
   * @param calleeSig the callee signature as the IR spells it
   * @param callerEnclosingClass the class of the function the call sits in, or null when it has
   *     none
   * @return the candidate keys, most specific first
   */
  public static List<String> keysFor(String calleeSig, String callerEnclosingClass) {
    if (calleeSig == null || calleeSig.isEmpty()) return List.of();
    LinkedHashSet<String> keys = new LinkedHashSet<>();

    // A signature that names the declaration itself is the most specific spelling there is:
    // one that carries its parameter list ("run(java.lang.String):void") or that names the
    // declaring class with a hash ("pkg.Class#run(int):void"). Nothing can be more exact than
    // that, so it comes first.
    if (calleeSig.indexOf('(') >= 0 || calleeSig.indexOf('#') >= 0) keys.add(calleeSig);

    String canonical = CalleeKeyNormalizer.canonicalCalleeKey(calleeSig);
    if (canonical == null) return List.copyOf(keys);
    String bare = CalleeKeyNormalizer.bareCalleeKey(canonical);
    if (bare == null || bare.isEmpty()) return List.copyOf(keys);

    String owner = ownerOf(canonical);
    String callerClass = blankToNull(callerEnclosingClass);
    String callerSimple = simpleName(callerClass);

    if (owner == null) {
      // The call names no class: the callee is a method of the caller's own class.
      addClassKeys(keys, callerClass, bare);
      addClassKeys(keys, callerSimple, bare);
    } else {
      String ownerSimple = simpleName(owner);
      // An owner that carries a package names the class exactly.
      if (owner.indexOf('.') >= 0) {
        addClassKeys(keys, owner, bare);
      }
      addClassKeys(keys, ownerSimple, bare);
      // A call whose receiver is the caller's own class names it by its simple name, so the
      // caller's own class is the one the lookup has to ask under.
      if (callerClass != null && ownerSimple != null && ownerSimple.equals(callerSimple)) {
        addClassKeys(keys, callerClass, bare);
      }
    }
    // The bare key addresses every method of that name and arity, in every class. It is the last
    // resort, because whichever class wrote it first owns it.
    keys.add(bare);
    return List.copyOf(keys);
  }

  /**
   * The summary a call site resolves to, and the key it was found under.
   *
   * <p>The candidates are tried in order, and the first one that holds a usable summary wins. A
   * summary is usable when it carries facts or when it knows it has a body: the second is the
   * getter / setter / passthrough shape, whose facts are needed even though it carries none.
   *
   * @param cache the summary cache to read
   * @param calleeSig the callee signature as the IR spells it
   * @param callerEnclosingClass the class of the function the call sits in, or null when it has
   *     none
   * @return the resolution; never null, and the summary is never null
   */
  public static Resolution lookup(
      FunctionSummaryCache cache, String calleeSig, String callerEnclosingClass) {
    if (cache == null) return new Resolution(null, FunctionSummary.empty());
    FunctionSummary first = null;
    String firstKey = null;
    for (String key : keysFor(calleeSig, callerEnclosingClass)) {
      FunctionSummary candidate = cache.get(key);
      if (candidate == null) continue;
      if (!candidate.isTrivial() || candidate.hasBody()) {
        return new Resolution(key, candidate);
      }
      if (first == null) {
        first = candidate;
        firstKey = key;
      }
    }
    if (first != null) return new Resolution(firstKey, first);
    // A signature shape the canonicaliser does not know still gets one chance under the spelling
    // the call site used, so an unusual callee is not lost.
    return new Resolution(calleeSig, cache.get(calleeSig));
  }

  /** Add the two ways a class-qualified key is written: the alias form and the dotted form. */
  private static void addClassKeys(LinkedHashSet<String> keys, String className, String bare) {
    if (className == null || className.isEmpty()) return;
    keys.add(aliasKey(className, bare));
    keys.add(qualifiedKey(className, bare));
  }

  /**
   * The owner of a canonical key, or null when the key names no class.
   *
   * @param canonical the canonical callee key
   * @return the owner, or null
   */
  private static String ownerOf(String canonical) {
    int slash = canonical.lastIndexOf('/');
    String qualified = slash < 0 ? canonical : canonical.substring(0, slash);
    int dot = qualified.lastIndexOf('.');
    return dot < 0 ? null : qualified.substring(0, dot);
  }

  /**
   * The simple name of a class: the last dot-separated segment.
   *
   * <p>The last DOT, not the last {@code '$'}: a nested class is {@code Outer$Inner}, and the
   * engine's own keys spell it that way, so the simple form has to match it.
   *
   * @param qualified the class name, qualified or not
   * @return the simple name, or null when there is no name to reduce
   */
  public static String simpleName(String qualified) {
    if (qualified == null || qualified.isEmpty()) return null;
    int dot = qualified.lastIndexOf('.');
    String simple = dot < 0 ? qualified : qualified.substring(dot + 1);
    return simple.isEmpty() ? null : simple;
  }

  private static String blankToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
