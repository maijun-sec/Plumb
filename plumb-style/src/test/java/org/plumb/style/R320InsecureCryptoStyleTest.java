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
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.security.InsecureCryptoCheck;

/**
 * AST-side end-to-end tests for {@link InsecureCryptoCheck}. The DFA-side equivalent ( covers the
 * const-variable / parameter case.
 */
class R320InsecureCryptoStyleTest {

  private final JavaSourceParser parser = new JavaSourceParser();
  private final RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);

  private List<Finding> run(String src) {
    ParsedSource ps = parser.parseFromString(Path.of("T.java"), src);
    StyleContext ctx = new StyleContext(ps, registry);
    // R-816: use AstDispatcher so per-node visit overloads fire.
    new AstDispatcher().register(new InsecureCryptoCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void md5Literal_firesCwe328() {
    String src =
        """
        import java.security.MessageDigest;
        public class T {
        byte[] hash(String s) throws Exception {
        return MessageDigest.getInstance("MD5").digest(s.getBytes());
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    Finding f = findings.get(0);
    assertThat(f.message()).contains("md5");
    assertThat(f.rule().ruleId().id()).isEqualTo("ast-security-insecure-crypto");
  }

  @Test
  void rsaLiteral_firesCwe780() {
    String src =
        """
        import javax.crypto.Cipher;
        public class T {
        Cipher c() throws Exception {
        return Cipher.getInstance("RSA");
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("rsa");
  }

  @Test
  void desLiteral_firesCwe327() {
    String src =
        """
        import javax.crypto.Cipher;
        public class T {
        Cipher c() throws Exception {
        return Cipher.getInstance("DES");
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).message()).contains("des");
  }

  @Test
  void sha256_doesNotFire() {
    String src =
        """
        import java.security.MessageDigest;
        public class T {
        byte[] hash(String s) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(s.getBytes());
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).isEmpty();
  }

  @Test
  void aes_doesNotFire() {
    String src =
        """
        import javax.crypto.Cipher;
        public class T {
        Cipher c() throws Exception {
        return Cipher.getInstance("AES/GCM/NoPadding");
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).isEmpty();
  }

  @Test
  void sha1Lowercase_firesCwe328() {
    // The check normalizes to lowercase, so
    // both "SHA-1" and "sha1" are caught.
    String src =
        """
        import java.security.MessageDigest;
        public class T {
        byte[] hash(String s) throws Exception {
        return MessageDigest.getInstance("sha1").digest(s.getBytes());
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).hasSize(1);
  }

  @Test
  void constVariable_doesNotFireInStyle() {
    // The AST (style) half does NOT cover
    // the const-variable case : that's the
    // DFA half's job. This test pins the
    // split: style should NOT fire on
    // `getInstance(ALGO)` where ALGO is a
    // local variable (even if the literal
    // source has a known-weak value).
    String src =
        """
        import java.security.MessageDigest;
        public class T {
        byte[] hash(String s) throws Exception {
        String ALGO = "MD5";
        return MessageDigest.getInstance(ALGO).digest(s.getBytes());
        }
        }
        """;
    List<Finding> findings = run(src);
    assertThat(findings).isEmpty();
  }
}
