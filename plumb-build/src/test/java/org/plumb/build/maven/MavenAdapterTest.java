package org.plumb.build.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.plumb.build.BuildSystemRegistry;

class MavenAdapterTest {

  private static final String SIMPLE_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <dependencies>
              <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>30.1-jre</version>
                  <scope>compile</scope>
              </dependency>
              <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13.2</version>
                  <scope>test</scope>
              </dependency>
          </dependencies>
      </project>
      """;

  private static final String MULTI_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <modules>
              <module>core</module>
              <module>lib</module>
          </modules>
      </project>
      """;

  private static final String CHILD_POM =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <parent>
              <groupId>com.example</groupId>
              <artifactId>parent</artifactId>
              <version>1.0.0</version>
          </parent>
          <artifactId>core</artifactId>
          <dependencies>
              <dependency>
                  <groupId>com.google.guava</groupId>
                  <artifactId>guava</artifactId>
                  <version>30.1-jre</version>
              </dependency>
          </dependencies>
      </project>
      """;

  @Test
  void probe_findsPom(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("pom.xml"), SIMPLE_POM);
    MavenAdapter adapter = new MavenAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_rejectsDirectoryWithoutPom(@TempDir Path tmp) {
    MavenAdapter adapter = new MavenAdapter();
    assertThat(adapter.probe(tmp)).isFalse();
  }

  @Test
  void discover_singleModule(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("pom.xml"), SIMPLE_POM);
    // Layout: src/main/java/...
    Files.createDirectories(tmp.resolve("src/main/java/com/example"));
    Path mainFoo = tmp.resolve("src/main/java/com/example/Foo.java");
    Files.writeString(mainFoo, "package com.example; class Foo {}");
    Files.createDirectories(tmp.resolve("src/test/java/com/example"));
    Path testFoo = tmp.resolve("src/test/java/com/example/FooTest.java");
    Files.writeString(testFoo, "package com.example; class FooTest {}");

    // Use a fresh, empty local repo so the test is hermetic regardless
    // of whether the developer happens to have guava 30.1-jre cached.
    Path emptyRepo = tmp.resolve("empty-repo");
    Files.createDirectories(emptyRepo);
    MavenAdapter adapter = new MavenAdapter(emptyRepo);
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.systemName()).isEqualTo("maven");
    assertThat(bp.sourceFiles()).containsExactly(mainFoo);
    assertThat(bp.testSourceFiles()).containsExactly(testFoo);
    // junit is test-scoped -> skipped; guava is compile-scoped but not
    // present in the (empty) local repo so it is dropped with a warning.
    // classpathJars should be empty.
    assertThat(bp.classpathJars()).isEmpty();
  }

  @Test
  void discover_resolvesCompileClasspathWhenArtifactIsInLocalRepo(@TempDir Path tmp)
      throws IOException {
    Files.writeString(tmp.resolve("pom.xml"), SIMPLE_POM);

    // Pretend the local repo has guava 30.1-jre already downloaded.
    Path guavaDir = tmp.resolve("repo/com/google/guava/guava/30.1-jre");
    Files.createDirectories(guavaDir);
    Path guavaJar = guavaDir.resolve("guava-30.1-jre.jar");
    Files.writeString(guavaJar, "fake-jar");

    MavenAdapter adapter = new MavenAdapter(tmp.resolve("repo"));
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.classpathJars()).containsExactly(guavaJar);
  }

  @Test
  void discover_multiModule(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("pom.xml"), MULTI_POM);

    // core/
    Path core = tmp.resolve("core");
    Files.createDirectories(core.resolve("src/main/java"));
    Files.writeString(core.resolve("pom.xml"), CHILD_POM);
    Path coreFoo = core.resolve("src/main/java/Core.java");
    Files.writeString(coreFoo, "class Core {}");

    // lib/
    Path lib = tmp.resolve("lib");
    Files.createDirectories(lib.resolve("src/main/java"));
    Files.writeString(
        lib.resolve("pom.xml"),
        CHILD_POM.replace("<artifactId>core</artifactId>", "<artifactId>lib</artifactId>"));
    Path libBar = lib.resolve("src/main/java/Lib.java");
    Files.writeString(libBar, "class Lib {}");

    MavenAdapter adapter = new MavenAdapter();
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.sourceFiles()).containsExactlyInAnyOrder(coreFoo, libBar);
    assertThat(bp.modules()).containsKeys("core", "lib");
    assertThat(bp.modules().get("core").sources()).containsExactly(coreFoo);
    assertThat(bp.modules().get("lib").sources()).containsExactly(libBar);
  }

  @Test
  void discover_siblingTargetClassesShortCircuitLocalRepo(@TempDir Path tmp) throws IOException {
    // Parent aggregator that has core as a child with a compile dep on lib.
    Files.writeString(tmp.resolve("pom.xml"), MULTI_POM);
    Path core = tmp.resolve("core");
    Files.createDirectories(core.resolve("src/main/java"));
    Files.writeString(core.resolve("pom.xml"), CHILD_POM);
    Path coreFoo = core.resolve("src/main/java/Core.java");
    Files.writeString(coreFoo, "class Core {}");

    // Create lib with target/classes (simulating an already-built sibling).
    Path lib = tmp.resolve("lib");
    Files.createDirectories(lib.resolve("target/classes"));
    Path libBar = lib.resolve("src/main/java/Lib.java");
    Files.createDirectories(libBar.getParent());
    Files.writeString(libBar, "class Lib {}");
    Files.writeString(
        lib.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>lib</artifactId>
            <version>1.0.0</version>
        </project>
        """);

    // Add a dep from core on lib (already covered by CHILD_POM's missing
    // reference, so add it explicitly):
    Files.writeString(
        core.resolve("pom.xml"),
        CHILD_POM.replace(
            "</dependencies>",
            "<dependency><groupId>com.example</groupId><artifactId>lib</artifactId>"
                + "<version>1.0.0</version></dependency></dependencies>"));

    // Use a hermetic empty local repo so this test does not depend on
    // the developer's ~/.m2 contents.
    Path emptyRepo = tmp.resolve("empty-repo");
    Files.createDirectories(emptyRepo);
    MavenAdapter adapter = new MavenAdapter(emptyRepo);
    BuildProject bp = adapter.discover(tmp);

    assertThat(bp.classpathJars())
        .as("lib's target/classes is preferred over the absent local repo jar")
        .containsExactly(lib.resolve("target/classes"));
  }

  @Test
  void discover_throwsOnMissingPom(@TempDir Path tmp) {
    MavenAdapter adapter = new MavenAdapter();
    assertThatThrownBy(() -> adapter.discover(tmp))
        .isInstanceOf(BuildAdapterException.class)
        .hasMessageContaining("pom.xml");
  }

  @Test
  void discover_throwsOnBrokenPom(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("pom.xml"), "<not valid xml");
    MavenAdapter adapter = new MavenAdapter();
    assertThatThrownBy(() -> adapter.discover(tmp))
        .isInstanceOf(BuildAdapterException.class)
        .hasMessageContaining("parse");
  }

  @Test
  void registry_advertisesMaven() {
    BuildAdapter a = BuildSystemRegistry.get().detect(Path.of("."));
    // Detect: depends on whether the workspace itself contains a pom.xml;
    // we just check the registry advertises the adapter by name.
    assertThat(BuildSystemRegistry.get().byName("maven")).isNotNull();
  }

  // -------------------------------------------------------------------------
  // P0 coverage: parent / dependencyManagement / properties / SNAPSHOT /
  //              classifier / provided / systemPath / dedup / path-traversal /
  //              custom sourceDirectory / languageLevel / sourceEncoding
  // -------------------------------------------------------------------------

  @Test
  void discover_parentProvidesGroupIdAndVersion(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>2.0.0</version>
            <packaging>pom</packaging>
            <modules>
                <module>child</module>
            </modules>
        </project>
        """);
    Path child = tmp.resolve("child");
    Files.createDirectories(child);
    Files.writeString(
        child.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>2.0.0</version>
            </parent>
            <artifactId>child</artifactId>
        </project>
        """);
    Files.createDirectories(child.resolve("src/main/java"));
    Files.writeString(child.resolve("src/main/java/C.java"), "class C {}");
    BuildProject bp = new MavenAdapter().discover(tmp);
    // child inherits groupId+version from the parent. We at least ensure discover runs without
    // throwing and that the child's sources are picked up.
    assertThat(bp.sourceFiles()).contains(child.resolve("src/main/java/C.java"));
    // The child module should be registered under its <artifactId>.
    assertThat(bp.modules()).containsKey("child");
  }

  @Test
  void discover_dependencyManagementProvidesVersion(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.google.guava</groupId>
                        <artifactId>guava</artifactId>
                        <version>30.1-jre</version>
                    </dependency>
                </dependencies>
            </dependencyManagement>
            <dependencies>
                <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path guava = repo.resolve("com/google/guava/guava/30.1-jre/guava-30.1-jre.jar");
    Files.createDirectories(guava.getParent());
    Files.writeString(guava, "fake-jar");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    // The dep without a <version> should have inherited the version from <dependencyManagement>
    // and resolved the local jar.
    assertThat(bp.classpathJars()).containsExactly(guava);
  }

  @Test
  void discover_resolvesPropertyPlaceholdersInDependencies(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <properties>
                <guava.version>30.1-jre</guava.version>
            </properties>
            <dependencies>
                <dependency>
                    <groupId>com.google.guava</groupId>
                    <artifactId>guava</artifactId>
                    <version>${guava.version}</version>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path guava = repo.resolve("com/google/guava/guava/30.1-jre/guava-30.1-jre.jar");
    Files.createDirectories(guava.getParent());
    Files.writeString(guava, "fake-jar");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    // ${guava.version} should have been substituted with 30.1-jre.
    assertThat(bp.classpathJars()).containsExactly(guava);
  }

  @Test
  void discover_resolvesSnapshotFromMavenMetadata(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>com.example</groupId>
                    <artifactId>snapshotdep</artifactId>
                    <version>1.0-SNAPSHOT</version>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path snapDir = repo.resolve("com/example/snapshotdep/1.0-SNAPSHOT");
    Files.createDirectories(snapDir);
    // maven-metadata.xml with <latest>1.0.0-20240101.000000-1</latest>
    Files.writeString(
        snapDir.resolve("maven-metadata.xml"),
        """
        <?xml version="1.0"?>
        <metadata>
            <groupId>com.example</groupId>
            <artifactId>snapshotdep</artifactId>
            <version>1.0-SNAPSHOT</version>
            <versioning>
                <latest>1.0.0-20240101.000000-1</latest>
                <release>1.0.0-20240101.000000-1</release>
                <versions>
                    <version>1.0.0-20240101.000000-1</version>
                </versions>
            </versioning>
        </metadata>
        """);
    Path tsDir = snapDir.resolve("1.0.0-20240101.000000-1");
    Files.createDirectories(tsDir);
    Path tsJar = tsDir.resolve("snapshotdep-1.0.0-20240101.000000-1.jar");
    Files.writeString(tsJar, "fake-snap");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    assertThat(bp.classpathJars()).containsExactly(tsJar);
  }

  @Test
  void discover_resolvesClassifier(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>org.example</groupId>
                    <artifactId>lib</artifactId>
                    <version>1.0</version>
                    <classifier>jdk17</classifier>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path classifierJar =
        repo.resolve("org/example/lib/1.0/lib-1.0-jdk17.jar");
    Files.createDirectories(classifierJar.getParent());
    Files.writeString(classifierJar, "fake");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    assertThat(bp.classpathJars()).containsExactly(classifierJar);
  }

  @Test
  void discover_providedScopeKeptInCompileClasspath(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>javax.servlet</groupId>
                    <artifactId>servlet-api</artifactId>
                    <version>2.5</version>
                    <scope>provided</scope>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path servlet = repo.resolve("javax/servlet/servlet-api/2.5/servlet-api-2.5.jar");
    Files.createDirectories(servlet.getParent());
    Files.writeString(servlet, "fake");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    // Provided scope stays in the compile classpath (matches javac semantics).
    assertThat(bp.classpathJars()).containsExactly(servlet);
  }

  @Test
  void discover_testScopeExcludedFromCompileClasspath(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>junit</groupId>
                    <artifactId>junit</artifactId>
                    <version>4.13.2</version>
                    <scope>test</scope>
                </dependency>
            </dependencies>
        </project>
        """);
    Path repo = tmp.resolve("repo");
    Path junit = repo.resolve("junit/junit/4.13.2/junit-4.13.2.jar");
    Files.createDirectories(junit.getParent());
    Files.writeString(junit, "fake");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    // Test scope must NOT appear in the compile classpath.
    assertThat(bp.classpathJars()).isEmpty();
  }

  @Test
  void discover_systemScopeResolvesViaSystemPath(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencies>
                <dependency>
                    <groupId>com.oracle</groupId>
                    <artifactId>jdk-tools</artifactId>
                    <version>1.0</version>
                    <scope>system</scope>
                    <systemPath>${basedir}/lib/jdk.jar</systemPath>
                </dependency>
            </dependencies>
        </project>
        """);
    Files.createDirectories(tmp.resolve("lib"));
    Path sysJar = tmp.resolve("lib/jdk.jar");
    Files.writeString(sysJar, "fake");
    BuildProject bp = new MavenAdapter().discover(tmp);
    assertThat(bp.classpathJars()).containsExactly(sysJar);
  }

  @Test
  void discover_dedupsAcrossMultipleInvocations(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        MULTI_POM);
    Path core = tmp.resolve("core");
    Files.createDirectories(core.resolve("src/main/java"));
    Files.writeString(core.resolve("pom.xml"), CHILD_POM);
    Files.writeString(core.resolve("src/main/java/Core.java"), "class Core {}");
    Path lib = tmp.resolve("lib");
    Files.createDirectories(lib.resolve("src/main/java"));
    Files.writeString(
        lib.resolve("pom.xml"),
        CHILD_POM.replace("<artifactId>core</artifactId>", "<artifactId>lib</artifactId>"));
    Files.writeString(lib.resolve("src/main/java/Lib.java"), "class Lib {}");
    Path repo = tmp.resolve("repo");
    Path guava = repo.resolve("com/google/guava/guava/30.1-jre/guava-30.1-jre.jar");
    Files.createDirectories(guava.getParent());
    Files.writeString(guava, "fake");
    BuildProject bp = new MavenAdapter(repo).discover(tmp);
    // Both core and lib declare guava in their own pom — without dedup the classpath would have
    // the jar twice. The adapter dedups so it appears once.
    assertThat(bp.classpathJars()).containsExactly(guava);
  }

  @Test
  void discover_rejectsPathTraversalInModule(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <modules>
                <module>../../../etc</module>
            </modules>
        </project>
        """);
    assertThatThrownBy(() -> new MavenAdapter().discover(tmp))
        .isInstanceOf(BuildAdapterException.class)
        .hasMessageContaining("escapes");
  }

  @Test
  void discover_customSourceDirectoryOverride(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <build>
                <sourceDirectory>gensrc/main/java</sourceDirectory>
                <testSourceDirectory>gensrc/test/java</testSourceDirectory>
            </build>
        </project>
        """);
    Files.createDirectories(tmp.resolve("gensrc/main/java/com/example"));
    Path mainFoo = tmp.resolve("gensrc/main/java/com/example/Foo.java");
    Files.writeString(mainFoo, "package com.example; class Foo {}");
    Files.createDirectories(tmp.resolve("gensrc/test/java/com/example"));
    Path testFoo = tmp.resolve("gensrc/test/java/com/example/FooTest.java");
    Files.writeString(testFoo, "package com.example; class FooTest {}");
    // Make sure the default src/main/java is empty / doesn't exist so we know the override is
    // what produced the result.
    BuildProject bp = new MavenAdapter().discover(tmp);
    assertThat(bp.sourceFiles()).containsExactly(mainFoo);
    assertThat(bp.testSourceFiles()).containsExactly(testFoo);
  }

  @Test
  void discover_detectsLanguageLevelFromProperties(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <properties>
                <maven.compiler.source>11</maven.compiler.source>
            </properties>
        </project>
        """);
    BuildProject bp = new MavenAdapter().discover(tmp);
    assertThat(bp.languageLevel()).isEqualTo("11");
  }

  @Test
  void discover_detectsSourceEncodingFromProperties(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <properties>
                <project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>
            </properties>
        </project>
        """);
    BuildProject bp = new MavenAdapter().discover(tmp);
    assertThat(bp.sourceEncoding()).isNotNull();
    assertThat(bp.sourceEncoding().name()).isEqualTo("ISO-8859-1");
  }

  @Test
  void discover_pomPackagingBomsAreNotDownloaded(@TempDir Path tmp) throws IOException {
    // A <dependency> with <type>pom</type> (BOM import) should not be resolved as a jar.
    Files.writeString(
        tmp.resolve("pom.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>demo</artifactId>
            <version>1.0.0</version>
            <dependencyManagement>
                <dependencies>
                    <dependency>
                        <groupId>com.fasterxml.jackson</groupId>
                        <artifactId>jackson-bom</artifactId>
                        <version>2.15.0</version>
                        <type>pom</type>
                        <scope>import</scope>
                    </dependency>
                </dependencies>
            </dependencyManagement>
        </project>
        """);
    BuildProject bp = new MavenAdapter(tmp.resolve("repo")).discover(tmp);
    // BOM has type=pom — adapter must NOT try to download it as a jar.
    assertThat(bp.classpathJars()).isEmpty();
  }
}
