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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacOperand;

/**
 * The coarse tracker keys a field by the NAME of the variable that holds it.
 *
 * <p><b>What this test records.</b> The behaviour that makes field sensitivity impossible today:
 * two different objects reached through the same variable name share one slot. That is not a bug in
 * the tracker -- it is what a name-keyed tracker DOES. It is the reason the access-path store
 * exists.
 *
 * <p>The test states the coarse behaviour explicitly, so that when the tracker is replaced by an
 * access-path store, this file becomes the place that says what changed and why. A tracker that
 * silently changed behaviour would leave no record of the trade.
 */
@DisplayName("Taint: the coarse field tracker keys by variable name")
class FieldTaintTrackerNameKeyingTest {

  @Test
  @DisplayName("two receivers with the same name are one slot")
  void twoReceiversWithTheSameNameAreOneSlot() {
    FieldTaintTracker t = new FieldTaintTracker();

    t.recordWrite("this", "data", "input:first", List.of());

    // A DIFFERENT object, reached through a variable that happens to be called "this".
    FieldTaintTracker.TrackedTaint read = t.readField("this", "data");

    assertThat(read)
        .as(
            "the coarse tracker cannot tell two objects apart when they are reached through the"
                + " same variable name -- this is the limitation the access-path store removes")
        .isNotNull();
    assertThat(read.label()).isEqualTo("input:first");
  }

  @Test
  @DisplayName("a different receiver name is a different slot")
  void aDifferentReceiverNameIsADifferentSlot() {
    FieldTaintTracker t = new FieldTaintTracker();

    t.recordWrite("other", "data", "input:first", List.of());

    assertThat(t.readField("this", "data"))
        .as("the receiver name IS the key, so a different name is a different slot")
        .isNull();
  }

  @Test
  @DisplayName("a write overwrites the previous write to the same slot")
  void aWriteOverwritesThePreviousWriteToTheSameSlot() {
    FieldTaintTracker t = new FieldTaintTracker();

    t.recordWrite("this", "data", "input:first", List.of());
    t.recordWrite("this", "data", "input:second", List.of());

    FieldTaintTracker.TrackedTaint read = t.readField("this", "data");
    assertThat(read).isNotNull();
    assertThat(read.label())
        .as("last-write-wins, which is sound for a path-insensitive intra-function analysis")
        .isEqualTo("input:second");
  }

  @Test
  @DisplayName("the getter-shaped lookup finds any tainted field of the receiver")
  void theGetterShapedLookupFindsAnyTaintedFieldOfTheReceiver() {
    FieldTaintTracker t = new FieldTaintTracker();
    TacOperand.TacVar recv = new TacOperand.TacVar("h", null, true);

    t.recordWrite("h", "data", "input:request", List.of());

    assertThat(t.readAnyTaintedFieldFor("h"))
        .as(
            "the dest-taint pass uses this to detect the getter pattern: the caller writes h.data ="
                + " u and then calls h.getData()")
        .isNotNull();
  }
}
