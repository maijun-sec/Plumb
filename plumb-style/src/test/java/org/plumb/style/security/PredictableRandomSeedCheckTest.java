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

class PredictableRandomSeedCheckTest {
  private final SecurityAstCheckTestHarness h =
      new SecurityAstCheckTestHarness(new PredictableRandomSeedCheck());

  @Test
  void randomCtorLiteral_fires() {
    List<Finding> f = h.run("class T { void m() { new java.util.Random(42); } }");
    assertThat(f).hasSize(1);
    assertThat(f.get(0).cwe()).isEqualTo("CWE-332");
  }

  @Test
  void secureRandomCtorLiteral_fires() {
    List<Finding> f = h.run("class T { void m() { new java.security.SecureRandom(12345L); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void randomNoCtor_doesNotFire() {
    // new Random() — no seed argument. Safe.
    List<Finding> f = h.run("class T { void m() { new java.util.Random(); } }");
    assertThat(f).isEmpty();
  }

  @Test
  void setSeedLiteral_fires() {
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "java.util.Random r = new java.util.Random(); "
                + "r.setSeed(42); } }");
    assertThat(f).hasSize(1);
  }

  @Test
  void setSeedVariable_doesNotFire() {
    List<Finding> f =
        h.run(
            "class T { void m() { "
                + "java.util.Random r = new java.util.Random(); "
                + "r.setSeed(System.currentTimeMillis()); } }");
    assertThat(f).isEmpty();
  }
}
