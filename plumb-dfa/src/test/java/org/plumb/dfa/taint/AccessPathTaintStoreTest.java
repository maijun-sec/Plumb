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
import org.plumb.tac.ir.TacAccessPath;

/**
 * The access-path store must be keyed by WHERE a value lives, not by the NAME of the variable that
 * happens to hold it.
 *
 * <p><b>Why this test exists.</b> The engine tracks field taint with a flat {@code
 * "receiver.fieldName"} string. The receiver is a <em>variable name</em>, so two different objects
 * reached through the same variable name share one slot. The access-path vocabulary exists
 * precisely to fix this: a path names a location, and two locations are the same location only when
 * they name the same root and the same chain of selectors.
 *
 * <p><b>The failure this test is designed to catch.</b> An access-path store that keys on the
 * variable name is not an access-path store; it is the same coarse tracker wearing a new type. The
 * two objects below are distinguished by their ALLOCATION SITE, and the store has to keep them
 * apart.
 *
 * <p><b>Why the alias barrier matters here.</b> A path can be cut at a point where the engine can
 * no longer prove that two references reach the same storage. A store that ignores the barrier
 * would claim a match it cannot justify.
 */
@DisplayName("Taint: the access-path store keys by location, not by variable name")
class AccessPathTaintStoreTest {

  /**
   * Two allocations of the same class, in the same function.
   *
   * <p>They are the same TYPE and they are different OBJECTS. The only thing that tells them apart
   * is where they were allocated.
   */
  private static final String ALLOC_A = "app#handle@ctx|fn#12";

  private static final String ALLOC_B = "app#handle@ctx|fn#27";

  @Test
  @DisplayName("two allocations of one class are two locations")
  void twoAllocationsOfOneClassAreTwoLocations() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath pathA = TacAccessPath.root(ALLOC_A).withField("data");
    TacAccessPath pathB = TacAccessPath.root(ALLOC_B).withField("data");

    store.record(pathA, "input:request", List.of());

    assertThat(store.readExact(pathB))
        .as(
            "a field of the SECOND allocation must not carry the taint that was written to the"
                + " FIRST allocation's field -- the two are different objects")
        .isNull();

    assertThat(store.readExact(pathA))
        .as("the field that was written is still tainted")
        .isNotNull();
  }

  @Test
  @DisplayName("a nested field is a different location from the field that holds it")
  void aNestedFieldIsADifferentLocationFromTheFieldThatHoldsIt() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath outer = TacAccessPath.root(ALLOC_A).withField("holder");
    TacAccessPath inner = TacAccessPath.root(ALLOC_A).withField("holder").withField("data");

    store.record(inner, "input:request", List.of());

    assertThat(store.readExact(outer))
        .as(
            "the OUTER field holds the object, not the tainted value -- reading it exactly must not"
                + " find the inner field's taint")
        .isNull();
  }

  @Test
  @DisplayName("a tainted container taints the element read through it")
  void aTaintedContainerTaintsTheElementReadThroughIt() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath container = TacAccessPath.root(ALLOC_A).withField("payload");
    TacAccessPath element = TacAccessPath.root(ALLOC_A).withField("payload").withField("value");

    store.record(container, "input:request", List.of());

    assertThat(store.read(element))
        .as(
            "the container is tainted, so a value read out of it is tainted too -- this is the"
                + " direction that lets one store serve fields, arrays and map entries alike")
        .isNotNull();
  }

  @Test
  @DisplayName("a tainted element makes the container read as tainted")
  void aTaintedElementMakesTheContainerReadAsTainted() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath container = TacAccessPath.root(ALLOC_A).withField("payload");
    TacAccessPath element = TacAccessPath.root(ALLOC_A).withField("payload").withField("value");

    store.record(element, "input:request", List.of());

    assertThat(store.read(container))
        .as(
            "an element is tainted, so reading the container sees it -- without this direction the"
                + " array taint has to be tracked separately from the field taint, which is exactly"
                + " the duplication this store removes")
        .isNotNull();
  }

  @Test
  @DisplayName("a clean write to the container clears the elements beneath it")
  void aCleanWriteToTheContainerClearsTheElementsBeneathIt() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath container = TacAccessPath.root(ALLOC_A).withField("payload");
    TacAccessPath element = TacAccessPath.root(ALLOC_A).withField("payload").withField("value");

    store.record(element, "input:request", List.of());
    store.clearSubtree(container);

    assertThat(store.read(element))
        .as(
            "after the container is replaced, the element it used to hold is gone -- the old object"
                + " is not the new one, and its taint must not survive")
        .isNull();
  }

  @Test
  @DisplayName("an alias barrier keeps two paths apart")
  void anAliasBarrierKeepsTwoPathsApart() {
    AccessPathTaintStore store = new AccessPathTaintStore();

    TacAccessPath beforeBarrier = TacAccessPath.root(ALLOC_A).withField("data");
    TacAccessPath afterBarrier = beforeBarrier.withAliasBarrier();

    store.record(beforeBarrier, "input:request", List.of());

    assertThat(store.readExact(afterBarrier))
        .as(
            "the barrier says the engine can no longer prove the two paths reach the same storage,"
                + " so an EXACT read past the barrier must not find the pre-barrier taint")
        .isNull();

    assertThat(store.readExact(beforeBarrier)).as("the pre-barrier path is unaffected").isNotNull();
  }
}
