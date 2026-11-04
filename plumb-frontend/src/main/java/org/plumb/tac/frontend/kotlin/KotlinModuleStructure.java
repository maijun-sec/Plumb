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
package org.plumb.tac.frontend.kotlin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaLibraryModuleBuilderKt;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaModuleContainerBuilder;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaSdkModuleBuilderKt;
import org.jetbrains.kotlin.analysis.project.structure.builder.KaSourceModuleBuilderKt;
import org.jetbrains.kotlin.analysis.project.structure.builder.KtLibraryModuleBuilder;
import org.jetbrains.kotlin.analysis.project.structure.builder.KtSdkModuleBuilder;
import org.jetbrains.kotlin.analysis.project.structure.builder.KtSourceModuleBuilder;
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms;

/**
 * Builds the module structure the Analysis API session resolves against.
 *
 * <p><b>Why the structure has to be this shape.</b> The Analysis API is not a compiler:
 * it resolves a symbol by looking it up in the <i>modules</i> the session was built
 * with. A module that is not in the container is invisible; a module that is in the
 * container but is not a dependency of the module being analysed contributes its files
 * to the index but not to the analysed module's scope. So the structure has three parts:
 *
 * <ul>
 *   <li>An <b>SDK module</b> for the JDK, built with {@code
 *       addBinaryRootsFromJdkHome}. The JDK's own classes live in its runtime image
 *       ({@code lib/modules}), which the session's virtual file system mounts through
 *       this call and through nothing else.
 *   <li>A <b>library module</b> for the Kotlin standard library, and for the analysis
 *       classpath the caller supplied.
 *   <li>A <b>source module</b> per source root, which depends on both of the above --
 *       as a <i>regular</i> dependency and as a <i>depends-on</i> dependency. The regular
 *       dependency is what puts the libraries in the analysed module's scope; the
 *       depends-on dependency is what makes them visible to the module's own
 *       dependencies.
 * </ul>
 *
 * <p><b>What happens without this.</b> Every reference to a JDK class or a stdlib class
 * resolves to an <i>error type</i>, and every value of such a type is unknown. That is
 * not a cosmetic difference: a Java method seen from Kotlin is a <i>platform</i> type,
 * and the platform type's nullability is exactly what the Kotlin frontend exists to
 * report. With the libraries missing, the frontend would report every Java-interop value
 * as unknown, and the null-dereference checker would lose the suppression it gets from a
 * Kotlin non-null declaration.
 *
 * <h2>Why the source module's content scope covers the whole root</h2>
 *
 * <p>The Analysis API builds a source module's content scope from the files that exist
 * under its source roots <i>at the moment the module is built</i> -- which is before the
 * frontend has staged anything. A scope built that way is a frozen list of files, and a
 * file staged afterwards is <b>outside the scope</b>: the Analysis API hands it to a
 * "not under content root" module, which has no dependencies, so every reference in the
 * file becomes an error type -- even a JDK class such as {@code StringBuilder} fails to
 * resolve.
 *
 * <p>The scope given to the module is therefore a {@link KotlinDirectoryScope}: it
 * answers "is this file under the root?" on every query, so it does not matter when the
 * frontend staged the file.
 */
final class KotlinModuleStructure {

  /** The source roots the session will see. */
  private final List<Path> sourceRoots;

  /** The classpath jars the session will resolve against. */
  private final List<Path> classpath;

  /** The problems found while building the structure. */
  private final List<String> problems = new ArrayList<>();

  /**
   * @param sourceRoots the roots whose Kotlin files the session will see
   * @param classpath the jars and class directories the session will resolve against
   */
  KotlinModuleStructure(List<Path> sourceRoots, List<Path> classpath) {
    this.sourceRoots = sourceRoots == null ? List.of() : List.copyOf(sourceRoots);
    this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
  }

  /** The problems found while building the structure. */
  List<String> problems() {
    return List.copyOf(problems);
  }

  /**
   * Adds the modules to a module container.
   *
   * @param container the container to add to
   */
  void addTo(KaModuleContainerBuilder container) {
    container.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());

    // The JDK, as an SDK module.
    KaModule sdk = addSdkModule(container);
    // The libraries: the Kotlin standard library and the analysis classpath.
    KaModule libraries = addLibraryModule(container);

    int index = 0;
    for (Path root : sourceRoots) {
      final String moduleName = "kotlin-module-" + index++;
      container.addModule(
          KaSourceModuleBuilderKt.buildKtSourceModule(
              container,
              new Function1<KtSourceModuleBuilder, Unit>() {
                @Override
                public Unit invoke(KtSourceModuleBuilder module) {
                  module.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());
                  module.setModuleName(moduleName);
                  module.addSourceRoot(root);
                  // The scope covers the whole root, not the files that exist at this
                  // moment, so a file staged after the session was built is in scope.
                  // See KotlinDirectoryScope.
                  module.setContentScope(directoryScope(container, root));
                  if (sdk != null) {
                    module.addRegularDependency(sdk);
                    module.addDependsOnDependency(sdk);
                  }
                  if (libraries != null) {
                    module.addRegularDependency(libraries);
                    module.addDependsOnDependency(libraries);
                  }
                  return Unit.INSTANCE;
                }
              }));
    }
  }

  /**
   * A content scope that covers a source root's whole tree.
   *
   * @param container the container the module is being added to
   * @param root the source root the scope covers
   * @return the scope
   */
  private static com.intellij.psi.search.GlobalSearchScope directoryScope(
      KaModuleContainerBuilder container, Path root) {
    com.intellij.openapi.vfs.VirtualFile directory = null;
    if (root != null) {
      try {
        com.intellij.openapi.vfs.VirtualFileManager vfs =
            com.intellij.openapi.vfs.VirtualFileManager.getInstance();
        try {
          vfs.refreshWithoutFileWatcher(false);
        } catch (RuntimeException ex) {
          // The refresh is best-effort: the directory may already be visible.
        }
        directory = vfs.findFileByNioPath(root);
      } catch (RuntimeException ex) {
        // A source root the virtual file system cannot see contributes no files.
      }
    }
    return new KotlinDirectoryScope(container.getProject(), directory);
  }

  /** Adds the JDK as an SDK module, or returns {@code null} when the JDK is not found. */
  private KaModule addSdkModule(KaModuleContainerBuilder container) {
    Path jdkHome = jdkHome();
    if (jdkHome == null) {
      problems.add("no JDK found; JDK classes will not resolve");
      return null;
    }
    try {
      KaModule sdk =
          KaSdkModuleBuilderKt.buildKtSdkModule(
              container,
              new Function1<KtSdkModuleBuilder, Unit>() {
                @Override
                public Unit invoke(KtSdkModuleBuilder module) {
                  module.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());
                  module.setLibraryName("jdk");
                  module.addBinaryRootsFromJdkHome(jdkHome, false);
                  return Unit.INSTANCE;
                }
              });
      container.addModule(sdk);
      return sdk;
    } catch (RuntimeException | LinkageError ex) {
      problems.add(
          "could not add the JDK to the Kotlin Analysis API session: "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      return null;
    }
  }

  /** Adds the libraries as a library module, or returns {@code null} when there are none. */
  private KaModule addLibraryModule(KaModuleContainerBuilder container) {
    List<Path> roots = libraryRoots();
    if (roots.isEmpty()) {
      problems.add(
          "no Kotlin standard library found; stdlib classes will not resolve, and every"
              + " value of a stdlib type will be unknown");
      return null;
    }
    try {
      KaModule libraries =
          KaLibraryModuleBuilderKt.buildKtLibraryModule(
              container,
              new Function1<KtLibraryModuleBuilder, Unit>() {
                @Override
                public Unit invoke(KtLibraryModuleBuilder module) {
                  module.setPlatform(JvmPlatforms.INSTANCE.getDefaultJvmPlatform());
                  module.setLibraryName("libraries");
                  module.addBinaryRoots(roots);
                  return Unit.INSTANCE;
                }
              });
      container.addModule(libraries);
      return libraries;
    } catch (RuntimeException | LinkageError ex) {
      problems.add(
          "could not add the libraries to the Kotlin Analysis API session: "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
      return null;
    }
  }

  /**
   * The library roots the session resolves against.
   *
   * <p>The Kotlin standard library, plus the analysis classpath the caller supplied.
   *
   * <p>The standard library is found on the running JVM's classpath rather than being
   * assumed to be at a fixed location: the Analysis API brings its own copy of the
   * standard library (as a transitive dependency), and that is the one whose classes
   * agree with the Analysis API's own built-ins.
   */
  private List<Path> libraryRoots() {
    List<Path> roots = new ArrayList<>();
    for (Path p : classpath) {
      if (p != null && Files.exists(p) && !roots.contains(p)) {
        roots.add(p);
      }
    }
    for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
      if (!entry.endsWith("kotlin-stdlib.jar") && !entry.contains("kotlin-stdlib-")) {
        continue;
      }
      Path path = Path.of(entry);
      if (Files.exists(path) && !roots.contains(path)) {
        roots.add(path);
      }
    }
    return roots;
  }

  /**
   * The JDK's home directory, or {@code null} when it cannot be found.
   *
   * <p>{@code java.home} points at the JDK when the tool runs on a JDK, and at the JRE
   * inside a JDK otherwise. The SDK builder wants the JDK's home.
   */
  static Path jdkHome() {
    String home = System.getProperty("java.home");
    if (home == null) {
      return null;
    }
    Path path = Path.of(home);
    if (!Files.exists(path)) {
      return null;
    }
    // A JRE inside a JDK: the SDK builder wants the JDK, not the JRE.
    Path parent = path.getParent();
    if (parent != null && Files.exists(parent.resolve("lib").resolve("modules"))) {
      return parent;
    }
    return path;
  }
}
