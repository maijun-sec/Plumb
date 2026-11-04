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
package org.plumb.reporter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * How complete an analysis was, and what it did not look at.
 *
 * <h2>Why the report has to carry this</h2>
 *
 * <p>"No findings" and "the analysis was cut short" are different statements, and only the first
 * one is safe to act on. A user who reads a report with zero findings and no completeness record
 * concludes the code is clean. If the truth is that a budget ran out, or that the scope excluded a
 * module, or that a frontend could not lower a construct, that conclusion is wrong -- and it is
 * wrong SILENTLY, which is the worst way to be wrong.
 *
 * <p>So the completeness travels with the report. It is not a finding -- a finding is a claim about
 * the code -- it is a claim about the ANALYSIS, and it lives in a separate channel.
 *
 * <p>The direction of the rule is the same one the rest of the engine obeys: a completeness note
 * may only ever make the report MORE cautious. It can never be used to claim that code is safe.
 */
public final class AnalysisCompleteness {

  /** A completeness record for an analysis that finished everything it was asked to do. */
  public static AnalysisCompleteness allComplete() {
    return new AnalysisCompleteness(true, List.of());
  }

  private final boolean complete;
  private final List<String> notes;

  private AnalysisCompleteness(boolean complete, List<String> notes) {
    this.complete = complete;
    this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
  }

  /**
   * A record for an analysis that did not finish everything.
   *
   * @param notes what was skipped, in the user's terms. An empty note list with {@code complete ==
   *     false} is legal but useless, so at least one note is required.
   * @return the record
   */
  public static AnalysisCompleteness incomplete(String... notes) {
    List<String> list = new ArrayList<>();
    for (String n : notes) {
      if (n != null && !n.isBlank()) list.add(n);
    }
    return new AnalysisCompleteness(false, list);
  }

  /**
   * A record built from an existing list of notes.
   *
   * @param notes what was skipped
   * @return the record
   */
  public static AnalysisCompleteness incomplete(List<String> notes) {
    List<String> list = new ArrayList<>();
    if (notes != null) {
      for (String n : notes) {
        if (n != null && !n.isBlank()) list.add(n);
      }
    }
    return new AnalysisCompleteness(false, list);
  }

  /** Whether the analysis completed everything it was asked to do. */
  public boolean complete() {
    return complete;
  }

  /** What was skipped. Empty when the analysis was complete. */
  public List<String> notes() {
    return notes;
  }

  /** A one-line summary, for the log. */
  public String summary() {
    if (complete) return "the analysis completed every step it was asked to do";
    return "the analysis did NOT complete: " + String.join("; ", notes);
  }
}
