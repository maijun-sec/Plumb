package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * read an entire Java class file's source code. The LLM uses this to understand the class's full
 * surface (constructors, fields, helper methods) before judging a finding that's deep inside a
 * method body.
 *
 * <p>Distinguished from {@link ReadSourceTool} (which returns a line range) and {@link GrepTool}
 * (which matches regex). This tool resolves a class name to its source file (via {@code .java} file
 * lookup) and returns the whole file. The lookup walks the source tree; the class file is whichever
 * {@code .java} contains {@code class <Name>} (or {@code interface} / {@code enum} /
 * {@code @interface}).
 *
 * <p>No IR dependency. Pure filesystem + regex.
 */
public class GetFullClassTool implements Tool {

  /**
   * Soft cap on the returned source. The LLM doesn't need a 5,000-line class to judge a finding —
   * the cap prevents the tool from blowing the conversation's token budget.
   */
  private static final int MAX_LINES = 500;

  @Override
  public String name() {
    return "get_full_class";
  }

  @Override
  public String description() {
    return "Return the full source of a Java class. Resolves "
        + "the class name to its .java file (walks the "
        + "source tree, picks the file containing 'class "
        + "<Name>' or 'interface <Name>'). Returns the "
        + "whole file up to "
        + MAX_LINES
        + " lines. Use "
        + "this to see the full class surface before "
        + "judging a finding on a method body.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of("class_name", Map.of("type", "string", "description", "Simple or FQN class name.")),
        List.of("class_name"));
  }

  @Override
  public ToolResult execute(ToolContext ctx, JsonNode args) {
    if (args == null || !args.has("class_name") || args.get("class_name").asText().isEmpty()) {
      return ToolResult.error("missing required argument: class_name");
    }
    String query = args.get("class_name").asText();
    java.nio.file.Path root =
        ctx.sourceRoot() == null
            ? java.nio.file.Path.of(".")
            : java.nio.file.Path.of(ctx.sourceRoot());
    if (!Files.exists(root)) {
      return ToolResult.error("source root not found: " + root);
    }
    java.nio.file.Path found = null;
    String simple = simpleName(query);
    try (var stream = Files.walk(root)) {
      for (var p : (Iterable<java.nio.file.Path>) stream::iterator) {
        if (found != null) break;
        if (!Files.isRegularFile(p)) continue;
        String name = p.getFileName().toString();
        if (!name.endsWith(".java")) continue;
        if (isSkippedDir(p)) continue;
        // Cheap reject: file's name has to contain
        // the simple name.
        String base = name.substring(0, name.length() - 5);
        if (!base.equals(simple)) continue;
        // Content reject: the class has to be declared
        // inside (we don't want TestFoo.class to
        // satisfy a query for "Foo").
        byte[] bytes;
        try {
          bytes = Files.readAllBytes(p);
        } catch (Exception e) {
          continue;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.contains("class " + simple)
            || content.contains("interface " + simple)
            || content.contains("enum " + simple)
            || content.contains("@interface " + simple)) {
          found = p;
        }
      }
    } catch (Exception e) {
      return ToolResult.error("walk failed: " + e.getMessage());
    }
    if (found == null) {
      return ToolResult.ok(
          "(no .java file containing class '" + simple + "' found under " + root + ")");
    }
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(found);
    } catch (Exception e) {
      return ToolResult.error("cannot read " + found + ": " + e.getMessage());
    }
    String content = new String(bytes, StandardCharsets.UTF_8);
    String[] lines = content.split("\n", -1);
    int cap = Math.min(MAX_LINES, lines.length);
    StringBuilder sb = new StringBuilder();
    sb.append("Source of ")
        .append(simple)
        .append(" (")
        .append(found.getFileName())
        .append(", ")
        .append(lines.length)
        .append(" lines total, showing ")
        .append(cap)
        .append("):\n");
    for (int i = 0; i < cap; i++) {
      sb.append("L").append(i + 1).append(": ").append(lines[i]).append('\n');
    }
    if (lines.length > cap) {
      sb.append("(truncated to ")
          .append(cap)
          .append("; raise the per-tool limit if you need more)");
    }
    return ToolResult.ok(sb.toString());
  }

  private static String simpleName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
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
