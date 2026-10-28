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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.resolution.DependencyResult;
import org.plumb.build.BuildAdapter;
import org.plumb.build.BuildAdapterException;
import org.plumb.build.BuildProject;
import org.plumb.build.CompileDiscrepancy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Maven adapter that resolves the <b>full transitive</b> dependency graph.
 *
 * <p>This complements {@code plumb-build-core}'s {@code MavenAdapter}: the core adapter reads
 * {@code pom.xml} statically and stops at the directly declared dependencies, while this one walks
 * the whole tree through the Maven Resolver so the classpath matches what the compiler actually
 * sees.
 *
 * <p>It is registered under the build-system name {@code maven-resolver} so the two adapters can
 * coexist and be selected independently -- the core adapter is dependency-light and fast, this one
 * is accurate and slower.
 */
public final class MavenResolverAdapter implements BuildAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(MavenResolverAdapter.class);

  /** The build-system id this adapter registers under. */
  public static final String SYSTEM_NAME = "maven-resolver";

  private final MavenSettings settings;
  private final TransitiveResolver resolver;

  public MavenResolverAdapter() {
    this(MavenSettings.load(MavenSettings.locateSettingsFile(null), null));
  }

  public MavenResolverAdapter(MavenSettings settings) {
    this.settings = settings;
    this.resolver = new TransitiveResolver(settings);
  }

  @Override
  public String systemName() {
    return SYSTEM_NAME;
  }

  @Override
  public boolean probe(Path projectRoot) {
    if (projectRoot == null) {
      return false;
    }
    return Files.isRegularFile(projectRoot.resolve("pom.xml"));
  }

  @Override
  public BuildProject discover(Path projectRoot) throws BuildAdapterException {
    if (!probe(projectRoot)) {
      throw new BuildAdapterException("Maven project root must contain a pom.xml: " + projectRoot);
    }

    Path rootPom = projectRoot.resolve("pom.xml");
    List<ModuleDescriptor> modules = describeModules(projectRoot, rootPom);

    // Resolve the whole reactor's dependencies in ONE pass. Aether then
    // applies "nearest wins" across the entire graph, which is what Maven
    // itself does. Resolving each module separately would let two modules
    // contribute two different versions of the same artifact.
    List<String> allCoordinates = new ArrayList<>();
    for (ModuleDescriptor module : modules) {
      for (String coordinates : module.dependencyCoordinates()) {
        if (!allCoordinates.contains(coordinates)) {
          allCoordinates.add(coordinates);
        }
      }
    }
    List<Path> resolvedClasspath = resolveAll(allCoordinates);

    List<Path> allSources = new ArrayList<>();
    List<Path> allTests = new ArrayList<>();
    LinkedHashSet<Path> allClasspath = new LinkedHashSet<>(resolvedClasspath);
    List<Path> sourceRoots = new ArrayList<>();
    List<Path> moduleRoots = new ArrayList<>();
    Map<String, BuildProject.Module> moduleMap = new LinkedHashMap<>();

    for (ModuleDescriptor module : modules) {
      for (Path root : module.sourceRoots()) {
        // A module may declare a source root that does not exist (an
        // aggregator pom has no sources of its own). Only real directories
        // become source roots.
        if (Files.isDirectory(root)) {
          sourceRoots.add(root);
        }
      }
      moduleRoots.add(projectRoot);
      // The module's OWN file list is every file under EVERY source root it
      // declares.  Taking only the first root would drop the files in the
      // directories a build added with build-helper-maven-plugin, and the
      // per-module view would be smaller than the module.
      List<Path> moduleSources = new ArrayList<>();
      for (Path root : module.sourceRoots()) {
        moduleSources.addAll(listJavaFiles(root));
      }
      allSources.addAll(moduleSources);
      if (module.testSourceRoot() != null) {
        allTests.addAll(listJavaFiles(module.testSourceRoot()));
      }
      moduleMap.put(
          module.id(),
          new BuildProject.Module(
              module.id(),
              module.sourceRoots().stream().findFirst().orElse(null),
              moduleSources,
              listJavaFiles(module.testSourceRoot()),
              module.classpath(),
              BuildProject.Scope.COMPILE,
              module.languageLevel(),
              module.encoding()));
    }

    return BuildProject.builder(SYSTEM_NAME)
        .version("Maven " + settings.localRepository())
        .sourceFiles(allSources)
        .testSourceFiles(allTests)
        .classpathJars(new ArrayList<>(allClasspath))
        .sourceRoots(sourceRoots)
        .moduleRoots(moduleRoots)
        .modules(moduleMap)
        .build();
  }

  /**
   * Compares the captures in a directory against what this project declared.
   *
   * <p><b>Why the two are compared.</b> The pom says what the build <em>declared</em>, and the
   * capture says what the compiler <em>actually did</em>. They answer the same questions, and they
   * are used together -- so a disagreement means one of them is wrong, and the analysis is wrong in
   * a way nobody can see.
   *
   * <p>Nothing is resolved here. The differences are reported, with both values, because there is
   * no right answer to pick in general: the pom may be stale, the capture may be from a different
   * build, and the two may describe different modules.
   *
   * <p><b>Why each capture is compared against every module.</b> A capture does not say which
   * module it belongs to, so the module is not assumed. A capture that disagrees with every module
   * is still reported -- that is the interesting case.
   *
   * @param projectRoot the project root
   * @param captureDir the directory the capture agent wrote to
   * @return the differences, sorted; empty when the two sides agree
   */
  public List<CompileDiscrepancy> crossCheck(Path projectRoot, Path captureDir) {
    List<CompileDiscrepancy> out = new ArrayList<>();
    if (projectRoot == null || captureDir == null || !Files.isDirectory(captureDir)) {
      return out;
    }
    Path rootPom = projectRoot.resolve("pom.xml");
    if (!Files.isRegularFile(rootPom)) {
      return out;
    }
    List<ModuleDescriptor> modules = describeModules(projectRoot, rootPom);

    List<Path> captures;
    try (var stream = Files.list(captureDir)) {
      captures = stream.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
    } catch (java.io.IOException e) {
      return out;
    }

    for (Path capture : captures) {
      String json;
      try {
        json = Files.readString(capture, java.nio.charset.StandardCharsets.UTF_8);
      } catch (java.io.IOException e) {
        continue;
      }
      for (ModuleDescriptor module : modules) {
        for (CompileDiscrepancy difference : CompileCrossCheck.compare(json, module)) {
          out.add(
              new CompileDiscrepancy(
                  difference.parameter(),
                  difference.declared(),
                  difference.observed(),
                  "module "
                      + module.id()
                      + ", capture "
                      + capture.getFileName()
                      + ": "
                      + difference.detail()));
        }
      }
    }
    // A fixed order, so two runs over the same inputs report the same
    // differences in the same order.
    out.sort(
        java.util.Comparator.comparing(CompileDiscrepancy::parameter)
            .thenComparing(CompileDiscrepancy::detail)
            .thenComparing(d -> String.valueOf(d.declared()))
            .thenComparing(d -> String.valueOf(d.observed())));
    return out;
  }

  // ------------------------------------------------------------------
  // module discovery
  // ------------------------------------------------------------------

  /**
   * Describe every module of the reactor.
   *
   * <p>The module list is read from the root pom's {@code <modules>} section when present; a
   * single-module project is described by the root pom itself.
   *
   * <p>The walk is <b>recursive</b>: a module may itself be an aggregator whose sub-modules live
   * one level down. An aggregator has no sources of its own, so it contributes only its dependency
   * declarations, while its sub-modules contribute the sources.
   */
  private List<ModuleDescriptor> describeModules(Path projectRoot, Path rootPom) {
    List<ModuleDescriptor> modules = new ArrayList<>();
    collectModules(projectRoot, rootPom, modules, 0);
    return modules;
  }

  /** Depth bound on the module walk, to survive a cyclic {@code <modules>}. */
  private static final int MAX_MODULE_DEPTH = 16;

  private void collectModules(
      Path moduleRoot, Path pomFile, List<ModuleDescriptor> out, int depth) {
    if (depth > MAX_MODULE_DEPTH) {
      LOG.warn("Maven: module nesting too deep at {}", moduleRoot);
      return;
    }
    MavenPom pom = MavenPom.read(pomFile.toFile());
    List<String> declared = pom.moduleNames();

    if (declared.isEmpty()) {
      out.add(describeSingleModule(moduleRoot, pom));
      return;
    }

    // An aggregator still declares dependencies of its own, and they belong
    // on the classpath of everything it aggregates.
    for (String name : declared) {
      Path childDir = moduleRoot.resolve(name).normalize();
      // Refuse a <module> that escapes the module root.
      if (!childDir.startsWith(moduleRoot)) {
        LOG.warn("Maven: skipping module {} -- path escapes {}", name, moduleRoot);
        continue;
      }
      Path childPom = childDir.resolve("pom.xml");
      if (!Files.isRegularFile(childPom)) {
        LOG.warn("Maven: skipping module {} -- no pom.xml at {}", name, childPom);
        continue;
      }
      collectModules(childDir, childPom, out, depth + 1);
    }

    // The aggregator itself, if it has sources or dependencies of its own.
    if (!pom.dependencyCoordinates().isEmpty() || hasSources(moduleRoot)) {
      out.add(describeSingleModule(moduleRoot, pom));
    }
  }

  /** Whether a module directory holds any Java source. */
  private static boolean hasSources(Path moduleRoot) {
    return Files.isDirectory(moduleRoot.resolve("src/main/java"));
  }

  /**
   * Describe one module: its source roots, output directories and the <b>transitive</b> closure of
   * its declared dependencies.
   */
  private ModuleDescriptor describeSingleModule(Path moduleRoot, MavenPom pom) {
    // The directories come from the pom, so a project that declares its
    // own source or output directory is described correctly.  They are
    // relative to the module's directory, which is where the pom lives.
    //
    // EVERY declared source root is kept, not just the first one: a build
    // that adds source directories with build-helper-maven-plugin compiles
    // them, so a module that declares them has more than one.  Taking only
    // the first would describe a module that is smaller than it is, and the
    // analyser would miss every file in the added directories.
    Set<Path> sourceRoots = new LinkedHashSet<>();
    for (String root : pom.sourceRoots(moduleRoot.toFile())) {
      sourceRoots.add(moduleRoot.resolve(root));
    }
    Path testSourceRoot = moduleRoot.resolve(pom.testSourceRoot());
    Path output = moduleRoot.resolve(pom.outputDirectory());
    Path testOutput = moduleRoot.resolve(pom.testOutputDirectory());

    return ModuleDescriptor.builder(pom.artifactId())
        .sourceRoots(sourceRoots)
        .testSourceRoot(testSourceRoot)
        .outputDirectory(output)
        .testOutputDirectory(testOutput)
        .languageLevel(pom.languageLevel())
        .encoding(pom.encoding())
        .dependencyCoordinates(pom.dependencyCoordinates())
        .compilerArgs(pom.compilerArgs())
        .annotationProcessorPaths(pom.annotationProcessorPaths())
        .build();
  }

  /**
   * Resolve the transitive closure of a set of root dependencies in one pass.
   *
   * <p>Doing it as a single {@link CollectRequest} rather than one request per dependency lets
   * Aether apply Maven's "nearest wins" conflict resolution across the whole graph -- resolving
   * each dependency separately would happily produce a classpath containing two different versions
   * of the same artifact.
   *
   * @param coordinates the root dependency coordinates
   * @return the resolved jar files
   */
  public List<Path> resolveAll(List<String> coordinates) {
    if (coordinates == null || coordinates.isEmpty()) {
      return List.of();
    }
    CollectRequest collectRequest = new CollectRequest();
    for (String c : coordinates) {
      collectRequest.addDependency(new Dependency(new DefaultArtifact(c), "compile"));
    }
    // No repositories are set here: the resolver tries each configured remote
    // on its own, which is what lets a repository cached under one id stay
    // reachable when a different id is asked for first.

    try {
      DependencyResult result = resolver.resolveCollectRequest(collectRequest);
      List<Path> paths = new ArrayList<>();
      for (var ar : result.getArtifactResults()) {
        if (ar.getArtifact() != null && ar.getArtifact().getFile() != null) {
          paths.add(ar.getArtifact().getFile().toPath());
        }
      }
      return paths;
    } catch (Exception e) {
      LOG.warn("Maven: transitive resolution failed: {}", e.getMessage());
      return List.of();
    }
  }

  /**
   * Resolve the transitive closure of a set of root dependencies in one pass.
   *
   * <p>Doing it as a single {@link CollectRequest} rather than one request per dependency lets
   * Aether apply Maven's "nearest wins" conflict resolution across the whole graph -- resolving
   * each dependency separately would happily produce a classpath containing two different versions
   * of the same artifact.
   *
   * @param coordinates the root dependency coordinates
   * @return the resolved jar files
   */
  private static List<Path> listJavaFiles(Path root) {
    if (root == null || !Files.isDirectory(root)) {
      return List.of();
    }
    try (var stream = Files.walk(root)) {
      return stream
          .filter(Files::isRegularFile)
          .filter(p -> p.getFileName().toString().endsWith(".java"))
          .toList();
    } catch (Exception e) {
      LOG.debug("Maven: could not list sources under {}", root, e);
      return List.of();
    }
  }
}
