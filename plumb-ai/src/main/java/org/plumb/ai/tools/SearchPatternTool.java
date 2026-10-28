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
import java.util.*;
import java.util.regex.Pattern;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * symbol-aware regex search. Wraps {@link GrepTool} with two extras the LLM finds useful when
 * hunting for cross-function references:
 *
 * <ol>
 *   <li><b>Camel-case boundary matching</b> — the LLM can pass {@code word="userRepo"} and the tool
 *       returns lines containing {@code userRepo}, {@code UserRepo}, {@code USER_REPO}, and {@code
 *       user_repo} (the four common Java naming styles for the same identifier). Plain regex
 *       matching would miss the case-mismatches.
 *   <li><b>Definition-aware filtering</b> — {@code kind="definition"} returns only the lines that
 *       look like a declaration (start with a visibility / type / annotation), ignoring usage
 *       sites. Useful for "where is this constant defined?" without the noise of "every place it's
 *       referenced".
 * </ol>
 *
 * <p>No IR dependency. The tool is regex + heuristics.
 *
 * <p>Like {@link GrepTool}, this tool scans up to {@code max_bytes_per_file} bytes per file
 * (default 8 KB). The cap is configurable via the same argument; a truncation warning is emitted
 * when the window is hit so the LLM knows matches may exist beyond the scanned prefix.
 */
public class SearchPatternTool implements Tool {

  @Override
  public String name() {
    return "search_pattern";
  }

  @Override
  public String description() {
    return "Symbol-aware regex search. Pass 'word' to match "
        + "userRepo / UserRepo / USER_REPO / user_repo "
        + "(CamelCase boundary); pass 'pattern' for raw "
        + "regex. kind='definition' filters to declaration "
        + "lines only. kind='usage' returns every line. "
        + "Default kind=usage.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "word",
            Map.of(
                "type",
                "string",
                "description",
                "Identifier to search for. " + "Matched in 4 common Java naming styles."),
            "pattern",
            Map.of(
                "type",
                "string",
                "description",
                "Raw Java regex to search for. " + "Use when 'word' isn't enough."),
            "path",
            Map.of("type", "string", "description", "File or directory. Default = source root."),
            "kind",
            Map.of("type", "string", "description", "'usage' (default) or 'definition'."),
            "max_results",
            Map.of("type", "integer", "description", "Cap. Default 50, max 500."),
            "max_bytes_per_file",
            Map.of(
                "type",
                "integer",
                "description",
                "Cap on bytes scanned per file. Default 8192 (8 KB), max 262144 (256 KB).")),
        List.of());
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (args == null || (!args.has("word") && !args.has("pattern"))) {
      return ToolResult.error("must supply 'word' or 'pattern'");
    }
    // M6: reject empty strings explicitly. Pattern.quote("")
    // produces \Q\E which matches every position, so an
    // empty word would silently match every line and
    // pollute the agent's view of the code.
    if (args.has("word") && args.get("word").asText().isEmpty()) {
      return ToolResult.error("'word' must be non-empty");
    }
    if (args.has("pattern") && args.get("pattern").asText().isEmpty()) {
      return ToolResult.error("'pattern' must be non-empty");
    }
    String word = args.has("word") ? args.get("word").asText() : null;
    String rawPattern = args.has("pattern") ? args.get("pattern").asText() : null;
    String kind = args.has("kind") ? args.get("kind").asText("usage") : "usage";
    int max = Math.min(500, args.has("max_results") ? args.get("max_results").asInt(50) : 50);
    int maxBytesPerFile =
        Math.min(
            GrepTool.MAX_BYTES_HARD_CAP,
            args.has("max_bytes_per_file") ? args.get("max_bytes_per_file").asInt(8192) : 8192);
    java.nio.file.Path root = ctx.resolvePath(args.has("path") ? args.get("path").asText() : "");

    Pattern pattern;
    if (word != null && !word.isEmpty()) {
      // Build a regex that matches any of the 4
      // naming variants. Escape each form, join with |.
      String[] variants = camelVariants(word);
      StringBuilder re = new StringBuilder("\\b(");
      for (int i = 0; i < variants.length; i++) {
        if (i > 0) re.append('|');
        re.append(Pattern.quote(variants[i]));
      }
      re.append(")\\b");
      pattern = Pattern.compile(re.toString());
    } else {
      try {
        pattern = Pattern.compile(rawPattern);
      } catch (Exception e) {
        return ToolResult.error("invalid regex: " + e.getMessage());
      }
    }

    List<String> hits = new ArrayList<>();
    List<String> truncatedFiles = new ArrayList<>();
    try {
      if (Files.isRegularFile(root)) {
        searchFile(root, pattern, kind, hits, max, maxBytesPerFile, ctx, truncatedFiles);
      } else if (Files.isDirectory(root)) {
        try (var stream = Files.walk(root)) {
          for (var p : (Iterable<java.nio.file.Path>) stream::iterator) {
            if (hits.size() >= max) break;
            if (!Files.isRegularFile(p)) continue;
            if (isSkippedDir(p)) continue;
            searchFile(p, pattern, kind, hits, max, maxBytesPerFile, ctx, truncatedFiles);
          }
        }
      }
    } catch (Exception e) {
      return ToolResult.error("search_pattern failed: " + e.getMessage());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("Found ")
        .append(hits.size())
        .append(" match(es) for '")
        .append(word != null ? word : rawPattern)
        .append("' (")
        .append(kind)
        .append("):\n");
    for (String h : hits) sb.append(h).append('\n');
    if (hits.size() >= max) {
      sb.append("(truncated to ").append(max).append(")");
    }
    if (!truncatedFiles.isEmpty()) {
      sb.append("(scanned first ")
          .append(maxBytesPerFile)
          .append(" bytes only of: ")
          .append(String.join(", ", truncatedFiles))
          .append("; raise max_bytes_per_file to see more)");
    }
    return ToolResult.ok(sb.toString());
  }

  private static void searchFile(
      java.nio.file.Path file,
      Pattern p,
      String kind,
      List<String> hits,
      int max,
      int maxBytesPerFile,
      ToolContext ctx,
      List<String> truncatedFiles) {
    try (var in = Files.newInputStream(file)) {
      byte[] head = in.readNBytes(maxBytesPerFile);
      for (byte b : head) if (b == 0) return; // binary
      String content = new String(head, StandardCharsets.UTF_8);
      String[] lines = content.split("\n", -1);
      for (int i = 0; i < lines.length; i++) {
        if (hits.size() >= max) return;
        String line = lines[i];
        if (!p.matcher(line).find()) continue;
        if ("definition".equals(kind) && !looksLikeDefinition(line)) continue;
        String rel = relativize(file, ctx.sourceRoot());
        hits.add("L" + (i + 1) + " in " + rel + ": " + line.trim());
      }
      // Detect truncation: if we filled the read
      // buffer, peek one more byte. Cheap and only
      // happens near the boundary.
      if (head.length == maxBytesPerFile) {
        int probe = in.read();
        if (probe != -1) {
          truncatedFiles.add(relativize(file, ctx.sourceRoot()));
        }
      }
    } catch (Exception e) {
      // skip
    }
  }

  /**
   * Heuristic: declaration lines start with a visibility / type / annotation, or contain {@code =}
   * at the end. Conservative -- false positives are fine for the LLM, false negatives miss real
   * definitions.
   */
  private static boolean looksLikeDefinition(String line) {
    String t = line.stripLeading();
    if (t.isEmpty()) return false;
    // Common declaration starters
    String[] starters = {
      "public",
      "private",
      "protected",
      "static",
      "final",
      "synchronized",
      "abstract",
      "volatile",
      "transient",
      "native",
      "@"
    };
    for (String s : starters) {
      if (t.startsWith(s)) return true;
    }
    // Class / interface / enum / record
    String[] decls = {"class ", "interface ", "enum ", "record "};
    for (String d : decls) {
      if (t.startsWith(d)) return true;
    }
    return false;
  }

  /**
   * Generate the 4 common Java naming variants for a given identifier. {@code userRepo} → {@code
   * [userRepo, UserRepo, USER_REPO, user_repo]}.
   */
  private static String[] camelVariants(String word) {
    if (word.isEmpty()) return new String[] {word};
    // Split on camelCase boundaries: insert _ before
    // each uppercase letter and lowercase the whole
    // thing.
    StringBuilder snake = new StringBuilder();
    for (int i = 0; i < word.length(); i++) {
      char c = word.charAt(i);
      if (i > 0 && Character.isUpperCase(c)) snake.append('_');
      snake.append(Character.toLowerCase(c));
    }
    String snakeForm = snake.toString(); // user_repo
    String camelForm = word; // userRepo
    String upperCamel = Character.toUpperCase(word.charAt(0)) + word.substring(1); // UserRepo
    String upperSnake = snakeForm.toUpperCase(Locale.ROOT); // USER_REPO
    // Deduplicate via a Set, preserve order.
    Set<String> out = new LinkedHashSet<>();
    out.add(camelForm);
    out.add(upperCamel);
    out.add(upperSnake);
    out.add(snakeForm);
    return out.toArray(new String[0]);
  }

  private static String relativize(java.nio.file.Path file, String sourceRoot) {
    if (sourceRoot == null || sourceRoot.isEmpty()) return file.toString();
    try {
      return java.nio.file.Path.of(sourceRoot)
          .toRealPath()
          .relativize(file.toRealPath())
          .toString();
    } catch (Exception e) {
      return file.getFileName().toString();
    }
  }

  private static boolean isSkippedDir(java.nio.file.Path file) {
    for (java.nio.file.Path p = file; p != null; p = p.getParent()) {
      String n = p.getFileName() == null ? "" : p.getFileName().toString();
      if (n.equals(".git")
          || n.equals("build")
          || n.equals("target")
          || n.equals("node_modules")
          || n.equals(".gradle")
          || n.equals(".idea")
          || n.equals("dist")
          || n.equals("out")) {
        return true;
      }
    }
    return false;
  }
}
