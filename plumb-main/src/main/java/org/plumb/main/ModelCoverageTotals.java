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
package org.plumb.main;

import java.util.Map;
import java.util.TreeMap;
import org.plumb.reporter.ModelCoverage;

/**
 * The analysis coverage of a whole scan, summed over the analysis runs it was made of.
 *
 * <p>One scan does not necessarily analyse in one step: a scan that mixes source files with a
 * bytecode artefact analyses them in two, and a scan of several files may analyse each file on its
 * own. The report has to carry ONE coverage record, so the per-step records are summed.
 *
 * <p>The counters are added, not recomputed: the ratios are derived from the totals at the end, so
 * a scan of two halves does not report the average of two ratios (which would be wrong whenever the
 * halves are of different sizes).
 *
 * <p>The class is synchronized because the analysis runs may be handed to workers.
 */
final class ModelCoverageTotals {

  private int functions;
  private int functionsAtFullPrecision;
  private int declaredProblems;
  private int callSites;
  private int callSitesWithBody;
  private int callSitesWithSemantics;
  private int callSitesWithPassthrough;
  private int callSitesUnmodelled;
  private int instructions;
  private int havocInstructions;
  private int uninterpretedInstructions;
  private final Map<String, Integer> drops = new TreeMap<>();
  private final Map<String, Integer> programDrops = new TreeMap<>();

  /**
   * Add one analysis run's coverage.
   *
   * @param coverage the coverage of one run; null is ignored
   */
  synchronized void add(ModelCoverage coverage) {
    if (coverage == null) return;
    functions += coverage.functions();
    functionsAtFullPrecision += coverage.functionsAtFullPrecision();
    declaredProblems += coverage.declaredProblems();
    callSites += coverage.callSites();
    callSitesWithBody += coverage.callSitesWithBody();
    callSitesWithSemantics += coverage.callSitesWithSemantics();
    callSitesWithPassthrough += coverage.callSitesWithPassthrough();
    callSitesUnmodelled += coverage.callSitesUnmodelled();
    instructions += coverage.instructions();
    havocInstructions += coverage.havocInstructions();
    uninterpretedInstructions += coverage.uninterpretedInstructions();
    merge(drops, coverage.drops().byFunction());
    merge(programDrops, coverage.drops().byProgram());
  }

  /**
   * Add one group's counts into another.
   *
   * <p>The counts are added, not replaced: two files that lost the same construct count twice, and
   * a reader wants to know that the construct was lost twice.
   *
   * @param into the group being accumulated
   * @param from the group being added
   */
  private static void merge(Map<String, Integer> into, Map<String, Integer> from) {
    for (Map.Entry<String, Integer> entry : from.entrySet()) {
      into.merge(entry.getKey(), entry.getValue(), Integer::sum);
    }
  }

  /**
   * The accumulated coverage.
   *
   * @return the record
   */
  synchronized ModelCoverage snapshot() {
    return new ModelCoverage(
        functions,
        functionsAtFullPrecision,
        declaredProblems,
        callSites,
        callSitesWithBody,
        callSitesWithSemantics,
        callSitesWithPassthrough,
        callSitesUnmodelled,
        instructions,
        havocInstructions,
        uninterpretedInstructions,
        new ModelCoverage.DropCounts(drops, programDrops));
  }
}
