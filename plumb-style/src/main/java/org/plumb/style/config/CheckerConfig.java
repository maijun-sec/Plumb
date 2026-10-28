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
package org.plumb.style.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central registry of configuration tables that {@link org.plumb.style.AstChecker} implementations
 * consult to decide whether a finding is warranted.
 *
 * <p><b>M7 audit (2026-10-17):</b> before this class existed, each checker hard-coded its keywords,
 * sanitiser names, and other reference data inline. Operators wanting to extend a checker (e.g. add
 * a new weak crypto algorithm to {@code InsecureCryptoCheck}'s blacklist, or a new credential
 * keyword to {@code HardcodedCredentialsCheck}'s name heuristic) had to find the source file and
 * edit it. {@link CheckerConfig} pulls those tables out of the checkers so they're visible in one
 * place and easy to override.
 *
 * <h2>Migration plan</h2>
 *
 * <ol>
 *   <li>Two checkers migrated in the M7 commit as proof-of-concept: {@link
 *       org.plumb.style.security.InsecureCryptoCheck} (algorithm blacklist) and {@link
 *       org.plumb.style.security.HardcodedCredentialsCheck} (credential-name keywords). The other
 *       ~591 checkers still hard-code their data and will be migrated as a follow-up.
 *   <li>Each table is a {@code Map.ofEntries} / {@link Set#of} constant (immutable). Operators can
 *       read them but cannot mutate at runtime.
 *   <li>If a future checker needs a configurable list, add the table here, then have the checker
 *       consult {@link #weakCryptoAlgorithms()} / {@link #credentialNameKeywords()} etc.
 * </ol>
 *
 * <p><b>Why this isn't tied to the rule-config system:</b> plumb-rules has its own parameter
 * mechanism ({@code RuleDef.parameters[]}) for per-rule overrides. These tables are different —
 * they're static reference data, not per-scan configuration. Forcing them through the
 * rule-parameter system would mean every scanner has to wire a parameter map for data that 99% of
 * operators never touch.
 */
public final class CheckerConfig {

  private CheckerConfig() {}

  // ─────────────────────────────────────────────────────────────────────────
  // 1. Weak cryptographic algorithms
  //
  // Source for the default set: NIST SP 800-131A (2024 revision) + IETF RFC 7465
  // (RC4 prohibition) + OWASP Cryptographic Storage Cheat Sheet. Map key is the
  // lowercase algorithm name passed to getInstance("..."); value is the canonical
  // "CWE + recommended-alternative" message that gets shown to the operator.
  //
  // Migrated from: org.plumb.style.security.InsecureCryptoCheck (was a private
  // static map inline).
  // ─────────────────────────────────────────────────────────────────────────

  public static final Map<String, String> WEAK_CRYPTO_ALGORITHMS;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    // RSA without OAEP — deterministic and malleable
    m.put(
        "rsa",
        "CWE-780: RSA without OAEP/PKCS1 padding is deterministic and malleable; "
            + "use RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
    m.put(
        "rsa/ecb/nopadding",
        "CWE-780: RSA/ECB/NoPadding is textbook RSA \u2014 never use for production data");
    m.put(
        "rsa/none/nopadding",
        "CWE-780: RSA/None/NoPadding is textbook RSA \u2014 never use for production data");
    // DES / 3DES / RC4 / Blowfish — short key or broken cipher
    m.put("des", "CWE-327: DES has a 56-bit key and is brute-forceable; use AES");
    m.put("desede", "CWE-327: 3DES (DESede) is deprecated; use AES");
    m.put("rc4", "CWE-327: RC4 has multiple biases and is broken; use AES");
    m.put(
        "blowfish",
        "CWE-327: Blowfish has a 64-bit block size and is weak for large data; use AES");
    // MD2/MD4/MD5/SHA-1 — broken for collision resistance
    m.put("md2", "CWE-328: MD2 is broken; use SHA-256");
    m.put("md4", "CWE-328: MD4 is broken; use SHA-256");
    m.put("md5", "CWE-328: MD5 is broken for collision resistance; use SHA-256");
    m.put("sha-1", "CWE-328: SHA-1 has known collision attacks; use SHA-256");
    m.put("sha1", "CWE-328: SHA-1 has known collision attacks; use SHA-256");
    WEAK_CRYPTO_ALGORITHMS = Map.copyOf(m);
  }

  public static Map<String, String> weakCryptoAlgorithms() {
    return WEAK_CRYPTO_ALGORITHMS;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 2. Credential-name keywords
  //
  // Variable / field names that suggest the field holds a credential. The check
  // is a substring match (case-insensitive) so it catches `password`, `pwd`,
  // `myPasswordHash`, etc. — but also false positives like `TokenStore` and
  // `AuthKeyGenerator`. Operators can extend this list in one place rather than
  // patching the checker's source.
  //
  // Migrated from: org.plumb.style.security.HardcodedCredentialsCheck (was a
  // private static method `looksLikeCredentialName`).
  // ─────────────────────────────────────────────────────────────────────────

  public static final Set<String> CREDENTIAL_NAME_KEYWORDS;

  static {
    Set<String> s = new LinkedHashSet<>();
    s.add("password");
    s.add("passwd");
    s.add("passphrase");
    s.add("secret");
    s.add("token");
    s.add("apikey");
    s.add("api_key");
    s.add("authkey");
    s.add("auth_key");
    s.add("accesskey");
    s.add("access_key");
    s.add("privatekey");
    s.add("private_key");
    CREDENTIAL_NAME_KEYWORDS = Set.copyOf(s);
  }

  public static Set<String> credentialNameKeywords() {
    return CREDENTIAL_NAME_KEYWORDS;
  }

  /**
   * Substring-match (case-insensitive) helper backed by {@link #CREDENTIAL_NAME_KEYWORDS}.
   * Equivalent to the previous inline {@code looksLikeCredentialName} helper.
   */
  public static boolean nameLooksLikeCredential(String name) {
    if (name == null || name.isEmpty()) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    for (String kw : CREDENTIAL_NAME_KEYWORDS) {
      if (lower.contains(kw)) return true;
    }
    return false;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 3. Spring / Jakarta controller annotations
  //
  // Used by SingletonRaceCheck (and ServletAtomicityCheck) to recognise Spring
  // MVC controllers and Jakarta servlets as singleton classes that need
  // thread-safety analysis. Migrated from the private static Set inside
  // SingletonRaceCheck.
  // ─────────────────────────────────────────────────────────────────────────

  public static final Set<String> CONTROLLER_ANNOTATIONS;

  static {
    Set<String> s = new LinkedHashSet<>();
    s.add("Controller");
    s.add("RestController");
    s.add("org.springframework.stereotype.Controller");
    s.add("org.springframework.web.bind.annotation.RestController");
    CONTROLLER_ANNOTATIONS = Set.copyOf(s);
  }

  public static Set<String> controllerAnnotations() {
    return CONTROLLER_ANNOTATIONS;
  }

  // ─────────────────────────────────────────────────────────────────────────
  // 4. Servlet / Jakarta servlet base classes
  //
  // Used by SingletonRaceCheck and ServletAtomicityCheck to recognise
  // servlet subclasses as singleton-ish classes that need thread-safety
  // analysis. Migrated from the private static Set inside SingletonRaceCheck.
  // ─────────────────────────────────────────────────────────────────────────

  public static final Set<String> SERVLET_SUPERCLASSES;

  static {
    Set<String> s = new LinkedHashSet<>();
    s.add("javax.servlet.Servlet");
    s.add("javax.servlet.http.HttpServlet");
    s.add("jakarta.servlet.Servlet");
    s.add("jakarta.servlet.http.HttpServlet");
    SERVLET_SUPERCLASSES = Set.copyOf(s);
  }

  public static Set<String> servletSuperclasses() {
    return SERVLET_SUPERCLASSES;
  }
}
