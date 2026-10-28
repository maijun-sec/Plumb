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
package org.plumb.build;

import java.util.Objects;

/**
 * One difference between what a build <em>declared</em> and what the compiler <em>actually
 * did</em>.
 *
 * <p><b>Why the difference is reported rather than resolved.</b> The two sides are used together: a
 * build is analysed by reading its pom, and by capturing the compile it really performed. When they
 * disagree, one of them is wrong -- and the analysis is wrong in a way nobody can see, because the
 * two answers are used in different places.
 *
 * <p>Silently picking one side hides that. So the difference is named, both values are kept, and
 * the report says which side said what.
 *
 * @param parameter the parameter that differs, e.g. {@code languageLevel}
 * @param declared what the build system declared, or {@code null} when it declared nothing
 * @param observed what the compiler actually used, or {@code null} when the capture does not report
 *     it
 * @param detail why the two are considered different
 */
public record CompileDiscrepancy(
    String parameter, String declared, String observed, String detail) {

  public CompileDiscrepancy {
    Objects.requireNonNull(parameter, "parameter");
    Objects.requireNonNull(detail, "detail");
  }

  /**
   * Whether this is a real difference or only a difference in what is known.
   *
   * <p>A side that says nothing is not a contradiction: an undeclared parameter is unknown, not a
   * default, so there is nothing to disagree about. A difference only exists when both sides have
   * an answer and the answers differ.
   *
   * @return true when both sides have an answer and the answers differ
   */
  public boolean isConflict() {
    return declared != null && observed != null && !declared.equals(observed);
  }

  /**
   * The difference as one line of text.
   *
   * <p>The order of the two values is fixed -- declared first, observed second -- so a report of
   * the same difference always reads the same way.
   *
   * @return the one-line description
   */
  public String describe() {
    return parameter
        + ": declared="
        + (declared == null ? "<unknown>" : declared)
        + ", observed="
        + (observed == null ? "<unknown>" : observed)
        + " ("
        + detail
        + ")";
  }
}
