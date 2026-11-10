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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The identity of a lock.
 *
 * <p><b>Why this is its own test.</b> A field read lands in a FRESH TEMPORARY, so the two reads of
 * one field have two different names. Every lock check that compared the receiver's NAME therefore
 * paired nothing, and reported every correctly written {@code mu.Lock(); defer mu.Unlock()} as an
 * unlock without a lock. The property that makes the lock the same lock is that the two reads agree
 * -- so the test measures the agreement, not the spelling.
 */
@DisplayName("the identity of a lock is the lock, not the temporary that holds it")
class LockIdentityTest {

  @Test
  @DisplayName("a variable is named after the variable")
  void aVariableIsNamedAfterTheVariable() {
    LockIdentity.Resolver ids = new LockIdentity.Resolver(function(List.of()));

    assertThat(ids.of(var("mu"))).isEqualTo("mu");
  }

  @Test
  @DisplayName("two reads of one field have one identity")
  void twoReadsOfOneFieldHaveOneIdentity() {
    LockIdentity.Resolver ids =
        new LockIdentity.Resolver(
            function(List.of(fieldLoad("t1", "c", "mu"), fieldLoad("t3", "c", "mu"))));

    assertThat(ids.of(var("t1")))
        .as("the two reads of c.mu are the same lock")
        .isEqualTo(ids.of(var("t3")));
    assertThat(ids.of(var("t1"))).isEqualTo("mu@c");
  }

  @Test
  @DisplayName("two different fields have two identities")
  void twoDifferentFieldsHaveTwoIdentities() {
    LockIdentity.Resolver ids =
        new LockIdentity.Resolver(
            function(List.of(fieldLoad("t1", "c", "mu"), fieldLoad("t3", "c", "other"))));

    assertThat(ids.of(var("t1"))).isNotEqualTo(ids.of(var("t3")));
  }

  @Test
  @DisplayName("two different objects have two identities")
  void twoDifferentObjectsHaveTwoIdentities() {
    LockIdentity.Resolver ids =
        new LockIdentity.Resolver(
            function(List.of(fieldLoad("t1", "a", "mu"), fieldLoad("t3", "b", "mu"))));

    assertThat(ids.of(var("t1"))).isNotEqualTo(ids.of(var("t3")));
  }

  @Test
  @DisplayName("a field of a field is named by the whole path")
  void aFieldOfAFieldIsNamedByTheWholePath() {
    LockIdentity.Resolver ids =
        new LockIdentity.Resolver(
            function(List.of(fieldLoad("t1", "c", "inner"), fieldLoad("t3", "t1", "mu"))));

    assertThat(ids.of(var("t3"))).isEqualTo("mu@inner@c");
  }

  @Test
  @DisplayName("the SSA version is not part of the name")
  void theSsaVersionIsNotPartOfTheName() {
    LockIdentity.Resolver ids = new LockIdentity.Resolver(function(List.of()));

    assertThat(ids.of(var("mu:2"))).isEqualTo("mu");
  }

  @Test
  @DisplayName("a receiver that names nothing is not a lock")
  void aReceiverThatNamesNothingIsNotALock() {
    LockIdentity.Resolver ids = new LockIdentity.Resolver(function(List.of()));

    assertThat(ids.of(null)).isNull();
  }

  /** A function with the given instructions and no parameters. */
  private static TacFunction function(List<TacInstruction> instructions) {
    return new TacFunction("f", "f", null, List.of(), List.of(), instructions);
  }

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, null, false);
  }

  /** A field read: {@code base.field} into a temporary. */
  private static TacInstruction fieldLoad(String dest, String base, String field) {
    return TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
        .dest(var(dest))
        .lhs1(var(base))
        .fieldName(field)
        .build();
  }
}
