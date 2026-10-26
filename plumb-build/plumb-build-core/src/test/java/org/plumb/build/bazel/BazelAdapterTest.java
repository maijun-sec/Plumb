package org.plumb.build.bazel;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.BuildProject;

class BazelAdapterTest {

  private static final String BUILD =
      """
      java_library(
          name = "core",
          srcs = glob(["**/*.java"]),
          deps = [":util"],
          visibility = ["//visibility:public"],
      )

      java_library(
          name = "util",
          srcs = ["Util.java", "Helper.java"],
      )
      """;

  @Test
  void probe_findsBuildFile(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("BUILD"), BUILD);
    BazelAdapter adapter = new BazelAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_findsBuildBazel(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("BUILD.bazel"), BUILD);
    BazelAdapter adapter = new BazelAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_rejectsWithoutBuildFile(@TempDir Path tmp) {
    assertThat(new BazelAdapter().probe(tmp)).isFalse();
  }

  @Test
  void discover_picksUpJavaLibrary(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("BUILD"), BUILD);
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("Core.java"), "class Core {}");
    Files.writeString(tmp.resolve("Util.java"), "class Util {}");
    Files.writeString(tmp.resolve("Helper.java"), "class Helper {}");

    BuildProject bp = new BazelAdapter().discover(tmp);

    // glob(["**/*.java"]) expands to all 3 files
    assertThat(bp.sourceFiles())
        .containsExactlyInAnyOrder(
            tmp.resolve("Core.java"), tmp.resolve("Util.java"), tmp.resolve("Helper.java"));
    // util's literal list also resolves; both targets end up in modules
    // (keyed by <package>:<target>).
    String pkgKey =
        bp.modules().keySet().stream().filter(k -> k.endsWith(":core")).findFirst().orElse(null);
    assertThat(pkgKey).as("expected a module key ending in :core").isNotNull();
    String utilKey =
        bp.modules().keySet().stream().filter(k -> k.endsWith(":util")).findFirst().orElse(null);
    assertThat(utilKey).as("expected a module key ending in :util").isNotNull();
  }

  // -------------------------------------------------------------------------
  // P1 coverage: java_import / exclude glob / additional rule kinds
  // -------------------------------------------------------------------------

  @Test
  void discover_javaImportContributesJars(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("BUILD"),
        """
        java_import(
            name = "guava",
            jars = ["lib/guava.jar"],
        )
        """);
    Files.createDirectories(tmp.resolve("lib"));
    Files.writeString(tmp.resolve("lib/guava.jar"), "fake-jar");
    BuildProject bp = new BazelAdapter().discover(tmp);
    // java_import adds its jars to the classpath so symbol resolution can find them.
    assertThat(bp.classpathJars()).contains(tmp.resolve("lib/guava.jar"));
  }

  @Test
  void discover_globExcludeFiltersOut(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("BUILD"),
        """
        java_library(
            name = "core",
            srcs = glob(["**/*.java"], exclude = ["Gen.java"]),
        )
        """);
    Files.writeString(tmp.resolve("A.java"), "class A {}");
    Files.writeString(tmp.resolve("B.java"), "class B {}");
    Files.writeString(tmp.resolve("Gen.java"), "class Gen {}");
    BuildProject bp = new BazelAdapter().discover(tmp);
    // Gen.java is excluded from the glob.
    assertThat(bp.sourceFiles())
        .containsExactlyInAnyOrder(tmp.resolve("A.java"), tmp.resolve("B.java"))
        .doesNotContain(tmp.resolve("Gen.java"));
  }

  @Test
  void discover_androidLibraryTargetIsRecognised(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("BUILD"),
        """
        android_library(
            name = "ui",
            srcs = ["UI.java"],
        )
        """);
    Files.writeString(tmp.resolve("UI.java"), "class UI {}");
    BuildProject bp = new BazelAdapter().discover(tmp);
    assertThat(bp.sourceFiles()).contains(tmp.resolve("UI.java"));
    // We at least one module key whose id ends with :ui.
    boolean hasUi = bp.modules().keySet().stream().anyMatch(k -> k.endsWith(":ui"));
    assertThat(hasUi).as("expected a module key ending in :ui").isTrue();
  }
}
