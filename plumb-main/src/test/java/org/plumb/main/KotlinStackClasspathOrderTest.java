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

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The two Kotlin stacks in this JVM must not steal each other's classes.
 *
 * <p>The repository runs the Kotlin DSL rule engine on the Kotlin 2.2.0 script engine and the
 * Kotlin source frontend on the Analysis API 2.5.0-Beta2. Both bundle a Kotlin compiler, and they
 * disagree about the INTELLIJ SHADING: the script engine uses the shaded {@code
 * org.jetbrains.kotlin.com.intellij}, the Analysis API uses the unshaded {@code com.intellij}. The
 * two can therefore never share a classpath.
 *
 * <p>The script engine is isolated in its own classloader (see {@code ScriptEngineClassLoader} in
 * plumb-dsl-kotlin). The other half of that isolation is the APPLICATION classpath order: the
 * Analysis API runs on the application loader, so the Analysis API's compiler must come first
 * there.
 *
 * <p>Without that order the frontend silently stops working: the session builder loads {@code
 * org.jetbrains.kotlin.cli.jvm.compiler.CompatKt} from the 2.2.0 compiler, the 2.5.0-Beta2 analysis
 * API calls a 2.5.0 method on it, and the scan reports "problems" instead of findings. A Kotlin
 * codebase would look clean.
 */
class KotlinStackClasspathOrderTest {

  /** A compiler class the Analysis API's session construction needs. */
  private static final String ANALYSIS_API_COMPILER_CLASS =
      "org.jetbrains.kotlin.cli.jvm.compiler.CompatKt";

  /** The Analysis API's jar: it bundles the 2.5.0-Beta2 compiler classes. */
  private static final String ANALYSIS_API_JAR = "kotlin-analysis-api-implementation";

  /** The script engine's compiler jar: it bundles the 2.2.0 compiler classes. */
  private static final String SCRIPT_ENGINE_JAR = "kotlin-compiler-embeddable";

  @Test
  void theAnalysisApiCompilerComesBeforeTheScriptEngineCompilerOnTheClasspath() {
    List<URL> classpath = applicationClasspath();

    int analysisApi =
        indexOfJarContaining(classpath, ANALYSIS_API_COMPILER_CLASS, ANALYSIS_API_JAR);
    int scriptEngine =
        indexOfJarContaining(classpath, ANALYSIS_API_COMPILER_CLASS, SCRIPT_ENGINE_JAR);

    assertThat(analysisApi)
        .as(
            "the Analysis API's compiler must be on the classpath at all; "
                + "without it the Kotlin frontend cannot build a session")
        .isGreaterThanOrEqualTo(0);
    assertThat(scriptEngine)
        .as("the script engine's compiler must be on the classpath at all")
        .isGreaterThanOrEqualTo(0);
    assertThat(analysisApi)
        .as(
            "the Analysis API's compiler must come FIRST on the classpath; "
                + "otherwise the frontend binds against the script engine's 2.2.0 "
                + "compiler and every Kotlin file is reported as a problem instead "
                + "of being analysed")
        .isLessThan(scriptEngine);
  }

  @Test
  void theApplicationLoaderResolvesTheCompilerFromTheAnalysisApiJar() {
    // The order above is necessary but not sufficient: what matters is the class
    // the APPLICATION loader resolves, because that is the loader the Analysis API
    // runs in. Asserting it directly turns a classpath-order regression into a
    // test failure instead of a silent loss of Kotlin coverage.
    String resource = ANALYSIS_API_COMPILER_CLASS.replace('.', '/').concat(".class");
    URL found = ApplicationLoader.class.getClassLoader().getResource(resource);
    assertThat(found).as("the Analysis API's compiler class must be resolvable").isNotNull();
    assertThat(found.toString())
        .as(
            "the application loader must resolve the compiler class from the "
                + "Analysis API's jar, not from kotlin-compiler-embeddable")
        .doesNotContain(SCRIPT_ENGINE_JAR);
  }

  /** A holder whose class loader is the application loader. */
  private static final class ApplicationLoader {
    static {
      // Nothing to initialise; the class exists only to carry the loader.
    }
  }

  /** The application classpath, as URLs. */
  private static List<URL> applicationClasspath() {
    List<URL> result = new ArrayList<>();
    String property = System.getProperty("java.class.path");
    if (property == null || property.isBlank()) {
      return result;
    }
    for (String entry : property.split(File.pathSeparator)) {
      if (entry.isBlank()) {
        continue;
      }
      try {
        result.add(new File(entry).toURI().toURL());
      } catch (Exception ignored) {
        // A classpath entry that is not a file contributes nothing to this check.
      }
    }
    return result;
  }

  /** The index of the first classpath entry that is the named jar AND contains the given class. */
  private static int indexOfJarContaining(List<URL> classpath, String className, String jarName) {
    String resource = className.replace('.', '/').concat(".class");
    for (int i = 0; i < classpath.size(); i++) {
      URL url = classpath.get(i);
      String name = url.getPath();
      if (!name.endsWith(".jar") || !name.contains(jarName)) {
        continue;
      }
      File file;
      try {
        file = new File(url.toURI());
      } catch (Exception ex) {
        continue;
      }
      if (file.isFile() && containsEntry(file, resource)) {
        return i;
      }
    }
    return -1;
  }

  /** True when the jar contains the given entry. */
  private static boolean containsEntry(File jar, String entryName) {
    try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar)) {
      java.util.Enumeration<java.util.jar.JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        if (entries.nextElement().getName().equals(entryName)) {
          return true;
        }
      }
      return false;
    } catch (Exception ex) {
      return false;
    }
  }
}
