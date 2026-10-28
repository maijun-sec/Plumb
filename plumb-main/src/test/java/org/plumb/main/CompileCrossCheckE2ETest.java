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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.CompileDiscrepancy;
import org.plumb.build.maven.MavenResolverAdapter;

/**
 * The cross-check has to reach the user.
 *
 * <p><b>Why this test exists.</b> The cross-check compares what the build declared against what the
 * compiler really did. A comparison that nobody can see is a comparison nobody benefits from, so
 * the differences have to come out of the adapter the CLI drives.
 *
 * <p>And the differences have to be <em>real</em>: a capture that says the compiler ran at Java 11
 * while the pom says 17 is a genuine disagreement, and it has to be reported rather than resolved.
 */
class CompileCrossCheckE2ETest {

  @Test
  void theDifferencesBetweenTheCaptureAndThePomAreReported(@TempDir Path dir) throws Exception {
    Path project = Files.createDirectories(dir.resolve("project"));
    Files.createDirectories(project.resolve("src/main/java"));
    Files.writeString(
        project.resolve("pom.xml"),
        """
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
                  <release>17</release>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """,
        StandardCharsets.UTF_8);

    // A capture that says the compiler ran at 11, while the pom says 17.
    Path captures = Files.createDirectories(dir.resolve("captures"));
    Files.writeString(
        captures.resolve("javac-1.json"),
        """
        {
          "api": "api",
          "compiler": "javac",
          "argv": ["-d", "target/classes"],
          "encoding": "UTF-8",
          "languageLevel": "11",
          "outputDirectory": "target/classes",
          "classpath": [],
          "sourcePath": [],
          "modulePath": [],
          "processorPath": [],
          "bootClassPath": [],
          "sourceFiles": ["src/main/java/Main.java"]
        }
        """,
        StandardCharsets.UTF_8);

    List<CompileDiscrepancy> differences = new MavenResolverAdapter().crossCheck(project, captures);

    assertThat(differences).as("the disagreement must be reported").isNotEmpty();
    CompileDiscrepancy difference = differences.get(0);
    assertThat(difference.parameter()).isEqualTo("languageLevel");
    assertThat(difference.declared()).isEqualTo("17");
    assertThat(difference.observed()).isEqualTo("11");
    assertThat(difference.isConflict()).isTrue();
    // The report has to say which module and which capture, or the reader
    // cannot tell what it is looking at.
    assertThat(difference.detail()).contains("demo").contains("javac-1.json");
  }

  @Test
  void aCaptureThatAgreesWithThePomIsNotReported(@TempDir Path dir) throws Exception {
    Path project = Files.createDirectories(dir.resolve("project"));
    Files.createDirectories(project.resolve("src/main/java"));
    Files.writeString(
        project.resolve("pom.xml"),
        """
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
                  <release>17</release>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>
        """,
        StandardCharsets.UTF_8);

    Path captures = Files.createDirectories(dir.resolve("captures"));
    Files.writeString(
        captures.resolve("javac-1.json"),
        """
        {
          "api": "api",
          "compiler": "javac",
          "argv": ["-d", "target/classes"],
          "encoding": "UTF-8",
          "languageLevel": "17",
          "outputDirectory": "target/classes",
          "classpath": [],
          "sourcePath": [],
          "modulePath": [],
          "processorPath": [],
          "bootClassPath": [],
          "sourceFiles": ["src/main/java/Main.java"]
        }
        """,
        StandardCharsets.UTF_8);

    List<CompileDiscrepancy> differences = new MavenResolverAdapter().crossCheck(project, captures);

    assertThat(differences).as("the two sides agree").isEmpty();
  }

  /** A capture directory that does not exist is not a difference; it is nothing to compare. */
  @Test
  void anAbsentCaptureDirectoryComparesToNothing(@TempDir Path dir) throws Exception {
    Path project = Files.createDirectories(dir.resolve("project"));
    Files.writeString(
        project.resolve("pom.xml"),
        """
        <project>
          <modelVersion>4.0.0</modelVersion>
          <groupId>com.example</groupId>
          <artifactId>demo</artifactId>
          <version>1.0.0</version>
        </project>
        """,
        StandardCharsets.UTF_8);

    List<CompileDiscrepancy> differences =
        new MavenResolverAdapter().crossCheck(project, dir.resolve("nowhere"));

    assertThat(differences).isEmpty();
  }
}
