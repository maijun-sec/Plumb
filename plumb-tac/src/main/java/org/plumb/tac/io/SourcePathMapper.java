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
package org.plumb.tac.io;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the source paths a store carries, so that a store produced on one machine names files
 * that exist on another.
 *
 * <p><b>Why this is needed.</b> The IR carries the absolute path of every file it came from,
 * because a finding has to point at a file and a relative path would depend on where the reader
 * happens to be. But that path is the PRODUCER's path: a store built at {@code D:\proj} says {@code
 * D:\proj\src\Main.java}, and on a machine where the project lives at {@code /srv/proj} that path
 * names nothing.
 *
 * <p><b>Why the rewrite is a separate step and not baked into the IR.</b> The path in the IR is
 * also the store's identity: it is what the cache key is derived from, and it is what says which
 * file a function came from. Rewriting it in the IR would make two different stores look the same
 * and would make the identity of the store depend on where it is read. So the IR keeps the
 * producer's path, and the rewrite happens at LOAD time -- on the way into the analysis, where the
 * path is only used to label findings.
 *
 * <p><b>Why a root substitution and not a per-file mapping.</b> A whole project moves as a unit. A
 * root substitution says "everything under this directory is now under that one", which is one fact
 * the user knows, rather than one fact per file.
 */
public final class SourcePathMapper {

  private final List<Mapping> mappings;

  /**
   * One root substitution.
   *
   * @param producerRoot the root the producer used
   * @param consumerRoot the root the consumer uses
   */
  public record Mapping(Path producerRoot, Path consumerRoot) {

    /**
     * The path with the producer's root replaced by the consumer's.
     *
     * @param path the path to rewrite
     * @return the rewritten path, or the path itself when it is not under the root
     */
    public Path apply(Path path) {
      if (path == null || producerRoot == null || consumerRoot == null) {
        return path;
      }
      Path normalized = path.toAbsolutePath().normalize();
      Path root = producerRoot.toAbsolutePath().normalize();
      if (!normalized.startsWith(root)) {
        return path;
      }
      Path relative = root.relativize(normalized);
      return consumerRoot.toAbsolutePath().normalize().resolve(relative);
    }
  }

  /**
   * A mapper that rewrites nothing.
   *
   * @return the mapper
   */
  public static SourcePathMapper identity() {
    return new SourcePathMapper(List.of());
  }

  /**
   * A mapper with the given substitutions.
   *
   * @param mappings the substitutions
   * @return the mapper
   */
  public static SourcePathMapper of(List<Mapping> mappings) {
    return new SourcePathMapper(mappings == null ? List.of() : List.copyOf(mappings));
  }

  /**
   * A mapper with one substitution.
   *
   * @param producerRoot the root the producer used
   * @param consumerRoot the root the consumer uses
   * @return the mapper
   */
  public static SourcePathMapper of(Path producerRoot, Path consumerRoot) {
    return new SourcePathMapper(List.of(new Mapping(producerRoot, consumerRoot)));
  }

  private SourcePathMapper(List<Mapping> mappings) {
    this.mappings = mappings;
  }

  /**
   * The rewritten path.
   *
   * <p>The first substitution that covers the path wins. A path that no substitution covers is
   * returned unchanged: it belongs to something outside the project, and guessing where it lives on
   * the consumer's machine would be worse than saying "this one I do not know".
   *
   * @param path the path
   * @return the rewritten path
   */
  public Path map(Path path) {
    if (path == null || mappings.isEmpty()) {
      return path;
    }
    for (Mapping mapping : mappings) {
      Path mapped = mapping.apply(path);
      if (mapped != null && !mapped.equals(path)) {
        return mapped;
      }
    }
    return path;
  }

  /**
   * The rewritten path, as a string.
   *
   * @param path the path
   * @return the rewritten path
   */
  public String map(String path) {
    if (path == null || path.isBlank()) {
      return path;
    }
    try {
      return map(Path.of(path)).toString();
    } catch (RuntimeException e) {
      return path;
    }
  }

  /**
   * The substitutions this mapper holds.
   *
   * @return the substitutions
   */
  public List<Mapping> mappings() {
    return mappings;
  }

  /**
   * Parse a substitution of the form {@code producerRoot=consumerRoot}.
   *
   * <p>The two roots are separated by the FIRST {@code =}, so a Windows path's own drive colon is
   * not mistaken for the separator.
   *
   * <p><b>Why the split happens before the paths are built.</b> The split is on the TEXT, and the
   * text is what the user typed. If the paths were built first, a root that the platform considers
   * illegal would fail before the split was even attempted -- and the user would be told the path
   * is wrong rather than being told where the separator was. The split is the syntax; the paths are
   * the payload.
   *
   * @param text the substitution
   * @return the parsed substitution
   * @throws IllegalArgumentException when the text does not have the expected shape, or when a root
   *     is not a path this platform can name
   */
  public static Mapping parseMapping(String text) {
    if (text == null) {
      throw new IllegalArgumentException("a path mapping cannot be null");
    }
    int eq = text.indexOf('=');
    if (eq <= 0 || eq == text.length() - 1) {
      throw new IllegalArgumentException(
          "a path mapping is producerRoot=consumerRoot, but got: " + text);
    }
    String producer = text.substring(0, eq);
    String consumer = text.substring(eq + 1);
    try {
      return new Mapping(Path.of(producer), Path.of(consumer));
    } catch (RuntimeException e) {
      throw new IllegalArgumentException(
          "a path mapping's roots must be paths this platform can name, but got: " + text, e);
    }
  }

  /**
   * Parse a list of substitutions.
   *
   * @param texts the substitutions
   * @return the parsed substitutions
   */
  public static List<Mapping> parseMappings(List<String> texts) {
    List<Mapping> out = new ArrayList<>();
    if (texts != null) {
      for (String text : texts) {
        if (text != null && !text.isBlank()) {
          out.add(parseMapping(text));
        }
      }
    }
    return out;
  }
}
