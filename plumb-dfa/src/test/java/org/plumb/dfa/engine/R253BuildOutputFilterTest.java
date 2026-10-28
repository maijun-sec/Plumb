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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Tests for the generic {@link BuildOutputFilter}. The filter is a pure path-component test: it
 * matches the leaf name against the default exclusion set (which can be extended per project). The
 * test exercises both default matches and the extension API.
 */
class R253BuildOutputFilterTest {

  @Test
  void defaultFilter_matchesCommonBuildDirs() {
    BuildOutputFilter f = new BuildOutputFilter();
    assertThat(f.isBuildOutput(Paths.get("/proj/target/classes/A.class"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/build/classes/A.class"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/out/A.class"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/bin/A.class"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/antbuild/copied/A.java"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/.gradle/cache"))).isTrue();
  }

  @Test
  void defaultFilter_keepsRealSource() {
    BuildOutputFilter f = new BuildOutputFilter();
    assertThat(f.isBuildOutput(Paths.get("/proj/src/main/java/A.java"))).isFalse();
    assertThat(f.isBuildOutput(Paths.get("/proj/testcases/CWE89_B.java"))).isFalse();
  }

  @Test
  void filter_isCaseInsensitive() {
    BuildOutputFilter f = new BuildOutputFilter();
    assertThat(f.isBuildOutput(Paths.get("/proj/TARGET/classes/A.class"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/Build/classes/A.class"))).isTrue();
  }

  @Test
  void extendedFilter_addsCustom() {
    BuildOutputFilter f = BuildOutputFilter.of("scratch", "tmp");
    assertThat(f.isBuildOutput(Paths.get("/proj/scratch/A.java"))).isTrue();
    assertThat(f.isBuildOutput(Paths.get("/proj/tmp/A.java"))).isTrue();
    // default still works.
    assertThat(f.isBuildOutput(Paths.get("/proj/target/classes/A.class"))).isTrue();
  }

  @Test
  void filterStream_stripsExcluded() {
    BuildOutputFilter f = new BuildOutputFilter();
    Stream<Path> in =
        Stream.of(
            Paths.get("/proj/src/A.java"),
            Paths.get("/proj/target/A.class"),
            Paths.get("/proj/src/B.java"),
            Paths.get("/proj/build/B.class"));
    List<Path> kept = f.filter(in).toList();
    assertThat(kept)
        .extracting(p -> p.getFileName().toString())
        .containsExactly("A.java", "B.java");
  }

  @Test
  void nullPath_returnsFalse() {
    BuildOutputFilter f = new BuildOutputFilter();
    assertThat(f.isBuildOutput(null)).isFalse();
  }
}
