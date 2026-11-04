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
package org.plumb.dfa.engine;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The WHOLE-PROGRAM facts a cross-function checker needs, shared by every worker.
 *
 * <p><b>Why this exists.</b> A checker that answers a question about the whole program -- "does
 * this callee throw?", "does this caller have a catch?" -- has to hold a fact about EVERY function.
 * It used to hold those facts in an INSTANCE field.
 *
 * <p>That works in a sequential run, because there is exactly one instance and it sees every
 * function. It does NOT work in a parallel run: each worker thread gets its OWN checker instances
 * (a checker carries per-function state, so a shared instance would have one worker's {@code
 * initFunction} erase another worker's facts mid-walk). Each worker's instance therefore sees only
 * the functions ITS worker analysed.
 *
 * <p>The consequence was silent and severe. {@code UncaughtExceptionPropagator} answers "the caller
 * has no catch and the callee throws", so it needs the callee's THROW fact -- which lives in a
 * DIFFERENT worker's instance. The lookup missed, the cross-function finding was never emitted, and
 * the scan reported success. A capability was missing while nothing said so.
 *
 * <p><b>What is shared and what is not.</b> The facts that are about the PROGRAM are shared here;
 * the facts that are about the FUNCTION being analysed stay in the checker instance. That split is
 * the whole point: a per-function fact has no meaning outside the function it describes, and a
 * whole-program fact is meaningless if only one worker knows it.
 *
 * <p>This store is the shared home for both kinds of question the cross-function checkers ask:
 *
 * <ul>
 *   <li>does this function THROW? ({@link #markThrows} / {@link #functionThrows})
 *   <li>does this function have a CATCH_HANDLER? ({@link #markCatches} / {@link #hasCatch})
 * </ul>
 *
 * <p>A checker that needs a whole-program fact of its own declares it here rather than in an
 * instance field. The store lives on the {@link AnalysisContext}, which the engine creates once per
 * run and shares by every worker.
 *
 * <p><b>Why the sets are concurrent.</b> Every worker writes to this store while it analyses its
 * own functions, concurrently with the other workers. A plain {@code HashSet} would lose writes and
 * could corrupt its own table, so the store uses {@link ConcurrentHashMap}-backed sets.
 *
 * <p><b>Why the facts are keyed by the MERGED key.</b> Two functions can share a name and an arity
 * -- two subclasses of the same abstract base both implementing {@code action(String)}. A
 * name/arity key would merge them, so a caller would be told "the callee throws" because a
 * DIFFERENT method with the same name throws. The merged key ({@code "<enclosingClass> ::
 * <signature>"}) keeps them apart.
 */
public final class ProgramFacts {

  /**
   * The merged keys of the functions that contain a THROW instruction.
   *
   * <p>Concurrent: every worker adds the functions it analysed.
   */
  private final Set<String> throwingFunctions = ConcurrentHashMap.newKeySet();

  /**
   * The merged keys of the functions that contain a CATCH_HANDLER instruction.
   *
   * <p>Concurrent, for the same reason as {@link #throwingFunctions}.
   */
  private final Set<String> catchingFunctions = ConcurrentHashMap.newKeySet();

  /**
   * The general-purpose shared maps, keyed by a checker-chosen name.
   *
   * <p>Not every whole-program fact is a yes/no about a function. Some checkers need to know which
   * functions did a {@code getAttribute(key)} and which did a {@code setAttribute(key, ...)}, or
   * which methods of a class read a field. Those facts have the same shape and the same failure
   * mode as the throw/catch facts -- they are about the PROGRAM, and an instance field would only
   * ever hold one worker's slice -- so they live here too.
   *
   * <p>A checker asks for its map by NAME, once, and keeps the reference. The map itself is
   * concurrent, so the workers can write to it concurrently.
   *
   * @param <K> the key type
   * @param <V> the value type
   */
  private final java.util.Map<String, java.util.Map<Object, Set<Object>>> sharedMaps =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The general-purpose shared values, keyed by a checker-chosen name.
   *
   * <p>For a fact whose VALUE is not a set -- the deadlock checker's lock sequence, for example.
   * The value is stored as-is; a checker that needs a set of values uses {@link #sharedMap}.
   *
   * @param <V> the value type
   * @param name the checker's name for this slot
   * @param initial the value to use when the slot is empty
   * @return the shared value
   */
  @SuppressWarnings("unchecked")
  public <V> V sharedValue(String name, V initial) {
    return (V) sharedValues.computeIfAbsent(name, k -> initial);
  }

  /** The general-purpose shared values, keyed by a checker-chosen name. */
  private final java.util.Map<String, Object> sharedValues =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * The shared map a checker uses for its own whole-program facts.
   *
   * <p>The map is created on the first call and returned on every later one, so a checker may call
   * this from {@code initEngine} and keep the reference, or call it on every use.
   *
   * @param <K> the key type
   * @param <V> the value type
   * @param name the checker's name for this map; two checkers with different names get different
   *     maps
   * @return the shared map
   */
  @SuppressWarnings("unchecked")
  public <K, V> java.util.Map<K, java.util.Set<V>> sharedMap(String name) {
    java.util.Map<Object, Set<Object>> holder =
        sharedMaps.computeIfAbsent(name, k -> new java.util.concurrent.ConcurrentHashMap<>());
    return (java.util.Map<K, java.util.Set<V>>) (java.util.Map<?, ?>) holder;
  }

  /**
   * Record that the function at {@code mergedKey} contains a THROW.
   *
   * @param mergedKey the function's merged key
   */
  public void markThrows(String mergedKey) {
    if (mergedKey != null) {
      throwingFunctions.add(mergedKey);
    }
  }

  /**
   * Record that the function at {@code mergedKey} contains a CATCH_HANDLER.
   *
   * @param mergedKey the function's merged key
   */
  public void markCatches(String mergedKey) {
    if (mergedKey != null) {
      catchingFunctions.add(mergedKey);
    }
  }

  /**
   * Does the function at {@code mergedKey} contain a THROW?
   *
   * @param mergedKey the function's merged key
   * @return true when some worker saw a THROW in that function
   */
  public boolean functionThrows(String mergedKey) {
    return mergedKey != null && throwingFunctions.contains(mergedKey);
  }

  /**
   * Does the function at {@code mergedKey} contain a CATCH_HANDLER?
   *
   * @param mergedKey the function's merged key
   * @return true when some worker saw a CATCH_HANDLER in that function
   */
  public boolean hasCatch(String mergedKey) {
    return mergedKey != null && catchingFunctions.contains(mergedKey);
  }

  /** The number of functions known to throw. Exposed for tests and for the audit log. */
  public int throwingCount() {
    return throwingFunctions.size();
  }

  /** The number of functions known to have a catch handler. */
  public int catchingCount() {
    return catchingFunctions.size();
  }
}
