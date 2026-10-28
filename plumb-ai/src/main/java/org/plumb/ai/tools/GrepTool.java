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
package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * regex-grep over the source tree. The single most useful "Claude Code-style" tool for the FP
 * reducer and the bug scanner: it works on raw text, no AST / TAC / plumb-dfa involvement, so it
 * survives when the other tools fall over (e.g. the finding's source file failed to parse, or
 * {@code TacProgram} is {@code null}).
 *
 * <p>Arguments:
 *
 * <ul>
 *   <li>{@code pattern} (string, required) -- Java regex (case-sensitive by default).
 *   <li>{@code path} (string, optional) -- file or directory to search. Default = source root.
 *       Recurses into sub-directories when given a directory.
 *   <li>{@code max_results} (integer, optional, default 50) -- hard cap on the number of matching
 *       lines returned. The LLM can raise this if it needs more (cap of 500 to keep tool output
 *       bounded).
 *   <li>{@code ignore_case} (boolean, optional, default false) -- case-insensitive match.
 *   <li>{@code file_glob} (string, optional) -- restrict to files whose path matches this glob
 *       (e.g. {@code "*.java"} or {@code "**\/test\/**"}).
 *   <li>{@code max_bytes_per_file} (integer, optional, default 8192) -- cap on how many bytes of
 *       each file to scan. Most source files are well under 8 KB; this default keeps the tool fast
 *       on large repos. The LLM can raise this when scanning generated / minified / vendored files,
 *       up to the {@link #MAX_BYTES_HARD_CAP} (256 KB) above which we refuse to avoid blowing the
 *       token budget. When the cap is hit the matcher's line numbers stay correct relative to the
 *       scanned window; the tool appends a {@code "(scanned first N bytes of M-byte file)"} warning
 *       so the LLM knows matches may exist beyond the window.
 * </ul>
 *
 * <p>Returns one line per match in the form {@code "L<line> in <relative-path>: <code>"}, with a
 * header {@code "Found N matches for <pattern>"} so the LLM can see how much was returned.
 * Truncation is reported as {@code "(truncated to <n>; raise max_results to see more)"}.
 *
 * <p>Skips {@code .git/}, {@code build/}, {@code target/}, {@code node_modules/}, and other noisy
 * directories to keep the output focused on source.
 */
public class GrepTool implements Tool {

  /** Default cap on returned lines. */
  private static final int DEFAULT_MAX = 50;

  /** Hard cap on returned lines. Above this we refuse to read further. */
  private static final int HARD_CAP = 500;

  /**
   * Default cap on bytes scanned per file. 8 KB covers typical Java/Kotlin source files comfortably
   * while keeping the tool fast on large repos.
   */
  private static final int DEFAULT_MAX_BYTES_PER_FILE = 8192;

  /**
   * Hard cap on bytes scanned per file. Above this we refuse to read further -- protects the
   * conversation token budget when the LLM asks for an unreasonably large window.
   */
  public static final int MAX_BYTES_HARD_CAP = 256 * 1024;

  @Override
  public String name() {
    return "grep";
  }

  @Override
  public String description() {
    return "Search a file or directory tree for lines matching a "
        + "Java regex. Returns matches as 'L<line> in <path>: "
        + "<code>'. Works on raw text, no AST or TAC required. "
        + "Use file_glob to scope to *.java / *.xml / etc.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "pattern",
            Map.of("type", "string", "description", "Java regex to search for."),
            "path",
            Map.of(
                "type",
                "string",
                "description",
                "File or directory to search. " + "Default = source root."),
            "max_results",
            Map.of("type", "integer", "description", "Cap on returned lines. Default 50, max 500."),
            "ignore_case",
            Map.of("type", "boolean", "description", "Case-insensitive match. Default false."),
            "file_glob",
            Map.of(
                "type",
                "string",
                "description",
                "Optional glob filter, e.g. '*.java' or '**\\/test\\/**'."),
            "max_bytes_per_file",
            Map.of(
                "type",
                "integer",
                "description",
                "Cap on bytes scanned per file. Default 8192 (8 KB), max 262144 (256 KB).")),
        List.of("pattern"));
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (args == null || !args.has("pattern") || args.get("pattern").asText().isEmpty()) {
      return ToolResult.error("missing required argument: pattern");
    }
    String patternStr = args.get("pattern").asText();
    int max =
        Math.min(
            HARD_CAP,
            args.has("max_results") ? args.get("max_results").asInt(DEFAULT_MAX) : DEFAULT_MAX);
    boolean ic = args.has("ignore_case") && args.get("ignore_case").asBoolean(false);
    String fileGlob = args.has("file_glob") ? args.get("file_glob").asText() : null;
    int maxBytesPerFile =
        Math.min(
            MAX_BYTES_HARD_CAP,
            args.has("max_bytes_per_file")
                ? args.get("max_bytes_per_file").asInt(DEFAULT_MAX_BYTES_PER_FILE)
                : DEFAULT_MAX_BYTES_PER_FILE);

    Pattern pattern;
    try {
      int flags = ic ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
      pattern = Pattern.compile(patternStr, flags);
    } catch (PatternSyntaxException e) {
      return ToolResult.error("invalid regex: " + e.getMessage());
    }

    Path root = ctx.resolvePath(args.has("path") ? args.get("path").asText() : "");
    if (root == null) {
      return ToolResult.error("cannot resolve path");
    }
    if (!Files.exists(root)) {
      return ToolResult.error("path does not exist: " + root);
    }

    List<String> hits = new ArrayList<>(max);
    List<String> truncatedFiles = new ArrayList<>();
    GlobMatcher glob = fileGlob == null ? null : new GlobMatcher(fileGlob);
    try {
      if (Files.isRegularFile(root)) {
        searchFile(root, pattern, ctx, glob, hits, max, maxBytesPerFile, truncatedFiles);
      } else if (Files.isDirectory(root)) {
        try (var stream = Files.walk(root)) {
          int budget = max;
          for (var p : (Iterable<Path>) stream::iterator) {
            if (hits.size() >= max) break;
            if (!Files.isRegularFile(p)) continue;
            if (isSkippedDir(p)) continue;
            if (glob != null && !glob.matches(p, root)) continue;
            int before = hits.size();
            searchFile(p, pattern, ctx, glob, hits, max, maxBytesPerFile, truncatedFiles);
            budget -= (hits.size() - before);
          }
        }
      } else {
        return ToolResult.error("path is neither file nor directory: " + root);
      }
    } catch (Exception e) {
      return ToolResult.error("grep failed: " + e.getMessage());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Found ")
        .append(hits.size())
        .append(" match(es) for /")
        .append(patternStr)
        .append("/ in ")
        .append(root)
        .append(":\n");
    for (String h : hits) sb.append(h).append('\n');
    if (hits.size() >= max) {
      sb.append("(truncated to ").append(max).append("; raise max_results to see more)");
    }
    if (!truncatedFiles.isEmpty()) {
      sb.append("(scanned first ")
          .append(maxBytesPerFile)
          .append(" bytes only of: ")
          .append(String.join(", ", truncatedFiles))
          .append("; raise max_bytes_per_file to see more)")
          .append('\n');
    }
    return ToolResult.ok(sb.toString());
  }

  private static void searchFile(
      Path file,
      Pattern pattern,
      ToolContext ctx,
      GlobMatcher glob,
      List<String> hits,
      int max,
      int maxBytesPerFile,
      List<String> truncatedFiles) {
    // Avoid binary files — peek the first 8KB and look
    // for NUL bytes. Regex-matching binary produces
    // nonsense output.
    try (var in = Files.newInputStream(file)) {
      // Read up to maxBytesPerFile bytes. The LLM
      // controls this via the max_bytes_per_file
      // argument; default is 8 KB which covers the
      // vast majority of source files.
      byte[] head = in.readNBytes(maxBytesPerFile);
      for (byte b : head) if (b == 0) return;
      String content = new String(head, StandardCharsets.UTF_8);
      String[] lines = content.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (hits.size() >= max) return;
        if (pattern.matcher(lines[i]).find()) {
          String rel = relativize(file, ctx.sourceRoot());
          hits.add("L" + (i + 1) + " in " + rel + ": " + lines[i].trim());
        }
      }
      // Detect truncation: if we filled the read
      // buffer, attempt to read one more byte to see
      // if more data exists. Cheap (single byte) and
      // only happens when the LLM widened the window
      // or the file is at the boundary.
      if (head.length == maxBytesPerFile) {
        int probe = in.read();
        if (probe != -1) {
          truncatedFiles.add(relativize(file, ctx.sourceRoot()));
        }
      }
    } catch (Exception e) {
      // unreadable — skip
    }
  }

  private static String relativize(Path file, String sourceRoot) {
    if (sourceRoot == null || sourceRoot.isEmpty()) return file.toString();
    try {
      Path root = Path.of(sourceRoot);
      return root.toRealPath().relativize(file.toRealPath()).toString();
    } catch (Exception e) {
      return file.getFileName().toString();
    }
  }

  private static boolean isSkippedDir(Path file) {
    // Don't recurse into noisy / generated directories.
    for (Path p = file; p != null; p = p.getParent()) {
      String name = p.getFileName() == null ? "" : p.getFileName().toString();
      if (name.equals(".git")
          || name.equals("build")
          || name.equals("target")
          || name.equals("node_modules")
          || name.equals(".gradle")
          || name.equals(".idea")
          || name.equals("dist")
          || name.equals("out")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Tiny glob matcher: supports {@code *}, {@code **}, and {@code ?}. Backslash is honoured as an
   * escape. Used for {@code file_glob} -- we don't pull in a glob library just for one tool.
   *
   * <p>M4: the previous implementation recursed on every {@code *} and every {@code **}, producing
   * exponential worst-case behaviour on patterns like {@code **.java} against long paths or
   * adversarial inputs. The new implementation uses the classic two-pointer wildcard matcher (with
   * backtrack-state stored inline) which runs in O(|pattern| × |file|) worst case. {@code **} is
   * treated as "match zero or more path segments" at the segment level, and as "match zero or more
   * characters" within a segment (matching the convention used by gitignore + most file browsers).
   */
  static final class GlobMatcher {
    private final List<String> parts;

    GlobMatcher(String glob) {
      String norm = glob.replace('\\', '/');
      String[] segs = norm.split("/");
      this.parts = new ArrayList<>();
      for (String s : segs) {
        if (s.isEmpty() || s.equals(".")) continue;
        parts.add(s);
      }
    }

    boolean matches(Path file, Path root) {
      Path rel;
      try {
        rel = root.toRealPath().relativize(file.toRealPath());
      } catch (Exception e) {
        return false;
      }
      String[] fileParts = rel.toString().replace('\\', '/').split("/");
      return matchSegments(parts, fileParts);
    }

    /**
     * Match a list of glob segments against a list of file path segments. {@code **} matches any
     * number of segments (including zero); other patterns match exactly one segment using the
     * linear wildcard matcher {@link #matchSegment}.
     */
    private static boolean matchSegments(List<String> pattern, String[] file) {
      int pi = 0;
      int fi = 0;
      // Backtrack state for the most recent `**`.
      int starPi = -1;
      int starFi = 0;
      while (fi < file.length) {
        if (pi < pattern.size() && pattern.get(pi).equals("**")) {
          // Commit `**` here; advance pattern,
          // remember where to backtrack to.
          starPi = pi;
          starFi = fi;
          pi++;
        } else if (pi < pattern.size() && matchSegment(pattern.get(pi), file[fi])) {
          pi++;
          fi++;
        } else if (starPi != -1) {
          // Mismatch — backtrack: extend the
          // previous `**` to consume one more file
          // segment and retry the rest of the
          // pattern from there.
          pi = starPi + 1;
          starFi++;
          fi = starFi;
        } else {
          return false;
        }
      }
      // The file is exhausted; any remaining pattern
      // must be all `**`s (which match empty).
      while (pi < pattern.size() && pattern.get(pi).equals("**")) {
        pi++;
      }
      return pi == pattern.size();
    }

    /**
     * Linear-time wildcard match within a single segment. Supports {@code *} (any sequence,
     * including empty) and {@code ?} (exactly one char). Algorithm: two pointers, one on the
     * pattern, one on the file. When we hit a {@code *}, we record the pattern position + current
     * file position; on mismatch we backtrack to the most recent {@code *} and try one more
     * character of the file. Worst case O(m × n).
     */
    private static boolean matchSegment(String pattern, String file) {
      int pi = 0;
      int fi = 0;
      int starPi = -1;
      int starFi = 0;
      while (fi < file.length()) {
        if (pi < pattern.length() && pattern.charAt(pi) == '*') {
          // Collapse consecutive `*`s.
          while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
          if (pi == pattern.length()) return true;
          starPi = pi;
          starFi = fi;
          // Don't advance fi — `*` matches zero
          // chars first; the loop will retry with
          // more if the rest fails.
        } else if (pi < pattern.length() && pattern.charAt(pi) == '?') {
          pi++;
          fi++;
        } else if (pi < pattern.length() && pattern.charAt(pi) == file.charAt(fi)) {
          pi++;
          fi++;
        } else if (starPi != -1) {
          // Mismatch: backtrack, consume one more
          // char with the previous `*`.
          pi = starPi;
          starFi++;
          fi = starFi;
        } else {
          return false;
        }
      }
      // File exhausted: trailing `*`s match empty.
      while (pi < pattern.length() && pattern.charAt(pi) == '*') pi++;
      return pi == pattern.length();
    }
  }
}
