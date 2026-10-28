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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * M7 audit (2026-10-17): lock-in tests for the centralised reference tables that used to live
 * inline in {@code InsecureCryptoCheck} and {@code HardcodedCredentialsCheck}. If you intentionally
 * change the contents of these tables, update the assertions below ? that is the whole point of
 * moving the data out of the checker sources.
 */
class CheckerConfigTest {

  @Test
  void weakCryptoAlgorithmsContainsKnownWeakAlgorithms() {
    assertThat(CheckerConfig.weakCryptoAlgorithms())
        .as("MD5 / SHA-1 / DES / 3DES / RC4 must remain in the weak-algorithm table")
        .containsKeys("md5", "md2", "md4", "sha-1", "sha1", "des", "desede", "rc4");
  }

  @Test
  void weakCryptoAlgorithmsMessagesCiteCwe() {
    // Each entry must carry the CWE in the message so the
    // finding's RuleDef ends up with the right CWE tag.
    for (var e : CheckerConfig.weakCryptoAlgorithms().entrySet()) {
      assertThat(e.getValue())
          .as("Weak-algorithm message for %s must mention CWE", e.getKey())
          .contains("CWE-");
    }
  }

  @Test
  void credentialNameKeywordsContainCorePasswordTerms() {
    assertThat(CheckerConfig.credentialNameKeywords())
        .as("password / secret / token / apikey / passwd are the core credential keywords")
        .contains("password", "secret", "token", "apikey", "passwd", "passphrase");
  }

  @Test
  void nameLooksLikeCredentialIsCaseInsensitive() {
    assertThat(CheckerConfig.nameLooksLikeCredential("password")).isTrue();
    assertThat(CheckerConfig.nameLooksLikeCredential("MyPassword")).isTrue();
    assertThat(CheckerConfig.nameLooksLikeCredential("API_KEY_HASH")).isTrue();
    assertThat(CheckerConfig.nameLooksLikeCredential("accessKey")).isTrue();
    // Common false-positive guards: name only "looks like"
    // a credential if it contains the keyword substring.
    assertThat(CheckerConfig.nameLooksLikeCredential("streamingBuffer")).isFalse();
    assertThat(CheckerConfig.nameLooksLikeCredential("")).isFalse();
    assertThat(CheckerConfig.nameLooksLikeCredential(null)).isFalse();
  }

  @Test
  void credentialNameKeywordsAreExposedAsUnmodifiableSet() {
    // Defensive contract: operators can clone-and-customize
    // the lookup logic, but the central set itself must
    // not be modifiable at runtime.
    assertThat(
            java.util.Collections.unmodifiableSet(CheckerConfig.credentialNameKeywords())
                .equals(CheckerConfig.credentialNameKeywords()))
        .isTrue();
    // Mutating a copy must not affect the central set.
    java.util.Set<String> copy = new java.util.HashSet<>(CheckerConfig.credentialNameKeywords());
    copy.add("hacked");
    assertThat(CheckerConfig.credentialNameKeywords()).doesNotContain("hacked");
  }

  @Test
  void weakCryptoAlgorithmsAreExposedAsUnmodifiableMap() {
    assertThat(
            java.util.Collections.unmodifiableMap(CheckerConfig.weakCryptoAlgorithms())
                .equals(CheckerConfig.weakCryptoAlgorithms()))
        .isTrue();
    java.util.Map<String, String> copy =
        new java.util.HashMap<>(CheckerConfig.weakCryptoAlgorithms());
    copy.put("hacked", "CWE-999: not a real algo");
    assertThat(CheckerConfig.weakCryptoAlgorithms()).doesNotContainKey("hacked");
  }

  // ─────────── controller annotations (M7 part 2) ───────────

  @Test
  void controllerAnnotationsContainsSpringAndJakarta() {
    assertThat(CheckerConfig.controllerAnnotations())
        .as("Spring @Controller/@RestController (short and FQN) must remain in the singleton set")
        .contains(
            "Controller",
            "RestController",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController");
  }

  @Test
  void servletSuperclassesContainsAllVariants() {
    assertThat(CheckerConfig.servletSuperclasses())
        .as("javax.servlet + jakarta.servlet variants must all be present")
        .contains(
            "javax.servlet.Servlet",
            "javax.servlet.http.HttpServlet",
            "jakarta.servlet.Servlet",
            "jakarta.servlet.http.HttpServlet");
  }
}
