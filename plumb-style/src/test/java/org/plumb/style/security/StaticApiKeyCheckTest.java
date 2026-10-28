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
package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;

/**
 * Tests for {@link StaticApiKeyCheck}.
 *
 * <p>Covers: positive (fires), negative-no-final (does not fire), boundary-too-short (does not
 * fire), boundary-placeholder (does not fire), unrelated (does not fire).
 */
class StaticApiKeyCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new StaticApiKeyCheck());

  @Test
  void staticFinalSecretLiteral_fires() {
    List<Finding> f = h.run("class T { static final String API_KEY = \"sk_live_abcdef12345\"; }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).contains("CWE-798");
  }

  @Test
  void staticFinalNonCredentialName_doesNotFire() {
    List<Finding> f = h.run("class T { static final String FOO = \"value12345\"; }");
    assertThat(f).isEmpty();
  }

  @Test
  void notFinal_doesNotFire() {
    // mutable static — same credential name but not final,
    // so not the "static final" subset this rule owns.
    List<Finding> f = h.run("class T { static String apiKey = \"sk_live_abcdef12345\"; }");
    assertThat(f).isEmpty();
  }

  @Test
  void tooShortLiteral_doesNotFire() {
    List<Finding> f = h.run("class T { static final String API_KEY = \"sk\"; }");
    // length 2 < 4 threshold
    assertThat(f).isEmpty();
  }

  @Test
  void placeholderLiteral_doesNotFire() {
    List<Finding> f = h.run("class T { static final String API_KEY = \"your-api-key\"; }");
    assertThat(f).isEmpty();
  }
}
