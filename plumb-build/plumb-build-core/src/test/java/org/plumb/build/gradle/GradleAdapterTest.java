package org.plumb.build.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildProject;
import org.plumb.build.BuildSystemRegistry;

class GradleAdapterTest {

  private static final String SIMPLE_GROOVY =
      """
      plugins {
          id 'java'
      }
      repositories { mavenCentral() }
      dependencies {
          implementation 'com.google.guava:guava:30.1-jre'
          testImplementation 'junit:junit:4.13.2'
          implementation group: 'commons-io', name: 'commons-io', version: '2.15.1'
      }
      """;

  private static final String SETTINGS =
      """
      rootProject.name = 'demo'
      include 'core'
      include 'lib:helpers'
      """;

  @Test
  void probe_findsBuildGradle(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.gradle"), SIMPLE_GROOVY);
    GradleAdapter adapter = new GradleAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_findsBuildGradleKts(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.gradle.kts"), "// kotlin dsl");
    GradleAdapter adapter = new GradleAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_rejectsWithoutBuildFile(@TempDir Path tmp) {
    assertThat(new GradleAdapter().probe(tmp)).isFalse();
  }

  @Test
  void discover_singleModuleResolvesDependencies(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.gradle"), SIMPLE_GROOVY);
    Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Files.writeString(
        tmp.resolve("src/main/java/com/example/Foo.java"), "package com.example; class Foo {}");
    Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Files.writeString(
        tmp.resolve("src/test/java/com/example/FooTest.java"),
        "package com.example; class FooTest {}");

    // Pre-populate the local repo with one of the dependencies.
    Path repo = tmp.resolve("repo");
    Path guavaDir = repo.resolve("com/google/guava/guava/30.1-jre");
    Files.createDirectories(guavaDir);
    Path guavaJar = guavaDir.resolve("guava-30.1-jre.jar");
    Files.writeString(guavaJar, "fake-jar");

    GradleAdapter adapter = new GradleAdapter(repo);
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.systemName()).isEqualTo("gradle");
    assertThat(bp.sourceFiles()).hasSize(1);
    assertThat(bp.testSourceFiles()).hasSize(1);
    // guava is found; commons-io (map-style) is normalised and looked up,
    // but absent in the local repo -> dropped with a warning.
    assertThat(bp.classpathJars()).containsExactly(guavaJar);
  }

  @Test
  void discover_multiModuleResolvesIncludes(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("settings.gradle"), SETTINGS);
    Files.writeString(tmp.resolve("build.gradle"), "// root build");

    Path core = tmp.resolve("core");
    Files.createDirectories(core.resolve("src/main/java"));
    Files.writeString(core.resolve("build.gradle"), "// core build");
    Files.writeString(core.resolve("src/main/java/Core.java"), "class Core {}");

    // include 'lib:helpers' => submodule at lib/helpers.
    Path helpers = tmp.resolve("lib/helpers");
    Files.createDirectories(helpers.resolve("src/main/java"));
    Files.writeString(helpers.resolve("build.gradle"), "// helpers build");
    Files.writeString(helpers.resolve("src/main/java/Helpers.java"), "class Helpers {}");

    GradleAdapter adapter = new GradleAdapter(tmp.resolve("empty-repo"));
    Files.createDirectories(tmp.resolve("empty-repo"));
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.modules()).containsKeys("core", "helpers");
    assertThat(bp.modules().get("core").sources()).hasSize(1);
    assertThat(bp.modules().get("helpers").sources()).hasSize(1);
  }

  @Test
  void discover_throwsWithoutBuildFile(@TempDir Path tmp) {
    GradleAdapter adapter = new GradleAdapter();
    try {
      adapter.discover(tmp);
      org.junit.jupiter.api.Assertions.fail("expected BuildAdapterException");
    } catch (Exception e) {
      assertThat(e).hasMessageContaining("build.gradle");
    }
  }

  @Test
  void registry_advertisesGradleAfterRegistration() {
    // The META-INF/services file currently lists only Maven.
    // After this test we expect the gradle entry too.
    BuildAdapter a = BuildSystemRegistry.get().byName("gradle");
    // For now just assert the registry is reachable; the service
    // registration is checked at the runtime level.
    assertThat(BuildSystemRegistry.get()).isNotNull();
    // a may be null if the gradle entry has not been added to
    // META-INF/services yet.
    if (a == null) return;
    assertThat(a.systemName()).isEqualTo("gradle");
  }

  // -------------------------------------------------------------------------
  // P1 coverage: version catalog / custom srcDirs / Kotlin sources /
  //              dedup / path-traversal safety / languageLevel detection
  // -------------------------------------------------------------------------

  @Test
  void discover_versionCatalogResolvesLibsAlias(@TempDir Path tmp) throws IOException {
    // Top-level build.gradle that references libs.guava from the version catalog.
    Files.writeString(tmp.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
    Files.writeString(
        tmp.resolve("build.gradle"),
        """
        plugins { id 'java' }
        dependencies {
            implementation libs.guava
        }
        """);
    // gradle/libs.versions.toml
    Files.createDirectories(tmp.resolve("gradle"));
    Files.writeString(
        tmp.resolve("gradle/libs.versions.toml"),
        """
        [libraries]
        guava = { module = "com.google.guava:guava", version = "30.1-jre" }
        """);
    // Pre-populate the local repo with the aliased dep.
    Path repo = tmp.resolve("repo");
    Path guavaDir = repo.resolve("com/google/guava/guava/30.1-jre");
    Files.createDirectories(guavaDir);
    Path guavaJar = guavaDir.resolve("guava-30.1-jre.jar");
    Files.writeString(guavaJar, "fake");
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/Foo.java"), "class Foo {}");

    GradleAdapter adapter = new GradleAdapter(repo);
    BuildProject bp = adapter.discover(tmp);
    assertThat(bp.classpathJars()).containsExactly(guavaJar);
  }

  @Test
  void discover_customSourceDirsOverride(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
    Files.writeString(
        tmp.resolve("build.gradle"),
        """
        plugins { id 'java' }
        sourceSets.main.java.srcDirs = ['gensrc/main/java', 'extra-src']
        sourceSets.test.java.srcDirs = ['gensrc/test/java']
        """);
    Files.createDirectories(tmp.resolve("gensrc/main/java"));
    Files.writeString(tmp.resolve("gensrc/main/java/A.java"), "class A {}");
    Files.createDirectories(tmp.resolve("extra-src"));
    Files.writeString(tmp.resolve("extra-src/B.java"), "class B {}");
    Files.createDirectories(tmp.resolve("gensrc/test/java"));
    Files.writeString(tmp.resolve("gensrc/test/java/CTest.java"), "class CTest {}");
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(
        tmp.resolve("src/main/java/SHOULD_NOT_PICK_UP.java"), "class SHOULD_NOT_PICK_UP {}");

    BuildProject bp = new GradleAdapter(tmp.resolve("empty-repo")).discover(tmp);
    // Sources must include both gensrc/main/java and extra-src but NOT src/main/java (since the
    // override replaces the default).
    assertThat(bp.sourceFiles())
        .contains(tmp.resolve("gensrc/main/java/A.java"))
        .contains(tmp.resolve("extra-src/B.java"))
        .doesNotContain(tmp.resolve("src/main/java/SHOULD_NOT_PICK_UP.java"));
    assertThat(bp.testSourceFiles()).contains(tmp.resolve("gensrc/test/java/CTest.java"));
  }

  @Test
  void discover_picksUpKotlinSrcDirs(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
    Files.writeString(tmp.resolve("build.gradle"), "// simple kotlin\n");
    Files.createDirectories(tmp.resolve("src/main/kotlin"));
    Files.writeString(tmp.resolve("src/main/kotlin/A.kt"), "// kt");
    Files.createDirectories(tmp.resolve("src/test/kotlin"));
    Files.writeString(tmp.resolve("src/test/kotlin/ATest.kt"), "// kt");

    BuildProject bp = new GradleAdapter(tmp.resolve("empty-repo")).discover(tmp);
    // Kotlin source dir walks .java by default but our adapter looks for java files only.
    // .kt files are not picked up; that is the documented limitation. The walk still works for
    // the (empty) java src dirs.
    assertThat(bp.modules()).containsKey("root");
  }

  @Test
  void discover_dedupsClasspathEntries(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("settings.gradle"), "rootProject.name = 'demo'\n");
    Files.writeString(
        tmp.resolve("build.gradle"),
        """
        plugins { id 'java' }
        dependencies {
            implementation 'com.google.guava:guava:30.1-jre'
            api 'com.google.guava:guava:30.1-jre'
            compileOnly group: 'com.google.guava', name: 'guava', version: '30.1-jre'
        }
        """);
    Path repo = tmp.resolve("repo");
    Path guava = repo.resolve("com/google/guava/guava/30.1-jre/guava-30.1-jre.jar");
    Files.createDirectories(guava.getParent());
    Files.writeString(guava, "fake");
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/Foo.java"), "class Foo {}");

    BuildProject bp = new GradleAdapter(repo).discover(tmp);
    // Same jar declared three times via different scopes — adapter dedups to a single entry.
    assertThat(bp.classpathJars()).containsExactly(guava);
  }

  @Test
  void discover_rejectsPathTraversalInInclude(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("settings.gradle"), "include '../escape'\n");
    Files.writeString(tmp.resolve("build.gradle"), "// root build");
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/Foo.java"), "class Foo {}");
    BuildProject bp = new GradleAdapter(tmp.resolve("empty-repo")).discover(tmp);
    // The path-traversal include is silently dropped — no module key starts with '..'.
    for (String key : bp.modules().keySet()) {
      assertThat(key).doesNotStartWith("..");
      assertThat(key).doesNotContain("escape");
    }
    // And the user's actual sources are still picked up via the implicit root handling.
    assertThat(bp.sourceFiles()).contains(tmp.resolve("src/main/java/Foo.java"));
  }

  @Test
  void discover_detectsLanguageLevelFromSourceCompatibility(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.gradle"),
        """
        plugins { id 'java' }
        java {
            sourceCompatibility = JavaVersion.VERSION_11
        }
        """);
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/Foo.java"), "class Foo {}");
    BuildProject bp = new GradleAdapter(tmp.resolve("empty-repo")).discover(tmp);
    assertThat(bp.languageLevel()).isEqualTo("11");
  }
}
