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
 *
 * <p><b>There is a second list, and this one does not own the whole answer.</b> The catalogue
 * declares eleven taint SOURCE families with anchored patterns naming the API that carries
 * untrusted data in ({@link SourceFamilyMatcher} reads them). The substring list below covers
 * sources the catalogue does not declare -- the numeric parsers above all -- so neither list
 * subsumes the other. {@link #matches(String, org.plumb.dfa.config.SinkFamilyRegistry,
 * org.plumb.tac.ir.SourceLanguage)} asks BOTH, and the answer is their union.
 *
 * <p>The union is deliberate. The catalogue's patterns are anchored, so they do not match {@code
 * Integer.parseInt}; the substring list has no owner, so it matches {@code
 * com.example.MyReader.read}. Neither replaces the other, and the false negatives a one-sided
 * answer produces are worse than the false positives a two-sided answer produces.
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
          // JDBC ResultSet sources for the
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
   * Whether the callee is an input source, asking BOTH the substring list and the catalogue.
   *
   * <p><b>Why the union and not a choice.</b> The two lists cover different APIs:
   *
   * <ul>
   *   <li>The substring list matches a method NAME anywhere, so it catches {@code Integer.parseInt}
   *       -- which the catalogue does not declare, and which matters, because a parsed number is
   *       still attacker-influenced.
   *   <li>The catalogue's patterns are ANCHORED, so they catch {@code java.net.URL.getHost} and
   *       {@code io.undertow.server.HttpServerExchange.getQueryParameters} -- which the substring
   *       list does not match at all.
   * </ul>
   *
   * <p>Neither list subsumes the other. Replacing the substring list with the catalogue would trade
   * eight false negatives for a set of false negatives of its own, and replacing the catalogue with
   * the substring list is what left the declared sources invisible in the first place.
   *
   * <p><b>Why the union is the safe direction.</b> A source the engine misses is a false NEGATIVE
   * -- a value read from an untrusted place that the engine calls clean, blinding every sink
   * downstream of it. A source the engine over-matches is a false POSITIVE. For a scanner, the
   * asymmetry favours the union: it costs precision and buys soundness.
   *
   * @param callee the callee signature
   * @param registry the catalogue, or {@code null} when there is none
   * @param language the language of the code being analysed
   * @return true when either list says this is an input source
   */
  public boolean matches(
      String callee,
      org.plumb.dfa.config.SinkFamilyRegistry registry,
      org.plumb.tac.ir.SourceLanguage language) {
    if (matches(callee)) return true;
    if (registry == null) return false;
    return registry.sourceFamilyMatcher().matches(callee, language);
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
   * invalidated (DEFAULT_PATTERNS is a static final list -- the only way to add a new pattern is to
   * recompile).
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> inputSourceCache =
      new java.util.concurrent.ConcurrentHashMap<>();
}
