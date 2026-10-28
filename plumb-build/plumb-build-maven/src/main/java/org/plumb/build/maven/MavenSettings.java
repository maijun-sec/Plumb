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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.maven.settings.Mirror;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.io.xpp3.SettingsXpp3Reader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The parts of a Maven {@code settings.xml} that matter for dependency resolution.
 *
 * <p>Two things are extracted:
 *
 * <ul>
 *   <li>the <b>local repository</b> path -- where jars live on disk;
 *   <li>the <b>mirrors</b> -- the remote repositories Aether should talk to.
 * </ul>
 *
 * <h2>Why the mirror id matters</h2>
 *
 * <p>Every artifact in the local repository carries a {@code _remote.repositories} marker file
 * recording the id of the repository it was downloaded from. Aether refuses to serve an artifact
 * that was not obtained from the repository being asked for. So when the local repo was populated
 * through a mirror called {@code alimaven}, asking for {@code central} yields
 *
 * <pre>
 *   The following artifacts could not be resolved: ... (present, but unavailable)
 * </pre>
 *
 * <p>even though the jar is sitting right there on disk. Using the mirror's <em>real</em> id --
 * rather than a hard-coded {@code central} -- is what makes offline resolution against a
 * pre-populated repository work.
 */
public final class MavenSettings {

  private static final Logger LOG = LoggerFactory.getLogger(MavenSettings.class);

  /** Repository id used when no mirror is configured. */
  public static final String CENTRAL_ID = "central";

  /** Fallback URL, matching Maven's own default central definition. */
  public static final String CENTRAL_URL = "https://repo1.maven.org/maven2";

  private final Path localRepository;
  private final List<Remote> remotes;

  private MavenSettings(Path localRepository, List<Remote> remotes) {
    this.localRepository = localRepository;
    this.remotes = List.copyOf(remotes);
  }

  /** A remote repository Aether can resolve against. */
  public record Remote(String id, String url) {}

  public Path localRepository() {
    return localRepository;
  }

  public List<Remote> remotes() {
    return remotes;
  }

  /**
   * Load the effective settings.
   *
   * @param settingsFile the {@code settings.xml} to read; may be {@code null}
   * @param localRepoOverride an explicit local repository path; wins over the one declared in
   *     {@code settings.xml}
   */
  public static MavenSettings load(Path settingsFile, Path localRepoOverride) {
    Settings settings = readSettings(settingsFile);

    Path localRepo = localRepoOverride;
    if (localRepo == null && settings != null) {
      String declared = settings.getLocalRepository();
      if (declared != null && !declared.isBlank()) {
        localRepo = Path.of(declared);
      }
    }
    if (localRepo == null) {
      localRepo = defaultLocalRepository();
    }

    List<Remote> remotes = remotesFrom(settings);
    return new MavenSettings(localRepo, remotes);
  }

  /**
   * Read the {@code settings.xml}, returning {@code null} when it is absent or unreadable. A
   * missing settings file is not an error -- Maven works fine without one -- so this degrades to
   * defaults instead of failing.
   */
  private static Settings readSettings(Path settingsFile) {
    if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
      if (settingsFile != null) {
        LOG.debug("Maven settings file {} not found, using defaults", settingsFile);
      }
      return null;
    }
    try (var reader = Files.newBufferedReader(settingsFile)) {
      return new SettingsXpp3Reader().read(reader);
    } catch (Exception e) {
      LOG.warn("Maven: could not read settings file {}: {}", settingsFile, e.getMessage());
      return null;
    }
  }

  /**
   * Build the remote repository list from the configured mirrors.
   *
   * <p>A mirror whose {@code mirrorOf} is {@code *} or {@code central} replaces central; anything
   * else is treated as an additional remote. Mirrors are emitted in declaration order so the first
   * configured mirror wins -- the same precedence Maven itself applies.
   *
   * <p>{@code central} itself is ALWAYS kept in the list, after the mirrors. That is not redundant:
   * every artifact in the local repository carries a {@code _remote.repositories} marker naming the
   * repository it came from, and Aether refuses to serve a cached artifact that was not obtained
   * from one of the configured remotes. A machine whose settings.xml declares a mirror (say {@code
   * alimaven}) but whose local repository was populated through {@code central} would otherwise
   * find every cached artifact "present, but unavailable" in offline mode -- the very failure the
   * mirror-id note above warns about. Keeping central as a fallback lets both kinds of cache be
   * served.
   */
  private static List<Remote> remotesFrom(Settings settings) {
    List<Remote> remotes = new ArrayList<>();
    if (settings != null && settings.getMirrors() != null) {
      for (Mirror mirror : settings.getMirrors()) {
        if (mirror == null || mirror.getId() == null || mirror.getUrl() == null) {
          continue;
        }
        remotes.add(new Remote(mirror.getId(), mirror.getUrl()));
      }
    }
    // central is always available: either as the only remote, or as the
    // fallback behind the configured mirrors.
    if (remotes.stream().noneMatch(r -> CENTRAL_ID.equals(r.id()))) {
      remotes.add(new Remote(CENTRAL_ID, CENTRAL_URL));
    }
    return remotes;
  }

  /**
   * The default local repository location: {@code ${user.home}/.m2/repository}, overridable via the
   * {@code maven.repo.local} system property -- which is how Maven itself is pointed at a different
   * cache.
   */
  public static Path defaultLocalRepository() {
    String prop = System.getProperty("maven.repo.local");
    if (prop != null && !prop.isBlank()) {
      return Path.of(prop);
    }
    return Path.of(System.getProperty("user.home"), ".m2", "repository");
  }

  /**
   * Locate the settings file Maven would use.
   *
   * <p>Checked in order: an explicit override, {@code ${user.home}/.m2/settings.xml}, then {@code
   * ${maven.home}/conf/settings.xml} where {@code maven.home} comes from {@code MAVEN_HOME} /
   * {@code M2_HOME} or the {@code maven.home} system property.
   */
  public static Path locateSettingsFile(Path explicit) {
    if (explicit != null) {
      return explicit;
    }
    Path userSettings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
    if (Files.isRegularFile(userSettings)) {
      return userSettings;
    }
    String mavenHome = System.getProperty("maven.home");
    if (mavenHome == null) {
      mavenHome = System.getenv("MAVEN_HOME");
    }
    if (mavenHome == null) {
      mavenHome = System.getenv("M2_HOME");
    }
    if (mavenHome != null && !mavenHome.isBlank()) {
      Path global = Path.of(mavenHome, "conf", "settings.xml");
      if (Files.isRegularFile(global)) {
        return global;
      }
    }
    return userSettings;
  }

  /** Exposed for the resolver session builder: id -&gt; url of every remote. */
  public Map<String, String> remoteUrls() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Remote r : remotes) {
      map.put(r.id(), r.url());
    }
    return map;
  }

  /** A builder that starts from this instance's values. */
  public Builder toBuilder() {
    return new Builder(this);
  }

  /** Builder for {@link MavenSettings}. */
  public static final class Builder {
    private Path localRepository;
    private List<Remote> remotes;

    private Builder(MavenSettings source) {
      this.localRepository = source.localRepository;
      this.remotes = new ArrayList<>(source.remotes);
    }

    public Builder localRepository(Path v) {
      this.localRepository = v;
      return this;
    }

    public Builder remotes(List<Remote> v) {
      this.remotes = new ArrayList<>(v);
      return this;
    }

    public MavenSettings build() {
      return new MavenSettings(localRepository, remotes);
    }
  }
}
