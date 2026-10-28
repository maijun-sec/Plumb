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
package org.plumb.build.ant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildProject;
import org.plumb.build.BuildSystemRegistry;

class AntAdapterTest {

  private static final String BUILD_XML =
      """
      <?xml version="1.0" encoding="UTF-8"?>
      <project name="demo" default="compile" basedir=".">
          <property name="src.dir"     value="src"/>
          <property name="build.dir"   value="build"/>
          <property name="lib.dir"     value="lib"/>

          <target name="compile">
              <mkdir dir="${build.dir}/classes"/>
              <javac srcdir="${src.dir}" destdir="${build.dir}/classes">
                  <classpath>
                      <pathelement path="${lib.dir}/guava.jar"/>
                      <pathelement location="${lib.dir}/commons-io.jar"/>
                  </classpath>
              </javac>
          </target>
      </project>
      """;

  @Test
  void probe_findsBuildXml(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.xml"), BUILD_XML);
    AntAdapter adapter = new AntAdapter();
    assertThat(adapter.probe(tmp)).isTrue();
  }

  @Test
  void probe_rejectsWithoutBuildXml(@TempDir Path tmp) {
    assertThat(new AntAdapter().probe(tmp)).isFalse();
  }

  @Test
  void discover_picksUpSourcesAndClasspath(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("build.xml"), BUILD_XML);
    Files.createDirectories(tmp.resolve("src/com/example"));
    Files.writeString(tmp.resolve("src/com/example/Foo.java"), "package com.example; class Foo {}");
    // Create the lib jars so the classpath entries are real files.
    Files.createDirectories(tmp.resolve("lib"));
    Files.writeString(tmp.resolve("lib/guava.jar"), "fake");
    Files.writeString(tmp.resolve("lib/commons-io.jar"), "fake");

    BuildProject bp = new AntAdapter().discover(tmp);

    assertThat(bp.systemName()).isEqualTo("ant");
    assertThat(bp.sourceFiles()).containsExactly(tmp.resolve("src/com/example/Foo.java"));
    assertThat(bp.classpathJars())
        .containsExactlyInAnyOrder(tmp.resolve("lib/guava.jar"), tmp.resolve("lib/commons-io.jar"));
  }

  @Test
  void discover_handlesLiteralSrcdir(@TempDir Path tmp) throws IOException {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <target name="compile">
                <javac srcdir="src/main/java"/>
            </target>
        </project>
        """;
    Files.writeString(tmp.resolve("build.xml"), xml);
    Files.createDirectories(tmp.resolve("src/main/java"));
    Files.writeString(tmp.resolve("src/main/java/A.java"), "class A {}");
    BuildProject bp = new AntAdapter().discover(tmp);
    assertThat(bp.sourceFiles()).containsExactly(tmp.resolve("src/main/java/A.java"));
  }

  @Test
  void registry_advertisesAnt() {
    BuildAdapter a = BuildSystemRegistry.get().byName("ant");
    if (a == null) return; // service registration is optional per adapter
    assertThat(a.systemName()).isEqualTo("ant");
  }

  // -------------------------------------------------------------------------
  // P2 coverage: multiple srcdirs / fileset / dirset / import
  // -------------------------------------------------------------------------

  @Test
  void discover_multipleSrcdirsViaColonSeparator(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <target name="compile">
                <javac srcdir="src1:src2"/>
            </target>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src1"));
    Files.writeString(tmp.resolve("src1/A.java"), "class A {}");
    Files.createDirectories(tmp.resolve("src2"));
    Files.writeString(tmp.resolve("src2/B.java"), "class B {}");
    BuildProject bp = new AntAdapter().discover(tmp);
    assertThat(bp.sourceFiles())
        .containsExactlyInAnyOrder(tmp.resolve("src1/A.java"), tmp.resolve("src2/B.java"));
  }

  @Test
  void discover_filesetPicksUpSources(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <target name="compile">
                <copy todir="build">
                    <fileset dir="src"/>
                </copy>
            </target>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src/com/example"));
    Files.writeString(tmp.resolve("src/com/example/Foo.java"), "class Foo {}");
    BuildProject bp = new AntAdapter().discover(tmp);
    assertThat(bp.sourceFiles()).contains(tmp.resolve("src/com/example/Foo.java"));
  }

  @Test
  void discover_importFollowsSubBuildXml(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve("build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <import file="sub-build.xml"/>
            <target name="compile">
                <javac srcdir="src"/>
            </target>
        </project>
        """);
    Files.writeString(
        tmp.resolve("sub-build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <target name="sub">
                <javac srcdir="sub-src"/>
            </target>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    Files.createDirectories(tmp.resolve("sub-src"));
    Files.writeString(tmp.resolve("sub-src/B.java"), "class B {}");
    BuildProject bp = new AntAdapter().discover(tmp);
    // Both top-level sources and the imported sub-build's sources are picked up.
    assertThat(bp.sourceFiles())
        .containsExactlyInAnyOrder(tmp.resolve("src/A.java"), tmp.resolve("sub-src/B.java"));
  }

  @Test
  void discover_importCycleIsSkipped(@TempDir Path tmp) throws IOException {
    // build.xml imports sub.xml which imports build.xml — the cycle must not loop forever.
    Files.writeString(
        tmp.resolve("build.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <import file="sub.xml"/>
            <target name="compile">
                <javac srcdir="src"/>
            </target>
        </project>
        """);
    Files.writeString(
        tmp.resolve("sub.xml"),
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
            <import file="build.xml"/>
        </project>
        """);
    Files.createDirectories(tmp.resolve("src"));
    Files.writeString(tmp.resolve("src/A.java"), "class A {}");
    BuildProject bp = new AntAdapter().discover(tmp);
    // The import cycle is silently dropped after the first occurrence; the top-level <javac>
    // still contributes its source.
    assertThat(bp.sourceFiles()).contains(tmp.resolve("src/A.java"));
  }
}
