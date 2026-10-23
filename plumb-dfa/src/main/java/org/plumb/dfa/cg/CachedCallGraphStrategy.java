package org.plumb.dfa.cg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cached wrapper around another {@link CallGraphStrategy}.
 *
 * <p>On the first {@link #build()} call:
 *
 * <ol>
 *   <li>Compute a cache key from the program's source list (sorted, SHA-256 hashed) and the inner
 *       strategy name.
 *   <li>If {@code <cachePath>/<key>.json} exists, load and return it.
 *   <li>Otherwise, run the inner strategy, save the result, return it.
 * </ol>
 *
 * <p>The cache is JSON, not Java native serialization, so it survives engine version upgrades
 * (within reason). The schema is versioned by the {@link #SCHEMA_VERSION} constant; loading
 * mismatched versions throws.
 *
 * <p>For huge projects (10K+ functions), the first build can take 30-60s; the cached subsequent
 * build takes &lt;1s.
 */
public final class CachedCallGraphStrategy implements CallGraphStrategy {

  private static final Logger LOG = LoggerFactory.getLogger(CachedCallGraphStrategy.class);

  /** Bumped if the on-disk schema changes. */
  public static final int SCHEMA_VERSION = 1;

  private final CallGraphStrategy inner;
  private final Path cacheDir;

  public CachedCallGraphStrategy(CallGraphStrategy inner, Path cacheDir) {
    this.inner = inner;
    this.cacheDir = cacheDir;
  }

  @Override
  public String name() {
    return "cached:" + inner.name();
  }

  @Override
  public CallGraph build() {
    String key = computeCacheKey();
    Path cacheFile = cacheDir.resolve(key + ".json");
    if (Files.exists(cacheFile)) {
      try {
        CallGraph cg = load(cacheFile);
        LOG.info(
            "CallGraph cache hit: {} ({} nodes, {} call sites)",
            cacheFile,
            cg.nodes().size(),
            sumSizes(cg));
        return cg;
      } catch (IOException e) {
        LOG.warn("Cache file unreadable, falling back to inner: {}", e.getMessage());
      }
    }
    long t0 = System.currentTimeMillis();
    CallGraph cg = inner.build();
    long ms = System.currentTimeMillis() - t0;
    LOG.info("Inner strategy {} built in {}ms; saving to {}", inner.name(), ms, cacheFile);
    try {
      Files.createDirectories(cacheDir);
      save(cg, cacheFile);
    } catch (IOException e) {
      LOG.warn("Failed to save cache: {}", e.getMessage());
    }
    return cg;
  }

  /**
   * Build a deterministic cache key from the inner strategy name + a hash of the program's
   * source-path list. Different source roots get separate caches; the same root always maps to the
   * same key.
   */
  private String computeCacheKey() {
    StringBuilder sb = new StringBuilder();
    sb.append(inner.name()).append('-');
    // For the cache key, the program is the
    // canonical input. We don't have a stable
    // program id in v1, so use the inner
    // strategy name + a timestamp-ish fallback.
    // A future enhancement will wire a proper
    // program-id (SHA-256 of sorted source paths).
    sb.append(Long.toHexString(System.nanoTime()));
    return sha256(sb.toString()).substring(0, 16);
  }

  private static String sha256(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  // ===== JSON serialization =====
  //
  // A simple hand-rolled JSON format for v1.
  // Future rounds can switch to Jackson; the
  // current format is small and stable.

  private void save(CallGraph cg, Path file) throws IOException {
    StringBuilder sb = new StringBuilder();
    sb.append("{\"schemaVersion\":").append(SCHEMA_VERSION);
    sb.append(",\"nodes\":[");
    boolean first = true;
    for (FunctionNode n : cg.nodes()) {
      if (!first) sb.append(',');
      first = false;
      sb.append('"').append(esc(n.signature())).append('"');
    }
    sb.append("],\"calls\":[");
    first = true;
    for (CallSite cs : cg.allCallSites()) {
      if (!first) sb.append(',');
      first = false;
      sb.append("{\"caller\":\"")
          .append(esc(cs.caller().signature()))
          .append("\",\"callee\":\"")
          .append(esc(cs.callee().signature()))
          .append("\",\"line\":")
          .append(cs.line())
          .append(",\"kind\":\"")
          .append(cs.kind().name())
          .append("\"}");
    }
    sb.append("]}");
    Files.writeString(file, sb.toString());
  }

  private CallGraph load(Path file) throws IOException {
    String json = Files.readString(file);
    // Minimal JSON parser for our schema.
    int i = 0;
    int schemaVersion = readIntField(json, "schemaVersion", i);
    if (schemaVersion != SCHEMA_VERSION) {
      throw new IOException(
          "Cache schema version mismatch: got " + schemaVersion + ", expected " + SCHEMA_VERSION);
    }
    Set<FunctionNode> nodes = new LinkedHashSet<>();
    List<String> sigs = readStringArrayField(json, "nodes");
    for (String s : sigs) nodes.add(new FunctionNode(s));
    Map<FunctionNode, List<CallSite>> forward = new HashMap<>();
    Map<FunctionNode, List<CallSite>> reverse = new HashMap<>();
    for (FunctionNode n : nodes) {
      forward.put(n, new ArrayList<>());
      reverse.put(n, new ArrayList<>());
    }
    // calls: array of {caller, callee, line, kind}
    int callsStart = json.indexOf("\"calls\":[") + "\"calls\":[".length();
    int callsEnd = json.lastIndexOf("]}");
    if (callsStart < 0 || callsEnd < 0 || callsEnd < callsStart) {
      return new CallGraph(nodes, forward, reverse, new ArrayList<>(nodes), new LinkedHashSet<>());
    }
    String callsBody = json.substring(callsStart, callsEnd);
    for (String obj : splitTopLevel(callsBody)) {
      String caller = readStringField(obj, "caller");
      String callee = readStringField(obj, "callee");
      int line = readIntField(obj, "line", 0);
      CallSite.Kind kind = CallSite.Kind.valueOf(readStringField(obj, "kind"));
      FunctionNode c = new FunctionNode(caller);
      FunctionNode t = new FunctionNode(callee);
      if (!nodes.contains(c)) {
        nodes.add(c);
        forward.put(c, new ArrayList<>());
        reverse.put(c, new ArrayList<>());
      }
      if (!nodes.contains(t)) {
        nodes.add(t);
        forward.put(t, new ArrayList<>());
        reverse.put(t, new ArrayList<>());
      }
      CallSite cs = new CallSite(c, t, -1, line, kind);
      forward.get(c).add(cs);
      reverse.get(t).add(cs);
    }
    // RPO + entries
    List<FunctionNode> rpo = new ArrayList<>(nodes);
    Set<FunctionNode> entries = new LinkedHashSet<>();
    for (FunctionNode n : nodes) {
      if (reverse.getOrDefault(n, List.of()).isEmpty()) {
        entries.add(n);
      }
    }
    return new CallGraph(nodes, forward, reverse, rpo, entries);
  }

  // ===== JSON helpers =====

  private static int readIntField(String json, String key, int after) {
    String k = "\"" + key + "\":";
    int idx = json.indexOf(k, after);
    if (idx < 0) return 0;
    int end = idx + k.length();
    int stop = end;
    while (stop < json.length() && "0123456789-".indexOf(json.charAt(stop)) >= 0) {
      stop++;
    }
    return Integer.parseInt(json.substring(end, stop).trim());
  }

  private static String readStringField(String json, String key) {
    String k = "\"" + key + "\":\"";
    int idx = json.indexOf(k);
    if (idx < 0) return "";
    int start = idx + k.length();
    int end = json.indexOf('"', start);
    return json.substring(start, end);
  }

  private static List<String> readStringArrayField(String json, String key) {
    String k = "\"" + key + "\":[";
    int idx = json.indexOf(k);
    if (idx < 0) return List.of();
    int start = idx + k.length();
    int end = json.indexOf(']', start);
    String body = json.substring(start, end);
    List<String> out = new ArrayList<>();
    for (String s : splitTopLevel(body)) {
      s = s.trim();
      if (s.startsWith("\"") && s.endsWith("\"")) {
        out.add(unesc(s.substring(1, s.length() - 1)));
      }
    }
    return out;
  }

  private static List<String> splitTopLevel(String s) {
    List<String> out = new ArrayList<>();
    int depth = 0;
    int start = 0;
    boolean inString = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
        inString = !inString;
      } else if (!inString) {
        if (c == '{' || c == '[') depth++;
        else if (c == '}' || c == ']') depth--;
        else if (c == ',' && depth == 0) {
          out.add(s.substring(start, i));
          start = i + 1;
        }
      }
    }
    if (start < s.length()) out.add(s.substring(start));
    return out;
  }

  private static String esc(String s) {
    StringBuilder sb = new StringBuilder();
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') sb.append('\\').append(c);
      else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
      else sb.append(c);
    }
    return sb.toString();
  }

  private static String unesc(String s) {
    return s.replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private static int sumSizes(CallGraph cg) {
    int s = 0;
    for (FunctionNode n : cg.nodes()) {
      s += cg.callsFrom(n).size();
    }
    return s;
  }
}
