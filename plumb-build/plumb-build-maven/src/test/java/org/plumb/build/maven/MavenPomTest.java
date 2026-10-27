package org.plumb.build.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link MavenPom}.
 *
 * <p><b>Why the language level and the encoding matter.</b> They are the two facts a static
 * analyser cannot guess: a source file compiled at a different language level is a different
 * program, and a source file read with the wrong encoding is a different program. The upstream
 * {@code maven-project-inspect} hard-coded both -- {@code setSource("1.8")} and {@code
 * setEncoding("UTF-8")} -- with TODOs next to them, so every project was reported as if it
 * compiled at 1.8 in UTF-8.
 */
class MavenPomTest {

  @Test
  void readsTheLanguageLevelFromAProperty(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <properties>
            <maven.compiler.source>11</maven.compiler.source>
          </properties>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("11");
  }

  @Test
  void readsTheEncodingFromAProperty(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <properties>
            <project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding>
          </properties>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
  }

  /**
   * The language level declared in the compiler plugin's configuration.
   *
   * <p>This is the other way to say it, and it is at least as common as the property: a project
   * that pins its Java version usually does it in {@code maven-compiler-plugin}'s {@code
   * <configuration>}. Reading only the property reported {@code null} for those projects, which
   * is the same as saying "1.8" -- the upstream's hard-coded answer.
   */
  @Test
  void readsTheLanguageLevelFromTheCompilerPluginConfiguration(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <release>17</release>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("17");
  }

  /**
   * {@code <source>} in the plugin configuration.
   *
   * <p>{@code <release>} is the more specific answer, but a project may declare only {@code
   * <source>}, and that is still a declared language level.
   */
  @Test
  void readsTheSourceLevelFromTheCompilerPluginConfiguration(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <source>11</source>
                  <target>11</target>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("11");
  }

  @Test
  void readsTheEncodingFromTheCompilerPluginConfiguration(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <encoding>ISO-8859-1</encoding>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.encoding()).isEqualTo(StandardCharsets.ISO_8859_1);
  }

  /**
   * The plugin's own {@code <release>} beats the property.
   *
   * <p>Both are ways to say the same thing, and the plugin's configuration is the more specific
   * one -- it is the value the compiler plugin actually used. So it wins.
   */
  @Test
  void thePluginConfigurationBeatsTheProperty(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <properties>
            <maven.compiler.source>8</maven.compiler.source>
          </properties>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <release>17</release>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("17");
  }

  /**
   * A {@code ${...}} placeholder in the plugin configuration is resolved.
   *
   * <p>The placeholder is the normal way to write it -- a project defines {@code
   * maven.compiler.release} and then uses it in the plugin -- so reading the configuration
   * without resolving it would produce the literal text {@code ${maven.compiler.release}}.
   */
  @Test
  void resolvesPlaceholdersInThePluginConfiguration(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <properties>
            <java.version>21</java.version>
          </properties>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <release>${java.version}</release>
                  <encoding>${project.build.sourceEncoding}</encoding>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("21");
  }

  /**
   * A project that declares nothing reports nothing.
   *
   * <p>Not "1.8" and not "UTF-8". The upstream's hard-coded defaults turned "the pom does not say"
   * into a confident wrong answer, and a confident wrong answer is worse than an absent one: the
   * analyser cannot tell that the value is a guess.
   */
  @Test
  void anUndeclaredLevelIsUnknownRatherThanGuessed(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isNull();
    assertThat(loaded.encoding()).isNull();
  }

  /**
   * The target level is read from the compiler plugin's configuration.
   *
   * <p>The target is a different question from the source: a build may compile Java 8 syntax into
   * Java 11 class files. The capture side reads it as {@code --target}, so the two sides have to
   * read the same thing.
   */
  @Test
  void readsTheTargetLevelFromThePluginConfiguration(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <source>8</source>
                  <target>11</target>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.targetLevel()).isEqualTo("11");
  }

  /**
   * The target level is read from a property too, when the plugin does not declare it.
   */
  @Test
  void readsTheTargetLevelFromAProperty(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <properties>
            <maven.compiler.target>17</maven.compiler.target>
          </properties>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.targetLevel()).isEqualTo("17");
  }

  /**
   * The directories are read from the pom, not hard-coded.
   *
   * <p>A pom may put its sources and its class files anywhere, so a hard-coded path is a
   * confident wrong answer for a project that declared a different one.
   */
  @Test
  void readsTheDeclaredDirectories(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <sourceDirectory>java/main</sourceDirectory>
            <testSourceDirectory>java/test</testSourceDirectory>
            <outputDirectory>build/classes</outputDirectory>
            <testOutputDirectory>build/test-classes</testOutputDirectory>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.sourceRoots(dir.toFile())).containsExactly("java/main");
    assertThat(loaded.testSourceRoot()).isEqualTo("java/test");
    assertThat(loaded.outputDirectory()).isEqualTo("build/classes");
    assertThat(loaded.testOutputDirectory()).isEqualTo("build/test-classes");
  }

  /**
   * A pom that declares no directory gets Maven's own defaults.
   */
  @Test
  void fallsBackToMavensOwnDirectoryDefaults(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.sourceRoots(dir.toFile())).containsExactly("src/main/java");
    assertThat(loaded.testSourceRoot()).isEqualTo("src/test/java");
    assertThat(loaded.outputDirectory()).isEqualTo("target/classes");
    assertThat(loaded.testOutputDirectory()).isEqualTo("target/test-classes");
  }

  /**
   * The extra arguments the compiler plugin passes to the compiler.
   *
   * <p>These are arguments the compiler plugin hands over verbatim, and they cannot be
   * reconstructed from the pom's other settings. {@code -proc:none} turns annotation processing
   * off; {@code -parameters} changes the class files. Either one makes the described compile
   * different from the performed one.
   */
  @Test
  void readsTheCompilerArguments(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <compilerArgs>
                    <arg>-parameters</arg>
                    <arg>-Xlint:all</arg>
                    <arg>-Werror</arg>
                  </compilerArgs>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.compilerArgs()).containsExactly("-parameters", "-Xlint:all", "-Werror");
  }

  /** A pom that declares no compiler arguments has none. */
  @Test
  void reportsNoCompilerArgumentsWhenNoneAreDeclared(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.compilerArgs()).isEmpty();
  }

  /**
   * The annotation processor paths.
   *
   * <p>They are the jars that decide which annotations are processed at all, and they are a path
   * of their own -- not part of the compile classpath.
   */
  @Test
  void readsTheAnnotationProcessorPaths(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <annotationProcessorPaths>
                    <path>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                      <version>1.18.30</version>
                    </path>
                    <path>
                      <groupId>com.google.auto.value</groupId>
                      <artifactId>auto-value-processor</artifactId>
                      <version>1.10.4</version>
                    </path>
                  </annotationProcessorPaths>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.annotationProcessorPaths())
        .containsExactly(
            "org.projectlombok:lombok:1.18.30",
            "com.google.auto.value:auto-value-processor:1.10.4");
  }

  /**
   * A child module inherits the compiler plugin's configuration from its parent.
   *
   * <p>This is the normal shape of a multi-module project: the parent configures the compiler
   * once, and every child inherits it. A lookup that only reads the module's own pom reports
   * "no language level" for every child of a well-configured parent -- and the analyser then
   * falls back to a guess.
   */
  @Test
  void aChildInheritsTheCompilerConfigurationFromItsParent(@TempDir Path dir) throws Exception {
    Path parent = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <properties>
            <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
          </properties>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <release>17</release>
                  <compilerArgs>
                    <arg>-parameters</arg>
                  </compilerArgs>
                  <annotationProcessorPaths>
                    <path>
                      <groupId>org.projectlombok</groupId>
                      <artifactId>lombok</artifactId>
                      <version>1.18.30</version>
                    </path>
                  </annotationProcessorPaths>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    Path childDir = Files.createDirectories(dir.resolve("child"));
    Path child =
        write(
            childDir.resolve("pom.xml"),
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
            </project>
            """);

    MavenPom loaded = MavenPom.read(child.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("17");
    assertThat(loaded.encoding()).isEqualTo(StandardCharsets.UTF_8);
    assertThat(loaded.compilerArgs()).containsExactly("-parameters");
    assertThat(loaded.annotationProcessorPaths())
        .containsExactly("org.projectlombok:lombok:1.18.30");
    // The parent's own pom is still readable, and still says the same thing.
    assertThat(MavenPom.read(parent.toFile()).languageLevel()).isEqualTo("17");
  }

  /**
   * The module's own configuration wins over the parent's.
   *
   * <p>This is what Maven itself does: the effective pom merges the parent into the child, with
   * the child on top. A child that pins a different release is compiled at that release.
   */
  @Test
  void theModulesOwnConfigurationWinsOverItsParents(@TempDir Path dir) throws Exception {
    write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>parent</artifactId>
          <version>1.0.0</version>
          <packaging>pom</packaging>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <release>17</release>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    Path childDir = Files.createDirectories(dir.resolve("child"));
    Path child =
        write(
            childDir.resolve("pom.xml"),
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <groupId>com.example</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>../pom.xml</relativePath>
              </parent>
              <artifactId>child</artifactId>
              <build>
                <plugins>
                  <plugin>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <configuration>
                      <release>21</release>
                    </configuration>
                  </plugin>
                </plugins>
              </build>
            </project>
            """);

    MavenPom loaded = MavenPom.read(child.toFile());

    assertThat(loaded.languageLevel()).isEqualTo("21");
  }

  /**
   * Another plugin's configuration is not the compiler's.
   *
   * <p>The compiler plugin is looked up by its artifact id. A {@code <configuration>} under some
   * other plugin must not be read as the compiler's -- {@code <release>} under a different plugin
   * means something else entirely.
   */
  @Test
  void anotherPluginsConfigurationIsNotTheCompilers(@TempDir Path dir) throws Exception {
    Path pom = write(dir.resolve("pom.xml"), """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
          <build>
            <plugins>
              <plugin>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                  <release>99</release>
                  <encoding>NOPE</encoding>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """);

    MavenPom loaded = MavenPom.read(pom.toFile());

    assertThat(loaded.languageLevel()).isNull();
    assertThat(loaded.encoding()).isNull();
  }

  private static Path write(Path pom, String xml) throws Exception {
    Files.writeString(pom, xml, StandardCharsets.UTF_8);
    return pom;
  }

  /** The file the pom reader takes. */
  private static File file(Path pom) {
    return pom.toFile();
  }
}
