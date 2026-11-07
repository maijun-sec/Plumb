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
package org.plumb.build.capture;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes the captured compiles as a <b>compilation database</b>.
 *
 * <p><b>What this format is.</b> A compilation database is the interchange format between a build
 * and an analyser: it says, for every compilation unit, <em>the exact command that compiled
 * it</em>. The point is that the analyser does not have to guess how the project is built -- the
 * build itself said so, at the moment it ran.
 *
 * <p><b>The shape.</b> A JSON <b>array</b> of objects, each with exactly three keys in this order:
 * {@code directory}, {@code arguments}, {@code file}.
 *
 * <ul>
 *   <li>{@code directory} -- the absolute working directory of the compile, {@code /}-separated;
 *   <li>{@code arguments} -- the argv, one token per element, never joined into a string;
 *   <li>{@code file} -- the absolute path of the compilation unit, {@code /}-separated.
 * </ul>
 *
 * <p><b>Why {@code arguments} and not {@code command}.</b> The format allows both. {@code
 * arguments} is an array of tokens, so no shell quoting has to survive a round trip; {@code
 * command} is a joined string, and every consumer has to re-parse the quoting rules. The joined
 * form is therefore not written -- it is the lossy one.
 *
 * <p><b>Why the paths are normalized but the arguments are not.</b> {@code directory} and {@code
 * file} are consumed by tools that resolve them against the entry's own directory, so they have to
 * be absolute and unambiguous: {@code \} becomes {@code /}, {@code .} and {@code ..} are collapsed.
 * The tokens inside {@code arguments} are reproduced <b>verbatim</b>, because the compiler itself
 * received them that way and a consumer that rewrites them is no longer describing the compile that
 * happened. A path inside {@code arguments} therefore keeps its platform separators.
 *
 * <p><b>One entry per compilation unit.</b> A compile may name several source files. The format has
 * one {@code file} per entry, so a multi-source compile becomes several entries -- but they would
 * carry the same {@code arguments}, which would name the wrong unit. The first source file is the
 * one that is emitted, and the rest are dropped; this is the same choice the format's own reference
 * implementation makes, and it is the right one because a consumer reads {@code file} as "the unit
 * this entry is about".
 *
 * <p><b>No JSON dependency.</b> The writer is hand-rolled, like {@link CaptureEntry#toJson()}, for
 * the same reason: this module runs inside an arbitrary compiler's JVM and must not drag a
 * dependency into it.
 */
public final class CompdbWriter {

  private CompdbWriter() {}

  /**
   * Write the captures as a compilation database.
   *
   * @param captures the captures to write
   * @return the database's JSON text
   */
  public static String write(List<CaptureEntry> captures) {
    List<CaptureEntry> entries = captures == null ? List.of() : captures;
    StringBuilder sb = new StringBuilder();
    sb.append("[\n");
    boolean first = true;
    for (CaptureEntry entry : entries) {
      if (entry == null) continue;
      // A capture with no source file describes no compilation unit, so it has
      // no entry. Emitting one would give the consumer a unit it cannot open.
      if (entry.sourceFiles() == null || entry.sourceFiles().isEmpty()) {
        continue;
      }
      if (!first) {
        sb.append(",\n");
      }
      first = false;
      appendEntry(sb, entry);
    }
    if (!first) {
      sb.append("\n");
    }
    sb.append("]\n");
    return sb.toString();
  }

  private static void appendEntry(StringBuilder sb, CaptureEntry entry) {
    String directory = normalizeDirectory(entry);

    // The argv: the compiler followed by its arguments. The capture records
    // the raw argv it was given; when there is none (the API path can produce
    // an empty argv), the compiler name stands in for it.
    List<String> arguments = new ArrayList<>();
    if (entry.argv() != null && !entry.argv().isEmpty()) {
      arguments.addAll(entry.argv());
    } else {
      arguments.add(entry.compiler());
    }

    sb.append("  {\n");
    sb.append("    \"directory\": ").append(quote(directory)).append(",\n");
    sb.append("    \"arguments\": [");
    for (int i = 0; i < arguments.size(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(quote(arguments.get(i)));
    }
    sb.append("],\n");
    sb.append("    \"file\": ").append(quote(fileOf(entry, directory))).append("\n");
    sb.append("  }");
  }

  /**
   * The compile's working directory, normalized.
   *
   * <p>The capture records {@code user.dir}. When it is absent the current directory is the only
   * sensible answer, and a relative {@code file} would then be unresolvable.
   */
  private static String normalizeDirectory(CaptureEntry entry) {
    String dir = entry.workingDirectory();
    if (dir == null || dir.isBlank()) {
      dir = System.getProperty("user.dir", ".");
    }
    return normalizePath(dir);
  }

  /**
   * The compilation unit's absolute path, normalized.
   *
   * <p>A source path that is relative is resolved against the compile's directory, because that is
   * the directory the compiler was running in.
   */
  private static String fileOf(CaptureEntry entry, String directory) {
    String file = entry.sourceFiles().get(0).toString();
    if (isAbsolute(file)) {
      return normalizePath(file);
    }
    if (directory.isEmpty()) {
      return normalizePath(file);
    }
    return normalizePath(directory + "/" + file);
  }

  /**
   * Whether a path is absolute.
   *
   * <p>Two shapes count: a rooted path that starts with a separator, and a Windows path that starts
   * with a drive letter and a separator.
   */
  static boolean isAbsolute(String path) {
    if (path == null || path.isEmpty()) {
      return false;
    }
    if (path.charAt(0) == '/' || path.charAt(0) == '\\') {
      return true;
    }
    return path.length() >= 3
        && isAsciiLetter(path.charAt(0))
        && path.charAt(1) == ':'
        && (path.charAt(2) == '/' || path.charAt(2) == '\\');
  }

  private static boolean isAsciiLetter(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
  }

  /**
   * Normalize a path: {@code \} becomes {@code /}, and {@code .} and {@code ..} are collapsed.
   *
   * <p><b>The drive letter is kept.</b> A Windows path's drive prefix is part of its identity, so
   * {@code C:/work/../work/src} becomes {@code C:/work/src} rather than losing the drive. The case
   * of the drive letter and of every segment is left exactly as it was: a path's case can be
   * meaningful to the filesystem it names, and lower-casing it here would change which file the
   * consumer opens.
   *
   * <p><b>{@code ..} above an absolute root is dropped.</b> A rooted path has nowhere above its
   * root to go, so the segment is discarded rather than making the path relative again. A relative
   * path keeps its {@code ..} segments, because they are meaningful there.
   *
   * @param path the path to normalize
   * @return the normalized path
   */
  public static String normalizePath(String path) {
    if (path == null || path.isEmpty()) {
      return path == null ? "" : path;
    }

    String s = path.replace('\\', '/');

    // The Windows drive prefix, kept apart from the segments.
    String prefix = "";
    int start = 0;
    if (s.length() >= 2 && isAsciiLetter(s.charAt(0)) && s.charAt(1) == ':') {
      prefix = s.substring(0, 2);
      start = 2;
    }

    boolean absolute = start < s.length() && s.charAt(start) == '/';
    if (absolute && !prefix.isEmpty()) {
      prefix = prefix + "/";
      start++;
    }

    List<String> parts = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    for (int i = start; i <= s.length(); i++) {
      if (i == s.length() || s.charAt(i) == '/') {
        String segment = current.toString();
        current.setLength(0);
        if (segment.isEmpty() || segment.equals(".")) {
          // Nothing to add: an empty segment is a repeated separator, and "." is
          // the current directory.
        } else if (segment.equals("..")) {
          if (!parts.isEmpty() && !parts.get(parts.size() - 1).equals("..")) {
            parts.remove(parts.size() - 1);
          } else if (!absolute) {
            parts.add("..");
          }
          // Above an absolute root there is nowhere to go, so the segment is
          // dropped.
        } else {
          parts.add(segment);
        }
      } else {
        current.append(s.charAt(i));
      }
    }

    StringBuilder out = new StringBuilder(prefix);
    if (absolute && (out.isEmpty() || out.charAt(out.length() - 1) != '/')) {
      out.append('/');
    }
    for (int i = 0; i < parts.size(); i++) {
      if (i > 0) {
        out.append('/');
      }
      out.append(parts.get(i));
    }
    if (out.isEmpty()) {
      return absolute ? "/" : ".";
    }
    return out.toString();
  }

  /**
   * Quote a string as a JSON string.
   *
   * <p>UTF-8 bytes are emitted as they are, which JSON allows. The control characters below 0x20
   * are escaped, and the two that have a short form use it.
   */
  static String quote(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    return sb.append('"').toString();
  }
}
