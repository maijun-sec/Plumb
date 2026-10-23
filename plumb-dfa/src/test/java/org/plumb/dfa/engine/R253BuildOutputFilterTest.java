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
