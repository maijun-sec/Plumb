package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.plumb.ai.agent.ToolDefinition;
import org.plumb.ai.agent.ToolResult;

/**
 * Step 3 -- return the {@code extends} / {@code implements} chain for a given class. Used by the
 * bug scanner to spot trust-boundary violations: a class extending {@code AbstractTrustedService}
 * has different security expectations than a plain POJO, and a class implementing
 * {@code @FunctionalInterface} is almost certainly a lambda body that the static analyzer already
 * mis-read.
 *
 * <p>No IR dependency -- the tool is regex-only. Java grammar is regular enough for this: a single
 * regex pulls {@code class Foo extends Bar implements Baz, Qux} out of a source file.
 *
 * <p>Limitations:
 *
 * <ul>
 *   <li>Only sees the class declaration, not the full transitive ancestor chain. The LLM can call
 *       {@code get_class_hierarchy} recursively on each super to walk the chain.
 *   <li>Doesn't resolve FQN -- returns simple names. The LLM can pass {@code FQN} as the {@code
 *       class_name} to filter.
 *   <li>Doesn't follow generic type parameters ({@code class Foo<T extends Bar>}). The super name
 *       is captured without the {@code <...>}.
 * </ul>
 */
public class GetClassHierarchyTool implements Tool {

  private static final Pattern CLASS_DECL =
      Pattern.compile(
          "\\b(class|interface|enum)\\s+"
              + "(?:(?:public|private|protected|static|final|abstract|"
              + "synchronized|volatile|transient|native|sealed|non-sealed)\\s+)*"
              + "(?<name>[A-Za-z_][A-Za-z0-9_]*)"
              + "(?:<[^>]*>)?"
              + "(?:\\s+extends\\s+(?<ext>[A-Za-z_][A-Za-z0-9_.<>,\\s]*?))?"
              + "(?:\\s+implements\\s+(?<impl>[A-Za-z_][A-Za-z0-9_.<>,\\s]*?))?"
              + "(?<terminator>[\\s{])");
  private static final Pattern AT_INTERFACE =
      Pattern.compile("@interface\\s+(?<name>[A-Za-z_][A-Za-z0-9_]*)");

  @Override
  public String name() {
    return "get_class_hierarchy";
  }

  @Override
  public String description() {
    return "Return the extends / implements chain of a class. "
        + "Pass the simple name (e.g. 'UserController') or "
        + "an FQN prefix (e.g. 'com.example.User'). Reads "
        + "the source file with regex; doesn't need IR. "
        + "Returns the immediate super-class and implemented "
        + "interfaces, plus a list of matching files so the "
        + "LLM can recurse on the parents.";
  }

  @Override
  public ToolDefinition definition() {
    return new ToolDefinition(
        name(),
        description(),
        Map.of(
            "class_name",
            Map.of("type", "string", "description", "Simple or FQN class name to look up.")),
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
    List<String> hits = new ArrayList<>();
    try (var stream = Files.walk(root)) {
      for (var p : (Iterable<java.nio.file.Path>) stream::iterator) {
        if (!Files.isRegularFile(p)) continue;
        String name = p.getFileName().toString();
        if (!name.endsWith(".java")) continue;
        if (GrepTool.GlobMatcher.class == null) {
          // never null but satisfy compiler
        }
        if (isSkippedDir(p)) continue;
        byte[] bytes;
        try {
          bytes = Files.readAllBytes(p);
        } catch (Exception e) {
          continue;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (!content.contains(query)) continue;
        Matcher m = CLASS_DECL.matcher(content);
        while (m.find()) {
          String name2 = m.group("name");
          if (!name2.equals(simpleName(query))) continue;
          String ext = m.group("ext");
          String impl = m.group("impl");
          if (ext == null && impl == null) continue;
          String rel = relativize(p, ctx.sourceRoot());
          StringBuilder sb = new StringBuilder();
          sb.append(name2).append(" (in ").append(rel).append("):\n");
          if (ext != null) {
            sb.append("  extends: ").append(String.join(", ", joinSimple(ext))).append('\n');
          }
          if (impl != null) {
            sb.append("  implements: ").append(String.join(", ", joinSimple(impl))).append('\n');
          }
          hits.add(sb.toString());
        }
      }
    } catch (Exception e) {
      return ToolResult.error("walk failed: " + e.getMessage());
    }
    if (hits.isEmpty()) {
      return ToolResult.ok(
          "(no class declaration matching '" + query + "' found under " + root + ")");
    }
    return ToolResult.ok("Hierarchy for " + query + ":\n" + String.join("\n", hits));
  }

  /** Strip generic parameters and split a comma-separated super list into individual names. */
  private static List<String> joinSimple(String spec) {
    List<String> out = new ArrayList<>();
    for (String s : spec.split(",")) {
      String t = s.trim();
      int lt = t.indexOf('<');
      if (lt >= 0) t = t.substring(0, lt).trim();
      // strip "extends Foo.Bar" — just the tail name
      int dot = t.lastIndexOf('.');
      if (dot >= 0 && t.indexOf('.') < t.length() - 1) {
        t = t.substring(dot + 1);
      }
      if (!t.isEmpty()) out.add(t);
    }
    return out;
  }

  private static String simpleName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
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
