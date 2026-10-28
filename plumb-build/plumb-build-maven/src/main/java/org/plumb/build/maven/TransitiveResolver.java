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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.DependencyResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the <b>full transitive</b> dependency closure of a Maven artifact using the Maven
 * Resolver (Aether).
 *
 * <p>This is the piece that {@code plumb-build-core}'s {@code MavenAdapter} deliberately leaves
 * out: that adapter parses {@code pom.xml} statically and resolves only the coordinates a module
 * declares directly. The transitive tree -- and Maven's "nearest wins" conflict resolution across
 * it -- is what this class computes.
 *
 * <h2>Scope</h2>
 *
 * <p>Resolution runs against the local repository and the remotes configured in {@code
 * settings.xml}. When the local repository already holds the artifacts, resolution succeeds without
 * network access.
 *
 * <h2>Repository ids</h2>
 *
 * <p>The remote repository id is taken from the configured mirror rather than hard-coded to {@code
 * central}. Aether tracks the origin of each cached artifact in a {@code _remote.repositories}
 * marker and refuses to serve an artifact obtained from a different repository id -- which surfaces
 * as {@code (present, but unavailable)} when resolving offline.
 */
public final class TransitiveResolver {

  private static final Logger LOG = LoggerFactory.getLogger(TransitiveResolver.class);

  private final RepositorySystem system;
  private final MavenSettings settings;
  private final boolean offline;

  public TransitiveResolver(MavenSettings settings) {
    this(settings, false);
  }

  public TransitiveResolver(MavenSettings settings, boolean offline) {
    this.settings = settings;
    this.offline = offline;
    this.system = newRepositorySystem();
  }

  // ------------------------------------------------------------------
  // repository system wiring
  // ------------------------------------------------------------------

  /**
   * Build a {@link RepositorySystem}.
   *
   * <p>Uses the supplier rather than a hand-built {@code DefaultServiceLocator}: a bare locator
   * does not register the repository system itself, so {@code getService(RepositorySystem.class)}
   * returns {@code null}. The supplier wires up the whole service set -- repository system, session
   * factory, local repository managers, connectors and transports -- by discovering them through
   * {@code ServiceLoader}, and it is the replacement the resolver project recommends for the
   * deprecated {@code MavenRepositorySystemUtils} helper.
   */
  private static RepositorySystem newRepositorySystem() {
    return new org.eclipse.aether.supplier.RepositorySystemSupplier().get();
  }

  // ------------------------------------------------------------------
  // session
  // ------------------------------------------------------------------

  /** Create a resolver session bound to the configured local repository. */
  public RepositorySystemSession newSession() {
    DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
    LocalRepository localRepository = new LocalRepository(settings.localRepository().toFile());
    session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepository));
    session.setOffline(offline);
    session.setSystemProperties(System.getProperties());
    return session;
  }

  /** The remote repositories to resolve against, in configured order. */
  private List<RemoteRepository> remotes() {
    List<RemoteRepository> list = new ArrayList<>();
    for (MavenSettings.Remote r : settings.remotes()) {
      list.add(new RemoteRepository.Builder(r.id(), "default", r.url()).build());
    }
    return list;
  }

  // ------------------------------------------------------------------
  // resolution
  // ------------------------------------------------------------------

  /**
   * Resolve the transitive closure of {@code groupId:artifactId:version}.
   *
   * @param coordinates the artifact coordinates
   * @param scope the Maven scope to collect under ({@code compile}, {@code test}, ...)
   * @return the resolved artifact files, in resolution order
   * @throws DependencyResolutionException if the artifact cannot be resolved
   */
  public List<File> resolve(String coordinates, String scope) throws DependencyResolutionException {
    return resolve(coordinates, scope, newSession());
  }

  /**
   * Resolve the transitive closure of {@code groupId:artifactId:version} using a caller-supplied
   * session.
   *
   * <p>The repositories are offered <b>one at a time</b>.
   *
   * <p>This is a correctness requirement, not an optimisation. Every artifact in the local
   * repository carries a {@code _remote.repositories} marker naming the repository id it was cached
   * under, and the resolver serves a cached artifact only when the request names a repository with
   * that id. A request that lists several repositories is not enough: the artifact resolver ends up
   * asking a repository whose id does not match the marker, and in offline mode that aborts the
   * whole resolution with {@code Cannot access <repo> in offline mode} even though the artifact is
   * sitting right there on disk.
   *
   * <p>So each configured remote gets its turn as a single-repository request, and the first that
   * produces a result wins.
   *
   * @param coordinates the artifact coordinates
   * @param scope the Maven scope to collect under
   * @param session the resolver session
   * @return the resolved artifact files, in resolution order
   * @throws DependencyResolutionException if the artifact cannot be resolved
   */
  public List<File> resolve(String coordinates, String scope, RepositorySystemSession session)
      throws DependencyResolutionException {
    DependencyResolutionException last = null;
    for (RemoteRepository remote : remotes()) {
      CollectRequest collectRequest = new CollectRequest();
      collectRequest.setRoot(new Dependency(new DefaultArtifact(coordinates), scope));
      collectRequest.setRepositories(List.of(remote));

      DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
      try {
        DependencyResult result = system.resolveDependencies(session, dependencyRequest);
        List<File> files = new ArrayList<>();
        for (ArtifactResult artifactResult : result.getArtifactResults()) {
          Artifact artifact = artifactResult.getArtifact();
          if (artifact != null && artifact.getFile() != null) {
            files.add(artifact.getFile());
          }
        }
        if (!files.isEmpty()) {
          return files;
        }
      } catch (DependencyResolutionException e) {
        last = e;
      }
    }
    if (last != null) {
      throw last;
    }
    throw new DependencyResolutionException(
        (DependencyResult) null, new IllegalStateException("no remote repository is configured"));
  }

  /**
   * Resolve a prepared {@link CollectRequest}.
   *
   * <p>Resolving the whole graph in one request is what lets Aether apply Maven's "nearest wins"
   * conflict resolution across it. Resolving each dependency separately would happily yield a
   * classpath containing two different versions of the same artifact.
   *
   * <p>As in {@link #resolve(String, String, RepositorySystemSession)}, each remote is tried on its
   * own, so that a repository cached under one id is still reachable when a different id is asked
   * for first.
   *
   * @param collectRequest the prepared request
   * @return the resolution result
   * @throws DependencyResolutionException if resolution fails
   */
  public DependencyResult resolveCollectRequest(CollectRequest collectRequest)
      throws DependencyResolutionException {
    List<RemoteRepository> candidates =
        collectRequest.getRepositories().isEmpty() ? remotes() : collectRequest.getRepositories();

    DependencyResolutionException last = null;
    for (RemoteRepository remote : candidates) {
      collectRequest.setRepositories(List.of(remote));
      DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
      try {
        DependencyResult result = system.resolveDependencies(newSession(), dependencyRequest);
        if (!result.getArtifactResults().isEmpty()) {
          return result;
        }
      } catch (DependencyResolutionException e) {
        last = e;
      }
    }
    if (last != null) {
      throw last;
    }
    throw new DependencyResolutionException(
        (DependencyResult) null, new IllegalStateException("no remote repository is configured"));
  }

  /**
   * Resolve the transitive closure of {@code groupId:artifactId:version} and return the distinct
   * jar files.
   *
   * <p>Never throws: an unresolvable dependency is logged and skipped, because a partially resolved
   * classpath is still more useful to a static analyser than no classpath at all.
   *
   * @param coordinates the artifact coordinates
   * @param scope the Maven scope to collect under
   * @return the resolved jar files, de-duplicated
   */
  public List<File> resolveLenient(String coordinates, String scope) {
    Set<File> files = new LinkedHashSet<>();
    try {
      files.addAll(resolve(coordinates, scope));
    } catch (DependencyResolutionException e) {
      LOG.warn("Maven: could not resolve {} ({}): {}", coordinates, scope, e.getMessage());
      var result = e.getResult();
      if (result != null) {
        for (var cause : result.getCollectExceptions()) {
          LOG.debug("Maven: resolution detail for {}: {}", coordinates, cause.getMessage());
        }
      }
    }
    return new ArrayList<>(files);
  }
}
