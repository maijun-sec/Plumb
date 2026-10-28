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
 * Tests for {@link InsecureCookieCheck}.
 *
 * <p>Covers: positive (fires), negative-with-secure (does not fire), boundary (similar but not
 * matching ? does not fire), unrelated (does not fire).
 */
class InsecureCookieCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new InsecureCookieCheck());

  @Test
  void addCookie_withoutSetSecure_fires() {
    List<Finding> f =
        h.run(
            "class T { void m(javax.servlet.http.HttpServletResponse r) { "
                + "javax.servlet.http.Cookie sessionCookie = "
                + "new javax.servlet.http.Cookie(\"sid\", \"abc\"); "
                + "r.addCookie(sessionCookie); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-614");
  }

  @Test
  void addCookie_afterSetSecure_doesNotFire() {
    List<Finding> f =
        h.run(
            "class T { void m(javax.servlet.http.HttpServletResponse r) { "
                + "javax.servlet.http.Cookie sessionCookie = "
                + "new javax.servlet.http.Cookie(\"sid\", \"abc\"); "
                + "sessionCookie.setSecure(true); "
                + "r.addCookie(sessionCookie); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void addNonCookieArg_doesNotFire() {
    // arg doesn't contain "cookie" — heuristic skips.
    List<Finding> f =
        h.run(
            "class T { void m(javax.servlet.http.HttpServletResponse r) { "
                + "Object payload = new Object(); "
                + "r.addCookie(payload); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void unrelatedMethodCall_doesNotFire() {
    // No addCookie at all.
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "javax.servlet.http.Cookie c = "
                + "new javax.servlet.http.Cookie(\"x\", \"y\"); "
                + "c.setSecure(true); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void arrayIndexArg_doesNotFire_whenEverMarkedSecure() {
    // addCookie(cookies[0]) where cookies[i] was
    // individually setSecure(true) — stripIndex
    // normalises both names to "cookies".
    List<Finding> f =
        h.run(
            "class T { void m(javax.servlet.http.HttpServletResponse r) { "
                + "javax.servlet.http.Cookie[] cookies = new "
                + "javax.servlet.http.Cookie[2]; "
                + "cookies[0] = new javax.servlet.http.Cookie(\"a\", \"b\"); "
                + "cookies[0].setSecure(true); "
                + "r.addCookie(cookies[0]); } }");
    assertThat(f).isEmpty();
  }
}
