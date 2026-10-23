package org.plumb.dfa.taint;

import java.util.List;

/**
 * Shared catalogue of "input source" call patterns used by the mirror checkers to mark a CALL's
 * dest as potentially-tainted.
 *
 * <p>Background. The {@link TaintedCheckerBase#initFunction} marks every function parameter as
 * "potentially tainted" so any taint that flows into the function via a parameter is caught. This
 * works for callers like {@code executeQuery("SELECT * FROM ... " + id)} where the SQL is built
 * from a parameter.
 *
 * <p>It does <em>not</em> work for local variables that are initialised from a call to a standard
 * library input function:
 *
 * <pre>
 *     BufferedReader br = new BufferedReader(
 *         new InputStreamReader(System.in));
 *     String line = br.readLine();
 *     int data = Integer.parseInt(line.trim());
 *     int result = data + 1;  // overflow
 * </pre>
 *
 * Here {@code data} is a local variable, not a parameter, so {@code initFunction} never marks it.
 * The mirror checkers (IntOverflow, DivByZero) catch this case by also marking the dest of an
 * "input-source" call (readLine, parseInt, etc.) as tainted.
 *
 * <p>This file centralises the list of such call patterns so the checkers don't drift out of sync.
 * Adding a new input source: add a new entry to {@link #DEFAULT_PATTERNS}; every checker that calls
 * {@link #isInputSourceCall} will pick it up automatically.
 */
public final class InputSourcePatterns {

  /**
   * The default set of substrings that identify an "input source" call. A callee signature matches
   * if any substring is contained in it. The callee signature format is {@code
   * ClassName.methodName/arity} (e.g. {@code "Byte.parseByte/1"}), so we use {@code .readLine} /
   * {@code .parseByte} etc. as the substring.
   */
  public static final List<String> DEFAULT_PATTERNS =
      List.of(
          ".readLine",
          ".read",
          ".parseByte",
          ".parseShort",
          ".parseInt",
          ".parseLong",
          ".parseDouble",
          ".parseFloat",
          "System.getenv",
          "System.getProperty",
          ".nextInt",
          ".nextLong",
          ".nextShort",
          ".nextByte",
          ".nextLine",
          ".nextFloat",
          ".nextDouble",
          // HTTP request sources (CWE-113,
          // CWE-79, CWE-601, CWE-918). These
          // match as substrings on the TAC
          // callee sig; e.g. "getParameter" is
          // contained in
          // "javax.servlet.http.HttpServletRequest
          //  .getParameter/1".
          "getParameter",
          "getParameterValues",
          "getHeader",
          "getHeaders",
          "getQueryString",
          "getCookies",
          "getRequestURI",
          "getRequestURL",
          "getPathInfo",
          "getMethod",
          "getInputStream",
          "getReader",
          "getInput",
          "getParameterMap",
          "getPart",
          "getParts",
          "getPathVariable",
          "getRequestParam",
          "getHeaderNames",
          // R693: JDBC ResultSet sources for the
          // Juliet CWE89 __database__ variants
          // (and any real-world app that does
          // "read row from DB → forward into SQL").
          // The ResultSet getter calls carry a
          // trust boundary because the database
          // content is attacker-influenced in
          // many deployment scenarios. Without
          // these entries the CWE89 database
          // variant misses silently — the call to
          // Statement.execute() never sees the
          // upstream taint.
          ".getString",
          ".getNString",
          ".getObject",
          ".getNCharacterStream",
          ".getCharacterStream",
          ".getAsciiStream",
          ".getUnicodeStream",
          ".getBinaryStream",
          ".getClob",
          ".getBlob",
          ".getArray",
          // More sources (network, env, file).
          "getRemoteAddr",
          "getRemoteHost",
          "getRemoteUser",
          "getServerName",
          "getRequestedSessionId",
          "InputStream.read",
          "FileInputStream.read",
          "Reader.read",
          "FileReader.read",
          "URLConnection.getInputStream",
          "DatagramSocket.receive",
          // Properties file / config source.
          // CWE-256 (Plaintext Storage of Password)
          // uses `password = properties.getProperty("password")`
          // which is the canonical file-sourced
          // credential pattern. Without this entry,
          // the DBCredentialSinkChecker never sees
          // the taint, because the taint framework
          // keys off InputSourcePatterns to seed the
          // dest of a CALL as "input:...".
          "Properties.getProperty",
          "Properties.get");

  private final List<String> patterns;

  public InputSourcePatterns(List<String> patterns) {
    this.patterns = List.copyOf(patterns);
  }

  public static InputSourcePatterns defaults() {
    return new InputSourcePatterns(DEFAULT_PATTERNS);
  }

  /**
   * Returns true if any pattern in this catalogue is a substring of the given callee signature. The
   * callee signature is the IR's {@code ClassName.methodName/arity} form.
   *
   * <p>Both the {@code ClassName.method} (dot) and the {@code ClassName#method} (hash) separators
   * are accepted: we normalise the callee by replacing every {@code #} with {@code .} before
   * matching, so a single pattern list (e.g. {@code ".readLine"}) matches both IR forms. This
   * avoids the silent miss that happens when the IR uses one separator and the pattern uses the
   * other.
   */
  public boolean matches(String callee) {
    if (callee == null) return false;
    String normalized = callee.replace('#', '.');
    for (String p : patterns) {
      if (normalized.contains(p)) return true;
    }
    return false;
  }

  /**
   * Static convenience: same as {@code defaults().matches(callee)}. Used by mirror checkers that
   * don't need to customise the pattern list.
   */
  public static boolean isInputSourceCall(String callee) {
    if (callee == null) return false;
    // Fast path: cache hit. The same
    // callee signature is checked by every
    // taint-style checker on every CALL
    // instruction, so the substring match
    // is hot. A ConcurrentHashMap keyed
    // on the normalized signature gives
    // O(1) repeat lookups.
    String normalized = callee.replace('#', '.');
    Boolean cached = inputSourceCache.get(normalized);
    if (cached != null) return cached;
    boolean match = false;
    for (String p : DEFAULT_PATTERNS) {
      if (normalized.contains(p)) {
        match = true;
        break;
      }
    }
    inputSourceCache.put(normalized, match);
    return match;
  }

  /**
   * Cache for {@link #isInputSourceCall}. Populated lazily on first call per signature; never
   * invalidated (DEFAULT_PATTERNS is a static final list ? the only way to add a new pattern is to
   * recompile).
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> inputSourceCache =
      new java.util.concurrent.ConcurrentHashMap<>();
}
