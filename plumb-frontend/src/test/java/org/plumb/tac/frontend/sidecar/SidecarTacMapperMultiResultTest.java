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
package org.plumb.tac.frontend.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The wire fields that say what a call PRODUCES, and what it writes back.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>The Go side car has been filling the instruction's result list for a long time -- a Go call
 * can return several values, {@code v, err := f()} -- and the wire protocol declares the
 * by-reference list as well. The Java side read NEITHER, and the IR had no field to read them into.
 *
 * <p><b>So the loss was silent.</b> Nothing said "this field was dropped": the side car did the
 * work, the TAC looked complete, and the information was simply gone. The engine then saw a call
 * with one result, plus a variable {@code err} that nothing ever writes -- and a variable nothing
 * writes is indistinguishable from a clean one.
 *
 * <h2>Why a HOLE keeps its place</h2>
 *
 * <p>A result the programmer discarded -- {@code v, _ := f()} -- is a hole in the list, and the
 * hole is kept. Dropping it would shift every later result one slot to the left, and the engine
 * would then read the error result as the value result. That is a wrong answer that looks like a
 * right one, which is the worst kind.
 */
@DisplayName("Mapper: the results of a call, and its by-reference arguments")
class SidecarTacMapperMultiResultTest {

  private static TacInstruction map(String wire) {
    return SidecarTacMapper.toInstruction(Json.parseObject(wire));
  }

  @Test
  @DisplayName("the results of a call reach the IR, in order")
  void theResultsOfACallReachTheIr() {
    TacInstruction ins =
        map(
            "{\"kind\":\"CALL\",\"calleeSig\":\"f/0\",\"dest\":{\"kind\":\"VAR\",\"name\":\"v\"},"
                + "\"resultDests\":[{\"kind\":\"VAR\",\"name\":\"v\"},{\"kind\":\"VAR\",\"name\":\"err\"}]}");

    assertThat(ins.knownResultDests()).hasSize(2);
    assertThat(ins.resultDestAt(0).toString()).contains("v");
    assertThat(ins.resultDestAt(1).toString()).contains("err");
    assertThat(ins.resultCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("a discarded result keeps its place")
  void aDiscardedResultKeepsItsPlace() {
    TacInstruction ins =
        map(
            "{\"kind\":\"CALL\",\"calleeSig\":\"f/0\",\"dest\":{\"kind\":\"VAR\",\"name\":\"v\"},"
                + "\"resultDests\":[{\"kind\":\"VAR\",\"name\":\"v\"},null]}");

    assertThat(ins.knownResultDests()).hasSize(2);
    assertThat(ins.knownResultDests().get(1)).isNull();
  }

  @Test
  @DisplayName("a call with no result list has one result")
  void aCallWithNoResultListHasOneResult() {
    TacInstruction ins =
        map("{\"kind\":\"CALL\",\"calleeSig\":\"f/0\",\"dest\":{\"kind\":\"VAR\",\"name\":\"v\"}}");

    assertThat(ins.knownResultDests()).isEmpty();
    assertThat(ins.resultCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("the by-reference arguments reach the IR, in step with the argument list")
  void theByReferenceArgumentsReachTheIr() {
    TacInstruction ins =
        map(
            "{\"kind\":\"CALL\",\"calleeSig\":\"f/2\","
                + "\"args\":[{\"kind\":\"VAR\",\"name\":\"plain\"},{\"kind\":\"VAR\",\"name\":\"target\"}],"
                + "\"refOutDests\":[null,{\"kind\":\"VAR\",\"name\":\"target\"}]}");

    assertThat(ins.knownRefOutDests()).hasSize(2);
    assertThat(ins.knownRefOutDests().get(0)).isNull();
    TacOperand second = ins.knownRefOutDests().get(1);
    assertThat(second).isNotNull();
    assertThat(second.toString()).contains("target");
  }
}
