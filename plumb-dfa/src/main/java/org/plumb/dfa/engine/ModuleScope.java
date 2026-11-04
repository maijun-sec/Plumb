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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The module boundary of a single-module analysis, and the record of where the analysis left it.
 *
 * <h2>Why a single-module analysis is not a smaller scan</h2>
 *
 * <p>The user's requirement is precise: analyse one module of a big project, and do NOT break the
 * call graph while doing it. Those two things pull in opposite directions, and the naive way to
 * satisfy the first breaks the second:
 *
 * <ul>
 *   <li>Scan only the module's files: the callees in the dependency modules are gone, so the call
 *       graph has no edge across the module boundary, so a tainted value that enters the module
 *       from a caller in another module never reaches a sink inside it. The module looks clean
 *       because nothing could reach it.
 *   <li>Scan everything but report everything: the module's boundary is not a boundary at all.
 * </ul>
 *
 * <p>So the third option is the one that works: <b>read everything, analyse only the selected
 * module</b>. The other modules are read for symbol resolution and their functions are summarised,
 * so the call graph keeps crossing the boundary and the taint keeps flowing. What changes is where
 * a FINDING may be reported.
 *
 * <h2>What the boundary means for the report</h2>
 *
 * <p>A finding is attributed to its SINK's file (Plumb's convention). A sink outside the selected
 * module is out of scope, so its findings are not reported -- and the count of what was left out is
 * recorded, because "the module has no findings" and "the findings were outside the module" are
 * different statements.
 *
 * <p>The same applies to a source outside the module: a taint that enters the module from a caller
 * in another module is still followed (that is the whole point), but a finding whose SOURCE is
 * outside the module is reported with that origin named, so the reader can see that the module did
 * not originate the problem.
 */
public final class ModuleScope {

  /**
   * A call that left the selected module.
   *
   * @param from the caller, inside the module
   * @param to the callee, outside the module
   * @param resolved whether the callee was resolved at all
   */
  public record BoundaryCrossing(String from, String to, boolean resolved) {
    @Override
    public String toString() {
      return from + " -> " + to + (resolved ? "" : " (unresolved)");
    }
  }

  /** The selected module, or {@code null} when the whole project is in scope. */
  private final String module;

  /** The crossings, keyed by the caller, so a caller is reported once. */
  private final Map<String, java.util.Set<String>> crossings = new ConcurrentHashMap<>();

  /** How many findings were dropped because their sink was outside the module. */
  private final java.util.concurrent.atomic.AtomicInteger outOfScopeFindings =
      new java.util.concurrent.atomic.AtomicInteger();

  /** How many findings were kept because their sink was inside the module. */
  private final java.util.concurrent.atomic.AtomicInteger inScopeFindings =
      new java.util.concurrent.atomic.AtomicInteger();

  /** The modules that were seen, so the report can name them. */
  private final java.util.Set<String> seenModules = ConcurrentHashMap.newKeySet();

  private ModuleScope(String module) {
    this.module = module;
  }

  /**
   * A scope that covers everything.
   *
   * @return the unbounded scope
   */
  public static ModuleScope all() {
    return new ModuleScope(null);
  }

  /**
   * A scope limited to one module.
   *
   * @param module the module id; {@code null} or blank means "everything"
   * @return the scope
   */
  public static ModuleScope of(String module) {
    if (module == null || module.isBlank()) return all();
    return new ModuleScope(module);
  }

  /** The selected module, or {@code null} when the whole project is in scope. */
  public String module() {
    return module;
  }

  /** Whether this scope limits the analysis to one module. */
  public boolean isLimited() {
    return module != null;
  }

  /**
   * Whether a function is inside the scope.
   *
   * <p>A function with NO module (a single-file scan, the bytecode path) counts as inside: there is
   * no boundary to be outside of.
   */
  public boolean contains(String functionModule) {
    if (module == null) return true;
    if (functionModule == null || functionModule.isEmpty()) return true;
    return module.equals(functionModule);
  }

  /** Record the module a function came from, so the report can name the modules it saw. */
  public void observeModule(String functionModule) {
    if (functionModule != null && !functionModule.isEmpty()) {
      seenModules.add(functionModule);
    }
  }

  /**
   * Record a call that left the selected module.
   *
   * @param from the caller
   * @param to the callee
   * @param resolved whether the callee resolved to a function at all
   */
  public void recordCrossing(String from, String to, boolean resolved) {
    if (module == null) return;
    crossings
        .computeIfAbsent(from == null ? "<unknown>" : from, k -> ConcurrentHashMap.newKeySet())
        .add(to == null ? "<unknown>" : to);
  }

  /** The crossings, as a stable list. */
  public List<BoundaryCrossing> crossings() {
    List<BoundaryCrossing> out = new ArrayList<>();
    crossings.forEach(
        (from, tos) -> {
          for (String to : tos) {
            out.add(new BoundaryCrossing(from, to, true));
          }
        });
    out.sort(java.util.Comparator.comparing(BoundaryCrossing::toString));
    return out;
  }

  /** How many callers left the module. */
  public int crossingCallers() {
    return crossings.size();
  }

  /** Record a finding that was kept because its sink is inside the module. */
  public void recordInScopeFinding() {
    inScopeFindings.incrementAndGet();
  }

  /** Record a finding that was dropped because its sink is outside the module. */
  public void recordOutOfScopeFinding() {
    outOfScopeFindings.incrementAndGet();
  }

  /** The number of findings that were kept. */
  public int inScopeFindings() {
    return inScopeFindings.get();
  }

  /** The number of findings that were dropped because their sink was outside the module. */
  public int outOfScopeFindings() {
    return outOfScopeFindings.get();
  }

  /** The modules the scan saw. */
  public java.util.Set<String> seenModules() {
    return seenModules;
  }

  /**
   * A summary of the boundary, for the run log and the report.
   *
   * <p>The wording matters: it says what was NOT analysed, so a reader does not read "no findings"
   * as "the module is clean".
   */
  public String summary() {
    if (module == null) return "whole project in scope";
    Map<String, Integer> byCaller = new LinkedHashMap<>();
    crossings.forEach((from, tos) -> byCaller.put(from, tos.size()));
    return "module "
        + module
        + ": "
        + inScopeFindings.get()
        + " finding(s) in scope, "
        + outOfScopeFindings.get()
        + " dropped (sink outside the module), "
        + crossings.size()
        + " caller(s) called out of the module";
  }
}
