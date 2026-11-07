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
package org.plumb.tac.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The source paths a store carries, and the rewrite that makes them mean something elsewhere.
 *
 * <p><b>Why this matters.</b> A store is meant to be copied to another machine. The IR carries the
 * producer's absolute paths, because the path is the store's identity. On the consumer's machine
 * those paths name nothing, so a finding would point at a file that is not there. The rewrite is
 * what makes the copy useful.
 */
@DisplayName("TAC: the store's source paths can be rewritten for another machine")
class SourcePathMapperTest {

  @Test
  @DisplayName("a path under the producer's root moves to the consumer's root")
  void aPathUnderTheProducersRootMovesToTheConsumersRoot() {
    SourcePathMapper mapper = SourcePathMapper.of(Paths.get("D:/proj"), Paths.get("/srv/proj"));

    assertThat(mapper.map(Paths.get("D:/proj/src/Main.java")))
        .as("the root is replaced and the rest of the path is kept")
        .isEqualTo(Paths.get("/srv/proj/src/Main.java").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a path outside the producer's root is left alone")
  void aPathOutsideTheProducersRootIsLeftAlone() {
    SourcePathMapper mapper = SourcePathMapper.of(Paths.get("D:/proj"), Paths.get("/srv/proj"));

    assertThat(mapper.map(Paths.get("C:/other/Lib.java")))
        .as(
            "a path outside the project belongs to something else, and guessing"
                + " where it lives would be worse than saying so")
        .isEqualTo(Paths.get("C:/other/Lib.java"));
  }

  @Test
  @DisplayName("a path that merely shares a prefix is not rewritten")
  void aPathThatMerelySharesAPrefixIsNotRewritten() {
    SourcePathMapper mapper = SourcePathMapper.of(Paths.get("D:/proj"), Paths.get("/srv/proj"));

    // "D:/project" starts with "D:/proj" as a STRING but is not under it.
    assertThat(mapper.map(Paths.get("D:/project/src/Main.java")))
        .as("a path boundary is a directory, not a string prefix")
        .isEqualTo(Paths.get("D:/project/src/Main.java"));
  }

  @Test
  @DisplayName("the identity mapper changes nothing")
  void theIdentityMapperChangesNothing() {
    SourcePathMapper mapper = SourcePathMapper.identity();
    Path path = Paths.get("D:/proj/src/Main.java");

    assertThat(mapper.map(path)).isEqualTo(path);
    assertThat(mapper.mappings()).isEmpty();
  }

  @Test
  @DisplayName("a substitution is parsed from producerRoot=consumerRoot")
  void aSubstitutionIsParsedFromProducerRootConsumerRoot() {
    SourcePathMapper.Mapping mapping = SourcePathMapper.parseMapping("D:/proj=/srv/proj");

    assertThat(mapping.producerRoot()).isEqualTo(Paths.get("D:/proj"));
    assertThat(mapping.consumerRoot()).isEqualTo(Paths.get("/srv/proj"));
  }

  @Test
  @DisplayName("a substitution's drive colon is not the separator")
  void aSubstitutionsDriveColonIsNotTheSeparator() {
    // The drive's colon is not the separator; the '=' is. A parser that split
    // on the first colon would take "D" as the producer's root.
    SourcePathMapper.Mapping mapping = SourcePathMapper.parseMapping("D:/proj=/srv/proj");

    assertThat(mapping.producerRoot()).isEqualTo(Paths.get("D:/proj"));
    assertThat(mapping.consumerRoot()).isEqualTo(Paths.get("/srv/proj"));
  }

  @Test
  @DisplayName("a substitution whose roots are not paths is refused")
  void aSubstitutionWhoseRootsAreNotPathsIsRefused() {
    // The shape was right but a root is not a path this platform can name.
    // The message says which of the two things is wrong, so the user knows
    // whether to add the separator or fix the path.
    assertThatThrownBy(() -> SourcePathMapper.parseMapping("D:/proj=\u0000bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("paths this platform can name");
  }

  @Test
  @DisplayName("a substitution without an equals sign is refused")
  void aSubstitutionWithoutAnEqualsSignIsRefused() {
    assertThatThrownBy(() -> SourcePathMapper.parseMapping("D:/proj"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("producerRoot=consumerRoot");
    assertThatThrownBy(() -> SourcePathMapper.parseMapping("=/srv/proj"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SourcePathMapper.parseMapping("D:/proj="))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("the first substitution that covers the path wins")
  void theFirstSubstitutionThatCoversThePathWins() {
    SourcePathMapper mapper =
        SourcePathMapper.of(
            List.of(
                new SourcePathMapper.Mapping(Paths.get("D:/proj"), Paths.get("/first")),
                new SourcePathMapper.Mapping(Paths.get("D:/proj/service"), Paths.get("/second"))));

    // The more specific root is listed second, so the broad one wins. That is
    // the order the caller gave, and the caller decides.
    assertThat(mapper.map(Paths.get("D:/proj/service/Main.java")))
        .isEqualTo(Paths.get("/first/service/Main.java").toAbsolutePath().normalize());
  }

  @Test
  @DisplayName("a string path is rewritten too")
  void aStringPathIsRewrittenToo() {
    SourcePathMapper mapper = SourcePathMapper.of(Paths.get("D:/proj"), Paths.get("/srv/proj"));

    assertThat(mapper.map("D:/proj/src/Main.java"))
        .isEqualTo(Paths.get("/srv/proj/src/Main.java").toAbsolutePath().normalize().toString());
    assertThat(mapper.map((String) null)).isNull();
    assertThat(mapper.map("")).isEmpty();
  }
}
