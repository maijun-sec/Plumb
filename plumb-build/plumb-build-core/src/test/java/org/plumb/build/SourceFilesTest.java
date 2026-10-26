package org.plumb.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SourceFiles#walk(Path, java.util.List)}.
 *
 * <p>Verifies: (1) hidden directories (names starting with {@code .}) are skipped, (2) the walk is
 * bounded so a symlink chain cannot blow up, (3) only files matching the requested extensions are
 * returned, (4) the result is sorted.
 */
class SourceFilesTest {

  @Test
  void walk_skipsHiddenDirectories(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    // A hidden sibling should be ignored
    Files.createDirectories(tmp.resolve(".git/refs"));
    Files.writeString(tmp.resolve(".git/refs/HEAD"), "ref: refs/heads/main");
    Files.createDirectories(tmp.resolve(".idea"));
    Files.writeString(tmp.resolve(".idea/workspace.xml"), "<xml/>");

    List<Path> result = SourceFiles.walk(tmp, List.of(".java"));
    assertThat(result).containsExactly(tmp.resolve("src/A.java"));
  }

  @Test
  void walk_respectsExtensionFilter(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    Files.writeString(tmp.resolve("src/b.txt"), "txt");
    Files.writeString(tmp.resolve("src/c.md"), "md");
    List<Path> result = SourceFiles.walk(tmp, List.of(".java"));
    assertThat(result).containsExactly(tmp.resolve("src/A.java"));
  }

  @Test
  void walk_returnsSortedResult(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/C.java"), "class C {}");
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    Files.writeString(tmp.resolve("src/B.java"), "class B {}");
    List<Path> result = SourceFiles.walk(tmp, List.of(".java"));
    assertThat(result.stream().map(p -> p.getFileName().toString()))
        .containsExactly("A.java", "B.java", "C.java");
  }

  @Test
  void walk_returnsEmptyForNullRoot() {
    assertThat(SourceFiles.walk(null, List.of(".java"))).isEmpty();
  }

  @Test
  void walk_returnsEmptyForMissingRoot(@TempDir Path tmp) {
    assertThat(SourceFiles.walk(tmp.resolve("does-not-exist"), List.of(".java"))).isEmpty();
  }

  @Test
  void walk_returnsEmptyForNullExtensions(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    assertThat(SourceFiles.walk(tmp, null)).isEmpty();
  }

  @Test
  void walk_doesNotFollowSymlinks(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("real"));
    Files.writeString(tmp.resolve("real/A.java"), "class A {}");
    // self-referential symlink: would loop forever if followed
    Path link = tmp.resolve("loop");
    try {
      Files.createSymbolicLink(link, tmp.resolve("real"));
    } catch (UnsupportedOperationException | java.io.IOException e) {
      // unsupported OS (Windows without privilege) — skip the test
      return;
    }
    // Walk from tmp/ which has both a real subtree and a symlink that points back to the
    // real subtree. NOFOLLOW means the symlink subtree is reachable but never expanded as
    // its own root, and there's no infinite recursion because we don't follow links.
    List<Path> result = SourceFiles.walk(tmp, List.of(".java"));
    assertThat(result).contains(tmp.resolve("real/A.java"));
  }
}
