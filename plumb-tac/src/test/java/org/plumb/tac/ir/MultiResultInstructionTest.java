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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The IR's shape for a call that produces SEVERAL values, and for a by-reference argument.
 *
 * <h2>Why this needed its own machinery</h2>
 *
 * <p>The TAC's CALL has ONE destination. A Go call can return several values -- {@code v, err :=
 * f()} -- and the ones after the first had nowhere to go. The front end had been sending them on
 * the wire for a long time, and the Java side read neither the wire field nor had a field to read
 * it into, so the information was simply gone.
 *
 * <p><b>And the loss is silent.</b> The engine saw a call with one result, plus a variable {@code
 * err} that nothing ever writes. A variable nothing writes is indistinguishable from a clean one,
 * so the taint in a tainted error result was gone before the analysis could ask about it.
 *
 * <h2>Why the POSITION is the meaning</h2>
 *
 * <p>A result the programmer discarded -- {@code v, _ := f()} -- leaves a HOLE. The hole keeps its
 * place: dropping it would shift every later result one slot to the left, and the engine would then
 * read the error result as the value result. So a null entry is kept, and the list says which
 * result each entry belongs to.
 *
 * <h2>Why the disk cache is part of this</h2>
 *
 * <p>The IR is serialised with a fixed {@code serialVersionUID}, so a cache written before these
 * fields existed still deserialises -- with the fields absent. Deserialisation does not run the
 * constructor, so the accessors have to tolerate a null field, or an old cache would throw where it
 * used to work.
 */
@DisplayName("IR: a call that produces several values, and a by-reference argument")
class MultiResultInstructionTest {

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, TacType.STRING, false);
  }

  @Test
  @DisplayName("the results are in order, and the first one is the instruction's own destination")
  void theResultsAreInOrder() {
    TacOperand.TacVar value = var("v");
    TacOperand.TacVar error = var("err");

    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("f/0")
            .dest(value)
            .resultDests(List.of(value, error))
            .build();

    assertThat(ins.knownResultDests()).containsExactly(value, error);
    assertThat(ins.resultDestAt(0)).isSameAs(value);
    assertThat(ins.resultDestAt(1)).isSameAs(error);
    assertThat(ins.resultCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a discarded result keeps its place")
  void aDiscardedResultKeepsItsPlace() {
    TacOperand.TacVar error = var("err");

    // `_, err := f()` -- the first result is discarded, so its entry is a hole.
    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("f/0")
            .dest(null)
            .resultDests(Arrays.asList(null, error))
            .build();

    assertThat(ins.knownResultDests()).hasSize(2);
    assertThat(ins.knownResultDests().get(0)).isNull();
    assertThat(ins.resultDestAt(1)).isSameAs(error);
  }

  @Test
  @DisplayName("a call with one result says so")
  void aCallWithOneResultSaysSo() {
    TacOperand.TacVar value = var("v");

    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL).calleeSig("f/0").dest(value).build();

    assertThat(ins.knownResultDests()).isEmpty();
    assertThat(ins.resultCount()).isEqualTo(1);
    assertThat(ins.resultDestAt(0)).isSameAs(value);
    assertThat(ins.resultDestAt(1)).isNull();
  }

  @Test
  @DisplayName("a bare call statement produces no result")
  void aBareCallStatementProducesNoResult() {
    TacInstruction ins = TacInstruction.builder(TacInstruction.Kind.CALL).calleeSig("f/0").build();

    assertThat(ins.resultCount()).isZero();
    assertThat(ins.resultDestAt(0)).isNull();
  }

  /**
   * A THROW can hand results back.
   *
   * <p>Go's {@code return 0, err} returns two values while exiting exceptionally, and the caller
   * binds both of them. The results therefore ride on the THROW, and the thrown value is also the
   * last result: one value plays both roles.
   *
   * <p>The accessors have to answer for a THROW the way they answer for a CALL, because the engine
   * reads the result slots off the instruction and does not ask what kind it is.
   */
  @Test
  @DisplayName("a throw that hands results back says so")
  void aThrowThatHandsResultsBackSaysSo() {
    TacOperand.TacVar value = var("v");
    TacOperand.TacVar error = var("err");

    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.THROW)
            .lhs1(error)
            .ctorType(TacType.ofClass("error"))
            .resultDests(List.of(value, error))
            .build();

    assertThat(ins.knownResultDests()).containsExactly(value, error);
    assertThat(ins.resultDestAt(0)).isSameAs(value);
    assertThat(ins.resultDestAt(1)).isSameAs(error);
    assertThat(ins.resultCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a throw that hands nothing back has no result")
  void aThrowThatHandsNothingBackHasNoResult() {
    // The ordinary exception: it raises something and hands back nothing.
    TacInstruction ins = TacInstruction.builder(TacInstruction.Kind.THROW).lhs1(var("e")).build();

    assertThat(ins.knownResultDests()).isEmpty();
    assertThat(ins.resultCount()).isZero();
    assertThat(ins.resultDestAt(0)).isNull();
  }

  @Test
  @DisplayName("a by-reference argument says which variable it is written back into")
  void aByReferenceArgumentSaysWhichVariableItIsWrittenBackInto() {
    TacOperand.TacVar target = var("target");

    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("f/1")
            .args(List.of(target))
            .refOutDests(List.of(target))
            .build();

    assertThat(ins.knownRefOutDests()).containsExactly(target);
    // The list is parallel to the argument list, so the two answer the same question.
    assertThat(ins.knownRefOutDests()).hasSameSizeAs(ins.args);
  }

  @Test
  @DisplayName("an argument that is not by reference leaves a hole")
  void anArgumentThatIsNotByReferenceLeavesAHole() {
    TacOperand.TacVar plain = var("plain");
    TacOperand.TacVar target = var("target");

    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("f/2")
            .args(List.of(plain, target))
            .refOutDests(Arrays.asList(null, target))
            .build();

    assertThat(ins.knownRefOutDests()).hasSize(2);
    assertThat(ins.knownRefOutDests().get(0)).isNull();
    assertThat(ins.knownRefOutDests().get(1)).isSameAs(target);
  }

  @Test
  @DisplayName("the fields survive the disk cache")
  void theFieldsSurviveTheDiskCache() throws Exception {
    TacOperand.TacVar value = var("v");
    TacOperand.TacVar error = var("err");
    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("f/0")
            .dest(value)
            .resultDests(List.of(value, error))
            .build();

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(ins);
    }
    TacInstruction read;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      read = (TacInstruction) in.readObject();
    }

    assertThat(read.knownResultDests()).containsExactly(value, error);
    // The deserialiser makes NEW objects, so the comparison is by VALUE: a variable's
    // identity is its name, not the instance that carries it.
    assertThat(read.resultDestAt(1)).isEqualTo(error);
  }

  @Test
  @DisplayName("an instruction from a cache written before these fields existed still reads")
  void anInstructionFromAnOlderCacheStillReads() throws Exception {
    // A cache written before the fields existed deserialises with them ABSENT -- the
    // deserialiser does not run the constructor. Reading the field directly would throw a
    // null pointer here, so the accessors have to answer "none" instead.
    TacOperand.TacVar value = var("v");
    TacInstruction ins =
        TacInstruction.builder(TacInstruction.Kind.CALL).calleeSig("f/0").dest(value).build();

    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(ins);
    }
    TacInstruction read;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      read = (TacInstruction) in.readObject();
    }

    assertThat(read.knownResultDests()).isEmpty();
    assertThat(read.knownRefOutDests()).isEmpty();
    assertThat(read.resultDestAt(0)).isEqualTo(value);
  }
}
