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
package org.plumb.build.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link MavenSettings}. */
class MavenSettingsTest {

  @Test
  void readsLocalRepositoryFromSettingsFile(@TempDir Path dir) throws Exception {
    Path settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><localRepository>" + dir.resolve("repo") + "</localRepository></settings>",
        StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, null);

    assertThat(loaded.localRepository()).isEqualTo(dir.resolve("repo"));
  }

  @Test
  void explicitLocalRepositoryWinsOverSettingsFile(@TempDir Path dir) throws Exception {
    Path settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><localRepository>"
            + dir.resolve("fromSettings")
            + "</localRepository></settings>",
        StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, dir.resolve("explicit"));

    assertThat(loaded.localRepository()).isEqualTo(dir.resolve("explicit"));
  }

  @Test
  void usesMirrorIdAndUrl(@TempDir Path dir) throws Exception {
    Path settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><mirrors><mirror>"
            + "<id>alimaven</id><mirrorOf>central</mirrorOf>"
            + "<url>http://maven.aliyun.com/nexus/content/repositories/central/</url>"
            + "</mirror></mirrors></settings>",
        StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, null);

    // The mirror comes first -- it is the repository the user configured.
    assertThat(loaded.remotes().get(0).id()).isEqualTo("alimaven");
    assertThat(loaded.remotes().get(0).url())
        .isEqualTo("http://maven.aliyun.com/nexus/content/repositories/central/");
    // central stays available as a fallback: an artifact cached under
    // "central" is otherwise unservable in offline mode.
    assertThat(loaded.remotes()).extracting(MavenSettings.Remote::id).contains("central");
  }

  /**
   * The mirror id has to match the one recorded in the local repository's {@code
   * _remote.repositories} markers, otherwise Aether refuses to serve an artifact that is present on
   * disk. Keeping central in the list is what makes an artifact cached under {@code central}
   * servable on a machine whose settings.xml declares a differently-named mirror.
   */
  @Test
  void preservesMultipleMirrorsInDeclarationOrder(@TempDir Path dir) throws Exception {
    Path settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><mirrors>"
            + "<mirror><id>first</id><mirrorOf>central</mirrorOf><url>http://first/</url></mirror>"
            + "<mirror><id>second</id><mirrorOf>*</mirrorOf><url>http://second/</url></mirror>"
            + "</mirrors></settings>",
        StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, null);

    assertThat(loaded.remotes())
        .extracting(MavenSettings.Remote::id)
        .containsExactly("first", "second", "central");
  }

  @Test
  void fallsBackToCentralWhenNoMirrorIsConfigured(@TempDir Path dir) {
    Path settings = dir.resolve("settings.xml");
    try {
      Files.writeString(settings, "<settings></settings>", StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new AssertionError(e);
    }

    MavenSettings loaded = MavenSettings.load(settings, null);

    assertThat(loaded.remotes()).hasSize(1);
    assertThat(loaded.remotes().get(0).id()).isEqualTo(MavenSettings.CENTRAL_ID);
    assertThat(loaded.remotes().get(0).url()).isEqualTo(MavenSettings.CENTRAL_URL);
  }

  @Test
  void missingSettingsFileIsNotAnError(@TempDir Path dir) {
    MavenSettings loaded = MavenSettings.load(dir.resolve("absent.xml"), dir.resolve("repo"));

    assertThat(loaded.localRepository()).isEqualTo(dir.resolve("repo"));
    assertThat(loaded.remotes()).isNotEmpty();
  }

  @Test
  void malformedSettingsFileDegradesToDefaults(@TempDir Path dir) throws Exception {
    Path settings = dir.resolve("settings.xml");
    Files.writeString(settings, "this is not xml at all <<<", StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, dir.resolve("repo"));

    assertThat(loaded.localRepository()).isEqualTo(dir.resolve("repo"));
    assertThat(loaded.remotes()).isNotEmpty();
  }

  /**
   * An artifact cached under {@code central} must still resolve on a machine whose settings.xml
   * declares a differently-named mirror.
   *
   * <p>This is the failure the "mirror id must match the marker" note warns about. Aether records
   * the repository id in {@code _remote.repositories} and refuses to serve an artifact that was not
   * obtained from one of the configured remotes. If the configured remotes are only the mirror,
   * every artifact cached under {@code central} is reported as "present, but unavailable" -- so an
   * offline resolution returns nothing at all.
   *
   * <p>The repository is built here rather than taken from the developer's machine, so the test is
   * hermetic.
   */
  @Test
  void resolvesAnArtifactCachedUnderCentralDespiteADifferentlyNamedMirror(@TempDir Path dir)
      throws Exception {
    Path repo = dir.resolve("repo");
    // The local repository layout is groupId/artifactId/version, with the
    // group id's dots turned into directories: org.example -> org/example.
    Path artifactDir = repo.resolve("org").resolve("example").resolve("widget").resolve("1.0");
    Files.createDirectories(artifactDir);
    // A real pom: the artifact-descriptor reader parses it to find the
    // artifact's dependencies, so it has to be a well-formed model.
    String pom =
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>org.example</groupId>"
            + "<artifactId>widget</artifactId>"
            + "<version>1.0</version>"
            + "<packaging>jar</packaging>"
            + "</project>";
    Files.writeString(artifactDir.resolve("widget-1.0.pom"), pom, StandardCharsets.UTF_8);
    // A REAL jar, not a stub: Aether validates the artifact file, so four
    // bytes of garbage make the local repository manager decline the cached
    // artifact -- which then surfaces as a remote failure and hides the
    // behaviour this test is about.
    writeJar(artifactDir.resolve("widget-1.0.jar"));
    // The checksum sidecars are part of a real local repository: Aether's
    // enhanced local repository manager treats an artifact without one as
    // unusable.
    Files.writeString(
        artifactDir.resolve("_remote.repositories"),
        "widget-1.0.pom>central=\nwidget-1.0.jar>central=\n",
        StandardCharsets.UTF_8);

    Path settings = dir.resolve("settings.xml");
    Files.writeString(
        settings,
        "<settings><mirrors><mirror>"
            + "<id>alimaven</id><mirrorOf>central</mirrorOf>"
            + "<url>http://maven.aliyun.com/nexus/content/repositories/central/</url>"
            + "</mirror></mirrors></settings>",
        StandardCharsets.UTF_8);

    MavenSettings loaded = MavenSettings.load(settings, repo);
    TransitiveResolver resolver = new TransitiveResolver(loaded, true);

    List<File> files = resolver.resolveLenient("org.example:widget:1.0", "compile");

    assertThat(files).isNotEmpty();
    assertThat(files).extracting(File::getName).contains("widget-1.0.jar");
  }

  /**
   * Write a minimal but <b>valid</b> jar.
   *
   * <p>Aether validates the artifact it finds in the local repository, so a stub file makes the
   * resolver treat the cached artifact as unusable.
   */
  private static void writeJar(Path jar) throws Exception {
    try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jar))) {
      out.putNextEntry(new java.util.zip.ZipEntry("META-INF/MANIFEST.MF"));
      out.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
      out.closeEntry();
    }
  }
}
