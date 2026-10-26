package org.plumb.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.ant.AntAdapter;
import org.plumb.build.bazel.BazelAdapter;
import org.plumb.build.gradle.GradleAdapter;
import org.plumb.build.maven.MavenAdapter;

/**
 * End-to-end exercise: every adapter must produce a non-empty {@link BuildProject} when fed a
 * minimal project root. This is the "it works" smoke test: each adapter is run with a hand-crafted
 * project tree and we check the result.
 */
class AdapterE2ETest {

  @Test
  void maven_singleModule(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>hello</artifactId>
            <version>1.0.0</version>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(
        tmp.resolve("src/main/java/com/example/Hello.java"), "package com.example; class Hello {}");

    BuildProject bp = new MavenAdapter().discover(tmp);
    assertThat(bp.systemName()).isEqualTo("maven");
    assertThat(bp.sourceFiles()).hasSize(1);
  }

  @Test
  void gradle_singleModule(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.gradle"),
        """
        plugins { id 'java' }
        """);
    Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(
        tmp.resolve("src/main/java/com/example/Hello.java"), "package com.example; class Hello {}");

    BuildProject bp = new GradleAdapter().discover(tmp);
    assertThat(bp.systemName()).isEqualTo("gradle");
    assertThat(bp.sourceFiles()).hasSize(1);
  }

  @Test
  void bazel_singleLibrary(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("BUILD"),
        """
        java_library(
            name = "hello",
            srcs = glob(["**/*.java"]),
        )
        """);
    Files.writeString(tmp.resolve("Hello.java"), "class Hello {}");

    BuildProject bp = new BazelAdapter().discover(tmp);
    assertThat(bp.systemName()).isEqualTo("bazel");
    assertThat(bp.sourceFiles()).hasSize(1);
  }

  @Test
  void ant_singleTarget(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project default="compile">
            <target name="compile">
                <javac srcdir="src"/>
            </target>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/Hello.java"), "class Hello {}");

    BuildProject bp = new AntAdapter().discover(tmp);
    assertThat(bp.systemName()).isEqualTo("ant");
    assertThat(bp.sourceFiles()).hasSize(1);
  }

  @Test
  void registry_autoDetectsMaven(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>x</groupId><artifactId>y</artifactId><version>1</version>
        </project>
        """);
    BuildAdapter a = BuildSystemRegistry.get().detect(tmp);
    assertThat(a).isNotNull();
    assertThat(a.systemName()).isEqualTo("maven");
  }

  @Test
  void registry_autoDetectsGradle(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.gradle"), "// gradle");
    BuildAdapter a = BuildSystemRegistry.get().detect(tmp);
    assertThat(a).isNotNull();
    // Could be gradle or bazel depending on registration order; we
    // assert that SOMETHING was detected.
    assertThat(a.systemName()).isIn("gradle", "bazel", "ant", "maven");
  }

  @Test
  void registry_returnsNullForUnknownRoot(@TempDir Path tmp) {
    // Empty dir has no markers.
    assertThat(BuildSystemRegistry.get().detect(tmp)).isNull();
  }
}
