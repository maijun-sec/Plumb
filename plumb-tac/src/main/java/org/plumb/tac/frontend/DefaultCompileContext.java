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
package org.plumb.tac.frontend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A default in-memory {@link CompileContext}.
 *
 * <p>Good enough for the single-process, single-run analysis Plumb does today. The symbol table is
 * a {@link ConcurrentHashMap} so that a first pass over several modules can register symbols in
 * parallel.
 */
public final class DefaultCompileContext implements CompileContext {

  private final List<Module> modules;
  private final List<Path> classpath;
  private final DefaultSymbolTable symbols = new DefaultSymbolTable();
  private final Map<String, Object> cache = new ConcurrentHashMap<>();
  private final boolean ownsClasspath;

  /** A context over the given modules, with no extra classpath. */
  public DefaultCompileContext(List<Module> modules) {
    this(modules, List.of());
  }

  /**
   * A context over the given modules and classpath.
   *
   * @param ownsClasspath when true, {@link #close()} releases the classpath's file handles. Set it
   *     when the context opened the classpath itself; leave it false when the classpath is owned by
   *     the caller.
   */
  public DefaultCompileContext(List<Module> modules, List<Path> classpath) {
    this(modules, classpath, false);
  }

  public DefaultCompileContext(List<Module> modules, List<Path> classpath, boolean ownsClasspath) {
    this.modules = modules == null ? List.of() : List.copyOf(modules);
    this.classpath = classpath == null ? List.of() : List.copyOf(classpath);
    this.ownsClasspath = ownsClasspath;
  }

  @Override
  public List<Module> modules() {
    return modules;
  }

  @Override
  public List<Path> classpath() {
    return classpath;
  }

  @Override
  public SymbolTable symbols() {
    return symbols;
  }

  @Override
  public <T> T computeIfAbsent(String key, java.util.function.Function<String, T> factory) {
    @SuppressWarnings("unchecked")
    T v = (T) cache.computeIfAbsent(key, k -> factory.apply(k));
    return v;
  }

  /**
   * The module a source file belongs to: the one whose source root contains it.
   *
   * <p>The LONGEST matching root wins, so a nested module (a module inside another module's source
   * tree) is attributed to itself rather than to its parent.
   *
   * <p>{@code null} when the file is under no module's root, or when there is no module at all.
   * That is the single-file case, and it means "no module boundary" rather than "outside every
   * module".
   */
  @Override
  public String moduleOf(Path file) {
    if (file == null || modules.isEmpty()) return null;
    Path absolute = file.toAbsolutePath().normalize();
    Module best = null;
    int bestDepth = -1;
    for (Module m : modules) {
      Path root = m.sourceRoot();
      if (root == null) continue;
      Path absoluteRoot = root.toAbsolutePath().normalize();
      if (!absolute.startsWith(absoluteRoot)) continue;
      int depth = absoluteRoot.getNameCount();
      if (depth > bestDepth) {
        bestDepth = depth;
        best = m;
      }
    }
    return best == null ? null : best.name();
  }

  @Override
  public void close() {
    cache.clear();
    // The classpath may hold open file handles when the frontend opened it; the frontend that
    // opened it is responsible for closing it, so this hook is intentionally a no-op for now.
  }

  /** Whether {@link #close()} should release the classpath. */
  public boolean ownsClasspath() {
    return ownsClasspath;
  }

  /** The default in-memory {@link SymbolTable}. */
  public static final class DefaultSymbolTable implements SymbolTable {

    /** owner + name + arity -> signature. */
    private final Map<String, String> byFullKey = new ConcurrentHashMap<>();

    /** name + arity -> signatures (may hold more than one when the owner differs). */
    private final Map<String, List<String>> byName = new ConcurrentHashMap<>();

    private static String fullKey(String ownerType, String methodName, int arity) {
      return (ownerType == null ? "" : ownerType) + "#" + methodName + "/" + arity;
    }

    private static String nameKey(String methodName, int arity) {
      return methodName + "/" + arity;
    }

    @Override
    public void register(String ownerType, String methodName, int arity, String signature) {
      if (methodName == null || signature == null) return;
      byFullKey.put(fullKey(ownerType, methodName, arity), signature);
      byName
          .computeIfAbsent(
              nameKey(methodName, arity), k -> Collections.synchronizedList(new ArrayList<>()))
          .add(signature);
    }

    @Override
    public Optional<String> lookup(String ownerType, String methodName, int arity) {
      String hit = byFullKey.get(fullKey(ownerType, methodName, arity));
      if (hit != null) return Optional.of(hit);
      // The owner may be recorded under a different spelling (a nested type's binary name vs its
      // source name). Fall back to the by-name index only when it yields exactly one candidate;
      // an ambiguous fallback would produce a wrong call-graph edge.
      List<String> candidates = byName.get(nameKey(methodName, arity));
      if (candidates != null && candidates.size() == 1) return Optional.of(candidates.get(0));
      return Optional.empty();
    }

    @Override
    public List<String> lookupByName(String methodName, int arity) {
      List<String> candidates = byName.get(nameKey(methodName, arity));
      if (candidates == null) return List.of();
      return List.copyOf(candidates);
    }
  }

  /** A minimal {@link SymbolTable} that records nothing, for tests and for single-file use. */
  public static final SymbolTable EMPTY_SYMBOLS =
      new SymbolTable() {
        @Override
        public void register(String ownerType, String methodName, int arity, String signature) {}

        @Override
        public Optional<String> lookup(String ownerType, String methodName, int arity) {
          return Optional.empty();
        }

        @Override
        public List<String> lookupByName(String methodName, int arity) {
          return List.of();
        }
      };

  /** A {@link CompileContext} with no modules, for tests. */
  public static CompileContext empty() {
    return new DefaultCompileContext(List.of());
  }
}
