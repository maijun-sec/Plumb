/*
 * Shared source-file reader for DFA-style checkers
 * that need AST-level data TAC IR doesn't carry.
 *
 * <p>The TAC IR strips several kinds of source-level
 * information that real SAST rules care about:</p>
 *
 * <ul>
 *   <li>Comments (CWE-546 suspicious comment, CWE-615
 *       info exposure by comment, CWE-598 HTML form
 *       method=get next to password fields)</li>
 *   <li>{@code assert} statements (CWE-617 reachable
 *       assertion -- {@code assert false;} produces no TAC
 *       because the assertion is dead code in the static
 *       view)</li>
 *   <li>Field / class modifiers (CWE-500 public static
 *       without final, CWE-582 array public final static,
 *       CWE-374 mutable fields)</li>
 *   <li>String literal contents (CWE-259 hard-coded
 *       password, CWE-549 missing password masking,
 *       CWE-329 IV random with CBC mode)</li>
 *   <li>{@code @SuppressWarnings} / {@code @Deprecated} /
 *       {@code @Generated} (FP suppression for
 *       legitimate-use cases)</li>
 * </ul>
 *
 * <p>Previously, every source-reading checker
 * ({@code SuspiciousCommentChecker},
 * {@code HardCodedPasswordSinkChecker}, ...) reimplemented
 * the same boilerplate: lookup {@code TacProgram.sourcePath()},
 * call {@code Files.readAllLines(...)}, wrap in try/catch.
 * This class extracts the shared logic into a single utility with
 * a process-wide cache so multiple checkers reading the
 * same file in the same run only hit disk once.</p>
 *
 * <h2>Usage</h2>
 * <pre>
 *   &#64;Override
 *   public void finish(AnalysisContext ctx) {
 *       SourceReader.readLines(ctx.program()).ifPresent(lines -&gt; {
 *           for (int i = 0; i &lt; lines.size(); i++) {
 *               String line = lines.get(i);
 *               if (line.contains("assert false")) {
 *                   ctx.reporter().report(...);
 *               }
 *           }
 *       });
 *   }
 * </pre>
 *
 * <p>Or use the regex helper:
 * <pre>
 *   SourceReader.findAll(ctx.program(), ASSERT_FALSE, (match, lineNo) -&gt; {
 *       ctx.reporter().report(...);
 *   });
 * </pre>
 *
 * <h2>Cache scope</h2>
 *
 * <p>The cache is keyed on the absolute {@link Path} of
 * the source file and stores the line list. It's a
 * plain {@link java.util.concurrent.ConcurrentHashMap}
 * with no size bound -- a whole source tree is
 * still only a few megabytes of line-lists, well under any
 * realistic heap limit. Real-codebase scans (single
 * file) cost one entry. Tests should call
 * {@link #clearCache()} in {@code &#64;BeforeEach} to
 * avoid cross-test pollution.</p>
 */
package org.plumb.dfa.checkers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.tac.ir.TacProgram;

public final class SourceReader {

  /** Process-wide line-list cache. Keyed by absolute Path. */
  private static final ConcurrentHashMap<Path, List<String>> LINES_CACHE =
      new ConcurrentHashMap<>();

  /** Process-wide full-content cache. Keyed by absolute Path. */
  private static final ConcurrentHashMap<Path, String> STRING_CACHE = new ConcurrentHashMap<>();

  private SourceReader() {
    // Static utility.
  }

  /**
   * Read all lines from the {@code prog}'s source file (UTF-8, with the platform line separator
   * preserved). Cached per absolute path so multiple checkers reading the same file in the same run
   * share work.
   *
   * <p>Returns {@link Optional#empty()} when:
   *
   * <ul>
   *   <li>{@code prog} is {@code null}
   *   <li>{@code prog.sourcePath()} is {@code null} (synthetic programs the translator built
   *       without a real source file)
   *   <li>The path is not a regular file (e.g. it's a directory or doesn't exist on disk)
   *   <li>The read throws {@link IOException}
   * </ul>
   *
   * <p>The returned list is unmodifiable. The cache stores the same instance for the same path, so
   * callers may safely retain the reference.
   */
  public static Optional<List<String>> readLines(TacProgram prog) {
    if (prog == null) return Optional.empty();
    Path p = prog.sourcePath();
    if (p == null || !Files.isRegularFile(p)) return Optional.empty();
    Path abs = p.toAbsolutePath();
    List<String> cached = LINES_CACHE.get(abs);
    if (cached != null) return Optional.of(cached);
    try {
      List<String> lines = List.copyOf(Files.readAllLines(p, StandardCharsets.UTF_8));
      // Race-tolerant: if another thread beat us,
      // prefer its value (the lists are equal).
      List<String> prev = LINES_CACHE.putIfAbsent(abs, lines);
      return Optional.of(prev != null ? prev : lines);
    } catch (IOException ex) {
      return Optional.empty();
    }
  }

  /**
   * Read the entire source file as a single UTF-8 string. Cached per absolute path. Returns {@link
   * Optional#empty()} under the same conditions as {@link #readLines(TacProgram)}.
   */
  public static Optional<String> readAll(TacProgram prog) {
    if (prog == null) return Optional.empty();
    Path p = prog.sourcePath();
    if (p == null || !Files.isRegularFile(p)) return Optional.empty();
    Path abs = p.toAbsolutePath();
    String cached = STRING_CACHE.get(abs);
    if (cached != null) return Optional.of(cached);
    try {
      String content = Files.readString(p, StandardCharsets.UTF_8);
      String prev = STRING_CACHE.putIfAbsent(abs, content);
      return Optional.of(prev != null ? prev : content);
    } catch (IOException ex) {
      return Optional.empty();
    }
  }

  /**
   * Walk each line, calling {@code action} with the 1-based line number and the line text. Skips
   * when the source is unavailable. No-op when the file is empty.
   */
  public static void forEachLine(TacProgram prog, BiConsumer<Integer, String> action) {
    Objects.requireNonNull(action, "action");
    Optional<List<String>> maybeLines = readLines(prog);
    if (maybeLines.isEmpty()) return;
    List<String> lines = maybeLines.get();
    for (int i = 0; i < lines.size(); i++) {
      action.accept(i + 1, lines.get(i));
    }
  }

  /**
   * Find all matches of {@code pattern} in the source and call {@code action} with each match's
   * 1-based line number and the {@link Matcher} (so callers can extract groups). Multiline mode is
   * controlled by {@code pattern} flags.
   *
   * <p>The {@link Matcher} passed in is reused across matches within the same line -- callers that
   * need a stable snapshot should call {@code match.group()} / {@code match.start()} inside the
   * action before returning.
   */
  public static void findAll(
      TacProgram prog, Pattern pattern, BiConsumer<Matcher, Integer> action) {
    Objects.requireNonNull(pattern, "pattern");
    Objects.requireNonNull(action, "action");
    Optional<List<String>> maybeLines = readLines(prog);
    if (maybeLines.isEmpty()) return;
    List<String> lines = maybeLines.get();
    for (int i = 0; i < lines.size(); i++) {
      Matcher m = pattern.matcher(lines.get(i));
      while (m.find()) {
        action.accept(m, i + 1);
      }
    }
  }

  /**
   * Clear both caches. Tests should call this in {@code &#64;BeforeEach} to prevent cross-test
   * pollution when running multiple test classes in the same JVM.
   */
  public static void clearCache() {
    LINES_CACHE.clear();
    STRING_CACHE.clear();
  }

  /**
   * Multi-source reader: returns the contents of every file the engine has registered for this run
   * via {@code AnalysisContext.registerSourcePath}.
   *
   * <p>The returned map is keyed by absolute path and values are the line lists. Files that failed
   * to read are silently skipped -- same failure semantics as {@link #readLines}. This is the right
   * entry point for source-reading DFA checkers that need ALL files in a cross-TU (MergedProgram)
   * run, since the synthetic {@code <merged>} program has a bogus {@code sourcePath} the regular
   * {@link #readLines} path can't resolve.
   *
   * <p>If the context doesn't expose the source paths (older callers / tests), returns an empty
   * map; callers should fall back to {@link #readLines} in that case.
   */
  public static Map<Path, List<String>> readAllFiles(org.plumb.dfa.engine.AnalysisContext ctx) {
    Map<Path, List<String>> out = new LinkedHashMap<>();
    if (ctx == null) return out;
    // Reflection-free: ask the context for its
    // enclosing-class → source path map. The
    // public API is `sourcePathForEnclosingClass`
    // but there's no `allSourcePaths` getter
    // — pre- checkers used the source-path
    // map exclusively for the filePath-filling
    // pass. To avoid widening the public API
    // surface for this single feature, walk the
    // enclosing classes we know about via the
    // CallGraph's functions. Failing that, fall
    // back to prog.sourcePath() if it's a real
    // file.
    org.plumb.tac.ir.TacProgram prog = ctx.program();
    if (prog != null) {
      readLines(prog)
          .ifPresent(
              lines -> {
                Path p = prog.sourcePath();
                if (p != null && Files.isRegularFile(p)) {
                  out.put(p.toAbsolutePath(), lines);
                }
              });
    }
    // If the engine exposed a public allSourcePaths
    // iterator we could use it here. Until then,
    // many source-reading checkers will simply
    // resolve the synthetic <merged> program back
    // to its registered real paths via this
    // get-or-merged-phase prior scan: it walks
    // every function and asks the context for
    // the enclosing-class → source path.
    try {
      java.lang.reflect.Method getterM;
      try {
        getterM = ctx.getClass().getMethod("sourcePathForEnclosingClass", String.class);
      } catch (NoSuchMethodException ex) {
        getterM = null;
      }
      // Walk the program's functions. The
      // MergedProgram case (cross-TU) puts
      // every TU's functions behind `prog
      // .functions()` and registers every
      // enclosing class's real source path
      // via registerSourcePath. The single-
      // file case has a non-Merged program with
      // one enclosing class registered the
      // same way.
      for (org.plumb.tac.ir.TacFunction fn : progFunctions(ctx, prog)) {
        String enclosing = fn.enclosingClass();
        if (enclosing == null) continue;
        if (getterM == null) break;
        Object sp = getterM.invoke(ctx, enclosing);
        if (sp == null) continue;
        Path src = Path.of(sp.toString());
        if (!Files.isRegularFile(src)) continue;
        Path abs = src.toAbsolutePath();
        if (out.containsKey(abs)) continue;
        readLines(absolutePathAsProgram(abs)).ifPresent(lines -> out.put(abs, lines));
      }
    } catch (ReflectiveOperationException ex) {
      // Older AnalysisContext builds: fall
      // through with whatever we collected.
    }
    return out;
  }

  private static TacProgram absolutePathAsProgram(Path abs) {
    return new TacProgram(abs, abs.toString());
  }

  private static List<org.plumb.tac.ir.TacFunction> progFunctions(
      org.plumb.dfa.engine.AnalysisContext ctx, org.plumb.tac.ir.TacProgram prog) {
    // MergedProgram exposes `functions()` via
    // reflection — its concrete type doesn't
    // implement `TacProgram.functions()` but
    // the user-facing API does. If that's not
    // available, fall back to the call graph's
    // entry points (smaller set but the same
    // file paths).
    try {
      java.lang.reflect.Method m = prog.getClass().getMethod("functions");
      Object res = m.invoke(prog);
      if (res instanceof java.util.Collection<?> coll) {
        List<org.plumb.tac.ir.TacFunction> out = new java.util.ArrayList<>();
        for (Object o : coll) {
          if (o instanceof org.plumb.tac.ir.TacFunction tf) {
            out.add(tf);
          }
        }
        return out;
      }
    } catch (ReflectiveOperationException ignore) {
    }
    // Fallback: walk the call graph.
    List<org.plumb.tac.ir.TacFunction> out = new java.util.ArrayList<>();
    if (ctx.callGraph() != null) {
      for (org.plumb.dfa.cg.FunctionNode n : ctx.callGraph().nodes()) {
        // FunctionNode is signature-only —
        // we can't recover the TacFunction
        // from it. Rely on `prog.functions()`
        // for cross-TU; for single-file the
        // `prog` is a regular TacProgram
        // with a `functions()` accessor.
        break;
      }
    }
    return out;
  }

  /**
   * Diagnostic: how many entries are cached right now. Used by the unit tests to confirm the cache
   * is doing its job.
   */
  public static int cacheSize() {
    return LINES_CACHE.size();
  }
}
