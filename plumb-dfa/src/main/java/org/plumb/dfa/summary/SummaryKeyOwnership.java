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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.tac.ir.CalleeKeyNormalizer;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * Which summary key belongs to which method -- and therefore which keys may be published at all.
 *
 * <h2>Why a key can be UNSAFE to publish</h2>
 *
 * <p>The engine stores one method's summary under SEVERAL spellings, so that a call site finds it
 * whichever way the callee is named there: the class-qualified form, the bare form, and the alias
 * form. Most of those spellings name ONE class, so they address one method and are safe. One of
 * them does not: the <b>bare</b> form ({@code bad_source/0}) names no class at all, so it addresses
 * EVERY method of that name and arity in the whole program.
 *
 * <p>When two classes declare a method with the same name and arity -- the ordinary case in a
 * corpus that has one file per test case -- the bare key can hold only ONE of their summaries, and
 * which one it holds is decided by <b>who wrote first</b>. The engine's schedule decides that. So
 * the summary a call site reads is a function of the analysis order, and the findings move when the
 * order moves, without the engine having got anything wrong.
 *
 * <p>The same shape appears on the SIMPLE-NAME key ({@code UserStore.run/1}): it names a class, but
 * only by its simple name, so two classes of that name in two packages share it.
 *
 * <h2>The rule</h2>
 *
 * <p>A key is published only when <b>exactly one method of the program claims it</b>. A key claimed
 * by two methods is not published at all: the call site then finds no summary under it and falls
 * through to the more specific keys, and if none of those match either, it finds nothing at all.
 *
 * <p>That is deliberate. The alternative -- publish it and let the first writer win -- answers a
 * question about <b>another</b> method, and a wrong answer is a false positive. Publishing nothing
 * answers the question with silence, and silence is recoverable: the finding is missing, not wrong.
 *
 * <h2>Why the index is built BEFORE the analysis</h2>
 *
 * <p>Whether a key is shared is a property of the PROGRAM, not of the order in which the engine
 * happens to visit its functions. So the index is computed from the program once, before any
 * summary is written. A conflict that is discovered halfway through the run cannot retract a
 * summary that was already read -- so discovering it late would leave the order dependence in place
 * for exactly the findings that are read early.
 *
 * <h2>What this class does NOT decide</h2>
 *
 * <p>It does not decide which key a call site should ASK for. That is {@link CalleeSummaryKeys}'s
 * job, and the two are deliberately separate: the read side knows what the call site could name,
 * and this class knows what the program contains.
 */
public final class SummaryKeyOwnership {

  /** An index in which every key is claimed by exactly one method. */
  private static final SummaryKeyOwnership NOTHING_SHARED = new SummaryKeyOwnership(Map.of());

  /**
   * The methods that claim each key.
   *
   * <p>Only the keys claimed by MORE THAN ONE method are kept: the common case is the interesting
   * one at the write side (a key nobody else claims), and keeping only the conflicts keeps the
   * index small on a large program.
   */
  private final Map<String, Set<String>> claimantsByKey;

  private SummaryKeyOwnership(Map<String, Set<String>> claimantsByKey) {
    this.claimantsByKey = claimantsByKey;
  }

  /**
   * The index for a program.
   *
   * @param program the program being analysed, or null
   * @return the index; never null
   */
  public static SummaryKeyOwnership of(TacProgram program) {
    if (program == null) return NOTHING_SHARED;
    Map<String, Set<String>> claimants = new HashMap<>();
    for (TacFunction fn : program.functions()) {
      if (fn == null) continue;
      String identity = identityOf(fn);
      for (String key : publishedKeys(fn)) {
        if (key == null || key.isEmpty()) continue;
        if (key.equals(fn.signature())) continue;
        claimants.computeIfAbsent(key, k -> new HashSet<>()).add(identity);
      }
      // The function's OWN signature is a key too, and it is shared whenever two classes
      // declare a method with the same signature -- which is the ordinary case in a corpus
      // that has one file per test case. It is published by the engine under the signature,
      // so it is claimed here as well.
      String signature = fn.signature();
      if (signature != null && !signature.isEmpty()) {
        claimants.computeIfAbsent(signature, k -> new HashSet<>()).add(identity);
      }
    }
    Map<String, Set<String>> shared = new HashMap<>();
    claimants.forEach(
        (key, ids) -> {
          if (ids.size() > 1) shared.put(key, Set.copyOf(ids));
        });
    return new SummaryKeyOwnership(shared);
  }

  /**
   * A method's identity: the class that declares it and its signature.
   *
   * <p>The signature alone is NOT an identity: two classes of the same name in two packages have
   * methods with the SAME signature, and that is exactly the case this class exists to see.
   *
   * @param fn the function
   * @return the identity
   */
  public static String identityOf(TacFunction fn) {
    String enclosing = fn == null ? null : fn.enclosingClass();
    String cls = enclosing == null ? "" : enclosing;
    return cls + " :: " + (fn == null ? "" : fn.signature());
  }

  /**
   * Every key the engine publishes a function's summary under.
   *
   * <p>This is the ONE place that enumerates the write-side keys, and both the index and the write
   * side use it. A second enumeration would drift from the first, and a key that the index does not
   * know about is a key the index cannot vouch for -- which would put the order dependence back
   * through the back door.
   *
   * @param fn the function
   * @return the keys, in the order they are published; never null
   */
  public static List<String> publishedKeys(TacFunction fn) {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    if (fn == null) return List.of();
    String canonical = CalleeKeyNormalizer.canonicalCalleeKey(fn.signature());
    if (canonical == null) return List.of();
    String bare = CalleeKeyNormalizer.bareCalleeKey(canonical);

    // The DECLARING class, spelled the way the IR spells it. The enclosing class is the class
    // that DECLARES the method, and it is the only spelling that tells two same-named classes
    // apart: "modA/store.UserStore" and "modB/store.UserStore" are two classes, while
    // "UserStore" is two classes and a hope.
    //
    // This key is published whatever the signature's own owner says. A signature that already
    // names its owner ("UserStore.run/1") and one that names no owner at all ("run/1") both
    // describe the SAME method, and the class that declares it is known either way.
    String enclosing = fn.enclosingClass();
    if (enclosing != null && !enclosing.isEmpty() && bare != null && !bare.isEmpty()) {
      keys.add(CalleeSummaryKeys.qualifiedKey(enclosing, bare));
      // The same class under its simple name: a call site that knows only the simple name asks
      // for this one. It addresses EVERY class of that name, so it is shared whenever two classes
      // share a simple name -- and the ownership gate then withholds it. Silence is recoverable;
      // another class's summary is not.
      String simple = CalleeSummaryKeys.simpleName(enclosing);
      if (simple != null && !simple.equals(enclosing)) {
        keys.add(CalleeSummaryKeys.qualifiedKey(simple, bare));
      }
    }

    keys.add(canonical);
    if (bare != null && !bare.isEmpty()) keys.add(bare);
    return List.copyOf(keys);
  }

  /**
   * Whether the key may be published for this method.
   *
   * <p>A key nobody else claims is safe. A key two methods claim is not published at all, so no
   * call site can read another method's facts under it.
   *
   * @param key the key
   * @param identity the identity of the method that wants to publish it
   * @return true when the key is safe to publish
   */
  public boolean isExclusivelyClaimedBy(String key, String identity) {
    if (key == null || key.isEmpty()) return false;
    Set<String> claimants = claimantsByKey.get(key);
    // A key the index does not know about was never claimed by anybody else.
    if (claimants == null) return true;
    return claimants.size() == 1 && claimants.contains(identity);
  }

  /**
   * The methods that share a key, for a report or a test.
   *
   * @param key the key
   * @return the identities that claim it; empty when the key is not shared
   */
  public Set<String> claimantsOf(String key) {
    Set<String> claimants = claimantsByKey.get(key);
    if (claimants == null) return Set.of();
    return new LinkedHashSet<>(claimants);
  }
}
