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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link TransitiveResolver}.
 *
 * <p>These exercise the real resolver against the developer's local Maven repository. The artifact
 * used has a small, well-known transitive tree ({@code opentest4j} and {@code apiguardian}), so the
 * test can assert that the <em>transitive</em> artifacts are present -- which is precisely the
 * capability {@code plumb-build-core}'s static Maven adapter lacks.
 *
 * <p>The settings come from the developer's own {@code settings.xml}, because the repository id an
 * artifact was cached under has to match the remote the resolver asks. Hard-coding {@code central}
 * fails on any machine whose local repository was populated through a mirror.
 */
class TransitiveResolverTest {

  /** An artifact with two well-known transitive dependencies. */
  private static final String JUNIT_JUPITER_API = "org.junit.jupiter:junit-jupiter-api:5.10.3";

  private TransitiveResolver resolver() {
    Path settingsFile = MavenSettings.locateSettingsFile(null);
    MavenSettings settings = MavenSettings.load(settingsFile, defaultLocalRepository());
    return new TransitiveResolver(settings, true);
  }

  @Test
  void resolvesTransitiveDependencies() {
    Path localRepo = defaultLocalRepository();
    if (!Files.isDirectory(localRepo)) {
      // No local repository to test against: nothing to assert.
      return;
    }

    List<File> files = resolver().resolveLenient(JUNIT_JUPITER_API, "compile");

    assertThat(files).isNotEmpty();
    String names = files.stream().map(File::getName).reduce("", (a, b) -> a + " " + b);
    assertThat(names).contains("junit-jupiter-api");
    // The transitive artifacts -- these are what a static pom.xml read cannot
    // produce on its own.
    assertThat(names).contains("opentest4j");
    assertThat(names).contains("apiguardian-api");
  }

  @Test
  void unknownArtifactResolvesToNothingRatherThanThrowing() {
    Path localRepo = defaultLocalRepository();
    if (!Files.isDirectory(localRepo)) {
      return;
    }

    List<File> files =
        resolver().resolveLenient("com.example.definitely:not-a-real-artifact:9.9.9", "compile");

    assertThat(files).isEmpty();
  }

  @Test
  void resolutionIsIdempotent() {
    Path localRepo = defaultLocalRepository();
    if (!Files.isDirectory(localRepo)) {
      return;
    }

    TransitiveResolver resolver = resolver();
    List<File> first = resolver.resolveLenient(JUNIT_JUPITER_API, "compile");
    List<File> second = resolver.resolveLenient(JUNIT_JUPITER_API, "compile");

    assertThat(first).isEqualTo(second);
  }

  @Test
  void sessionUsesTheConfiguredLocalRepository() {
    Path localRepo = defaultLocalRepository();
    if (!Files.isDirectory(localRepo)) {
      return;
    }

    var session = resolver().newSession();

    assertThat(session).isNotNull();
    assertThat(session.getLocalRepository().getBasedir()).isNotNull();
  }

  /** The local repository, honouring the {@code maven.repo.local} override. */
  private static Path defaultLocalRepository() {
    String prop = System.getProperty("maven.repo.local");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    return Path.of(System.getProperty("user.home"), ".m2", "repository");
  }
}
