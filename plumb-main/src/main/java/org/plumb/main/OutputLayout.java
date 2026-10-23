package org.plumb.main;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Resolves where to read / write on-disk artifacts given an {@code output-dir}. The layout is:
 *
 * <pre>
 *   &lt;outputDir&gt;/
 *     cache/
 *       programs/
 *         &lt;sha1-of-source-path&gt;.tac      one file per TacProgram
 *       merged.tac                        the full MergedProgram (R-cross-TU)
 *       callgraph.tac                     the global call graph
 *     report/
 *       findings.ndjson                   the canonical findings dump
 *       findings.console                  the human-readable dump (when
 *                                         --format console is used)
 * </pre>
 *
 * <p>Path hashing uses SHA-1 of the canonical source path string. Two scans over the same source
 * tree produce identical cache keys, so the second scan can short-circuit the parse + translate
 * stage entirely.
 */
public final class OutputLayout {

  private final Path root;
  private final Path cacheDir;
  private final Path programsDir;
  private final Path reportDir;
  private final Path mergedCache;
  private final Path callgraphCache;
  private final Path reportFindings;
  private final Path reportConsole;

  public OutputLayout(Path outputDir) {
    this.root = Objects.requireNonNull(outputDir, "outputDir");
    this.cacheDir = root.resolve("cache");
    this.programsDir = cacheDir.resolve("programs");
    this.reportDir = root.resolve("report");
    this.mergedCache = cacheDir.resolve("merged.tac");
    this.callgraphCache = cacheDir.resolve("callgraph.tac");
    this.reportFindings = reportDir.resolve("findings.ndjson");
    this.reportConsole = reportDir.resolve("findings.console");
  }

  public Path root() {
    return root;
  }

  public Path cacheDir() {
    return cacheDir;
  }

  public Path programsDir() {
    return programsDir;
  }

  public Path reportDir() {
    return reportDir;
  }

  public Path mergedCache() {
    return mergedCache;
  }

  public Path callgraphCache() {
    return callgraphCache;
  }

  public Path reportFindings() {
    return reportFindings;
  }

  public Path reportConsole() {
    return reportConsole;
  }

  /**
   * Create the directory tree (cache/, cache/programs/, report/) if it does not yet exist. Safe to
   * call repeatedly.
   */
  public void ensureDirectories() throws IOException {
    Files.createDirectories(programsDir);
    Files.createDirectories(reportDir);
  }

  /**
   * Cache file path for a single source file. The key is the SHA-1 of the canonical (normalized)
   * path so two scans over the same source tree produce identical filenames.
   */
  public Path programCacheFor(Path sourceFile) {
    String key = sha1(sourceFile.toAbsolutePath().normalize().toString());
    return programsDir.resolve(key + ".tac");
  }

  /** Stable hash of an arbitrary key (used for merged + call graph). */
  public static String sha1(String s) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      byte[] bytes = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-1 unavailable", e);
    }
  }
}
