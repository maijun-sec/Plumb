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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * Flask's request getters are untrusted input.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>The catalogue's python3 web sources named only the CONTAINER -- {@code request.args}, {@code
 * request.cookies} and their siblings -- so the engine tainted the <b>dictionary</b>. A subscript
 * read ({@code request.args["x"]}) carried that taint to a sink; the GETTER ({@code
 * request.args.get("x")}) did not, because a call's result does not inherit its receiver's taint.
 *
 * <p>The getter is the shape a Flask program actually writes, so every Flask finding was a false
 * NEGATIVE. Measured against the OWASP Benchmark for Python (1230 files), the taint families scored
 * a recall of 1.3%.
 *
 * <p>The names below are the engine's own callee keys, read off the TAC the Python side car
 * produces. A pattern that does not match them verbatim never fires, which is why they are written
 * out rather than derived.
 *
 * <h2>Why the negative control matters</h2>
 *
 * <p>A pattern loose enough to catch the getter would catch EVERY {@code dict.get} in every Python
 * file. That is a false positive on a scale no corpus forgives, so the control below pins the
 * tightness: an unrelated {@code .get} is not a source, and a Flask getter is not a source in a
 * language that has no Flask.
 */
@DisplayName("Flask's request getters are taint sources for Python")
class PythonWebSourceFamilyTest {

  private static final SinkFamilyRegistry CATALOGUE =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  /**
   * The callee keys the Python side car produces for Flask's request getters, measured from the TAC
   * it builds.
   */
  private static final String[] FLASK_GETTERS = {
    "flask.request.args.get/1",
    "flask.request.args.getlist/1",
    "flask.request.form.get/1",
    "flask.request.form.getlist/1",
    "flask.request.values.get/1",
    "flask.request.cookies.get/1",
    "flask.request.headers.get/1",
    "flask.request.headers.getlist/1",
    "flask.request.get_json/0",
  };

  /** Whether the engine recognises the callee as a source. */
  private static boolean engineSays(String callee, SourceLanguage language) {
    return InputSourcePatterns.defaults().matches(callee, CATALOGUE, language);
  }

  @Test
  @DisplayName("the request getters are sources")
  void theRequestGettersAreSources() {
    for (String callee : FLASK_GETTERS) {
      assertThat(engineSays(callee, SourceLanguage.PYTHON3))
          .as("taint.sources.web declares " + callee + " for python3")
          .isTrue();
    }
  }

  @Test
  @DisplayName("a getter that is not a request's is not a source")
  void aGetterThatIsNotARequestsIsNotASource() {
    // The control. The pattern is anchored on the module the request was
    // imported from, so an ordinary dictionary lookup stays clean.
    assertThat(engineSays("mydict.get/1", SourceLanguage.PYTHON3))
        .as("an ordinary dict.get carries no request")
        .isFalse();
    assertThat(engineSays("helpers.separate_request.request_wrapper/1", SourceLanguage.PYTHON3))
        .as("a program's own wrapper is not a request")
        .isFalse();
  }

  @Test
  @DisplayName("a Flask getter is not a source in another language")
  void aFlaskGetterIsNotASourceInAnotherLanguage() {
    // The python3 bucket belongs to python3. A Java file that happens to call
    // something.get is not reading a Flask request.
    assertThat(engineSays("flask.request.args.get/1", SourceLanguage.JAVA))
        .as("the python3 bucket does not leak into another language")
        .isFalse();
  }
}
