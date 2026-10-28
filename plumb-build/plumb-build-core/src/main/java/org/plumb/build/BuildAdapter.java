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
package org.plumb.build;

import java.nio.file.Path;
import java.util.List;

/**
 * The contract every build-system adapter implements.
 *
 * <p>An adapter is responsible for ONE build system (Maven, Gradle, Bazel, Ant, ...). The user
 * tells Plumb which adapter to use (or Plumb auto-detects via {@link BuildSystemRegistry}). The
 * adapter then walks the project root and produces a uniform {@link BuildProject}.
 *
 * <h2>Implementation contract</h2>
 *
 * <ul>
 *   <li>{@link #systemName()} -- short id like {@code "maven"} / {@code "gradle"}. Used in CLI flag
 *       values and in log lines.
 *   <li>{@link #probe(Path)} -- fast check: does this project root look like it belongs to this
 *       adapter? Should be cheap (stat one or two files) and never throw. {@code true} if the probe
 *       finds the expected marker file (e.g. {@code pom.xml} for Maven, {@code build.gradle} for
 *       Gradle).
 *   <li>{@link #discover(Path)} -- produce the {@link BuildProject}. May throw {@link
 *       BuildAdapterException} on parse errors; the CLI surfaces the message and exits non-zero.
 * </ul>
 *
 * <h2>Thread-safety</h2>
 *
 * <p>Adapters should be safe to share across threads (the CLI only invokes {@link #discover(Path)}
 * once per scan, so this is mostly defensive). Adapters must NOT cache filesystem paths internally
 * without being told to.
 */
public interface BuildAdapter {

  /** Short id, e.g. {@code "maven"}. Lower-case, no spaces. */
  String systemName();

  /** Cheap check: is {@code projectRoot} a project for this build system? */
  boolean probe(Path projectRoot);

  /** Resolve {@code projectRoot} into a {@link BuildProject}. */
  BuildProject discover(Path projectRoot) throws BuildAdapterException;

  /**
   * Optional: list of source file extensions to consider. Defaults to {@code [".java"]} for JVM
   * adapters.
   */
  default List<String> sourceExtensions() {
    return List.of(".java");
  }
}
