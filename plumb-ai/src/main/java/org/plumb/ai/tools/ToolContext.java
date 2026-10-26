package org.plumb.ai.tools;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.plumb.reporter.Finding;

/**
 * The execution context the agent hands to every tool call. Holds:
 *
 * <ul>
 *   <li>The {@link Finding} currently under review (FP reducer) or the function snippet (bug
 *       scanner).
 *   <li>The {@code MergedProgram} + per-function summaries + call graph the tools need to answer
 *       questions like "is this variable sanitised?" or "who calls this method?".
 *   <li>The source-file root (for absolute path resolution when the LLM passes a relative path).
 *   <li>A small in-memory file cache so a tool that reads the same file twice doesn't hit disk
 *       twice (the LLM may iterate over the same file in one session).
 *   <li>The list of dataflow trace lines for the current finding (lifted out of the {@code Finding}
 *       so tools don't have to re-traverse it).
 * </ul>
 *
 * <p>Constructed once per agent session (i.e. per {@code ReActAgent.run} call). The agent passes
 * the same context to every tool; tools must not mutate it. The built-in file cache uses {@link
 * java.util.concurrent.ConcurrentHashMap} so a single {@link ToolContext} is safe to share across
 * parallel agent runs (plumb-main scans with {@code --threads > 1} against the same ctx).
 */
public class ToolContext {

  private final Finding currentFinding;
  private final String taskKind; // "fp-reduction" | "bug-scan"
  private final String sourceRoot;
  private final List<String> dataflowTrace;
  private final List<String> ruleIds;
  // B3: ConcurrentHashMap — same ToolContext can be
  // shared across parallel agent runs (plumb-main runs
  // --threads > 1 against the same ctx) and the previous
  // HashMap would corrupt under concurrent put. The
  // readFileBytes check-then-act is now atomic per key
  // (computeIfAbsent would be even cleaner, but the
  // "return null on read failure" contract requires the
  // explicit lookup so we can distinguish cached-bytes
  // from never-cached).
  private final Map<String, byte[]> fileCache;
  // Hooks for the cross-function + program views; the
  // tools cast / use them as needed. We hold them as
  // Object so plumb-ai doesn't need a hard dep on
  // plumb-tac / plumb-dfa for tests that mock
  // them.
  private final Object program;
  private final Object summaryCache;
  private final Object callGraph;

  public ToolContext(
      Finding currentFinding,
      String taskKind,
      String sourceRoot,
      List<String> dataflowTrace,
      List<String> ruleIds,
      Object program,
      Object summaryCache,
      Object callGraph) {
    this.currentFinding = currentFinding;
    this.taskKind = taskKind;
    this.sourceRoot = sourceRoot;
    this.dataflowTrace = dataflowTrace == null ? List.of() : dataflowTrace;
    this.ruleIds = ruleIds == null ? List.of() : ruleIds;
    this.program = program;
    this.summaryCache = summaryCache;
    this.callGraph = callGraph;
    this.fileCache = new java.util.concurrent.ConcurrentHashMap<>();
  }

  public Finding currentFinding() {
    return currentFinding;
  }

  public String taskKind() {
    return taskKind;
  }

  public String sourceRoot() {
    return sourceRoot;
  }

  public List<String> dataflowTrace() {
    return dataflowTrace;
  }

  public List<String> ruleIds() {
    return ruleIds;
  }

  public Object program() {
    return program;
  }

  public Object summaryCache() {
    return summaryCache;
  }

  public Object callGraph() {
    return callGraph;
  }

  /**
   * Sentinel value used in {@link #fileCache} to mark "tried to read and failed". ConcurrentHashMap
   * doesn't allow null values, so we cache the empty array on failure and convert it back to a
   * {@code null} return for the caller. Caching the failure stops the agent loop from hammering the
   * FS for a missing / unreadable file across many turns.
   */
  private static final byte[] READ_FAILED = new byte[0];

  /**
   * Read a file's bytes, hitting the cache. Returns {@code null} if the file cannot be read (tools
   * convert that to a {@code ToolResult.error} so the LLM can adjust). Safe to call concurrently
   * from multiple agent runs sharing the same {@link ToolContext} -- the cache uses {@link
   * java.util.concurrent.ConcurrentHashMap} and the read is de-duplicated per path via {@link
   * java.util.concurrent.ConcurrentHashMap#putIfAbsent}.
   */
  public byte[] readFileBytes(String path) {
    byte[] cached = fileCache.get(path);
    if (cached != null) return cached == READ_FAILED ? null : cached;
    try {
      byte[] data = java.nio.file.Files.readAllBytes(resolvePath(path));
      byte[] prior = fileCache.putIfAbsent(path, data);
      return prior != null ? prior : data;
    } catch (Exception e) {
      fileCache.putIfAbsent(path, READ_FAILED);
      return null;
    }
  }

  /**
   * Resolve a (possibly relative) path against {@link #sourceRoot}. Absolute paths pass through.
   */
  public java.nio.file.Path resolvePath(String path) {
    if (path == null || path.isEmpty()) {
      return sourceRoot == null ? Path.of(".") : Path.of(sourceRoot);
    }
    Path p = Path.of(path);
    if (p.isAbsolute()) return p;
    return sourceRoot == null ? p.toAbsolutePath() : Path.of(sourceRoot).resolve(p).normalize();
  }
}
