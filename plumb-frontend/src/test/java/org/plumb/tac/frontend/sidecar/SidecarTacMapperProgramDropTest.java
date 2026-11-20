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

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.TacDrop;

/**
 * The program-level drops of a sidecar response must be read.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>The sidecar records the losses that belong to the WHOLE PROGRAM -- a build fact it knows and no
 * function owns -- in the response's top-level array, beside the functions rather than inside one of
 * them. That array was never read on the Java side: the front ends read each function's own drop
 * array and stopped there. So a drop the sidecar did the work to record was thrown away on the way
 * in, and the TAC looked complete.
 *
 * <p>Nothing said so. A loss that is dropped on the way in looks exactly like a loss that never
 * happened, and the report cannot tell the two apart.
 *
 * <p>The wire names that array two ways -- {@code programDrops} in the protocol document, {@code
 * drops} in the task statement -- and the Go sidecar sends the SAME list under both names. So the
 * reader has to take one and not both: reading both would count every program drop twice, and a
 * count that is twice as big as the truth is worse than no count at all.
 */
@DisplayName("The sidecar's program-level drops are read from the response's top level")
class SidecarTacMapperProgramDropTest {

  /** One drop, as the wire carries it. */
  private static final String ONE_DROP =
      "{\"construct\":\"go.version\",\"detail\":\"the version is unknown\","
          + "\"impact\":\"SEMANTICS\",\"line\":-1,\"column\":-1,\"scope\":\"PROGRAM\"}";

  @Test
  @DisplayName("a drop in the programDrops array is read")
  void aDropInTheProgramDropsArrayIsRead() {
    List<TacDrop> drops =
        SidecarTacMapper.toProgramDrops(Json.parseObject("{\"programDrops\":[" + ONE_DROP + "]}"));

    assertThat(drops).as("the sidecar's program-level loss must survive the wire").hasSize(1);
    assertThat(drops.get(0).construct()).isEqualTo("go.version");
    assertThat(drops.get(0).detail()).isEqualTo("the version is unknown");
    assertThat(drops.get(0).impact()).isEqualTo(TacDrop.Impact.SEMANTICS);
  }

  @Test
  @DisplayName("the same drop under the other spelling of the array is read too")
  void theSameDropUnderTheOtherSpellingIsReadToo() {
    List<TacDrop> drops =
        SidecarTacMapper.toProgramDrops(Json.parseObject("{\"drops\":[" + ONE_DROP + "]}"));

    assertThat(drops).as("the protocol names the array two ways, and both are in use").hasSize(1);
    assertThat(drops.get(0).construct()).isEqualTo("go.version");
  }

  @Test
  @DisplayName("a drop that arrives under both names is counted once")
  void aDropThatArrivesUnderBothNamesIsCountedOnce() {
    // The Go sidecar sends the same list under both names. Reading both arrays would count
    // every program drop twice, and a count that is twice the truth flatters the report.
    List<TacDrop> drops =
        SidecarTacMapper.toProgramDrops(
            Json.parseObject("{\"programDrops\":[" + ONE_DROP + "],\"drops\":[" + ONE_DROP + "]}"));

    assertThat(drops).as("the same loss under two names is one loss").hasSize(1);
  }

  @Test
  @DisplayName("a drop in the program channel is a program-level drop")
  void aDropInTheProgramChannelIsAProgramLevelDrop() {
    // The channel decides the scope. A sidecar that left the scope field out must not make a
    // program-level loss look like a function's -- that would lower the wrong function's precision.
    String withoutScope =
        "{\"construct\":\"go.embed.value\",\"detail\":\"the content is not in the TAC\","
            + "\"impact\":\"SEMANTICS\",\"line\":-1,\"column\":-1}";
    String withFunctionScope =
        "{\"construct\":\"go.embed.value\",\"detail\":\"the content is not in the TAC\","
            + "\"impact\":\"SEMANTICS\",\"line\":-1,\"column\":-1,\"scope\":\"FUNCTION\"}";

    List<TacDrop> drops =
        SidecarTacMapper.toProgramDrops(Json.parseObject("{\"programDrops\":[" + withoutScope + "]}"));
    List<TacDrop> mislabelled =
        SidecarTacMapper.toProgramDrops(
            Json.parseObject("{\"programDrops\":[" + withFunctionScope + "]}"));

    assertThat(drops.get(0).scope()).isEqualTo(TacDrop.Scope.PROGRAM);
    assertThat(mislabelled.get(0).scope())
        .as("the program channel decides the scope, whatever the wire said")
        .isEqualTo(TacDrop.Scope.PROGRAM);
  }

  @Test
  @DisplayName("a response with no program-level drops gives none")
  void aResponseWithNoProgramLevelDropsGivesNone() {
    assertThat(SidecarTacMapper.toProgramDrops(Json.parseObject("{\"ok\":true}")))
        .as("a sidecar that recorded nothing must not invent a loss")
        .isEmpty();
    assertThat(SidecarTacMapper.toProgramDrops(Json.parseObject("{\"programDrops\":[]}")))
        .isEmpty();
    assertThat(SidecarTacMapper.toProgramDrops(null)).isEmpty();
  }
}
