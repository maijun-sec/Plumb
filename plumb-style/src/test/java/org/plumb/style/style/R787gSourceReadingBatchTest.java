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
/*
 * R787g unit tests for AST-based source-reading batch
 * (5 cwe source-reading checkers migrated from plumb-dfa
 * to plumb-style).
 */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

class R787gSourceReadingBatchTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse %s", src).isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new java.util.ArrayList<>());
    return new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
  }

  private static List<Finding> run(Class<? extends org.plumb.style.AstChecker> klass, String src) {
    StyleContext ctx = ctxFor(src);
    try {
      // R-816: use AstDispatcher so per-node visit overloads
      // (Pattern D) fire — visit(CompilationUnit) is no longer
      // implemented on the rewritten checkers.
      org.plumb.style.AstChecker checker = klass.getDeclaredConstructor().newInstance();
      org.plumb.style.AstDispatcher dispatcher =
          new org.plumb.style.AstDispatcher().register(checker);
      dispatcher.run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ctx.findings();
  }

  // ---------- Cwe329 RandomIvCheck ----------

  @Test
  void randomIv_literalArrayFires() {
    String src =
        "import javax.crypto.spec.IvParameterSpec;\n"
            + "class T {\n"
            + "  void bad() {\n"
            + "    byte[] iv = {0x00, 0x00, 0x00, 0x00};\n"
            + "    new IvParameterSpec(iv);\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(RandomIvCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-random-iv-cbc");
  }

  @Test
  void randomIv_nextBytesBetweenSuppresses() {
    String src =
        "import javax.crypto.spec.IvParameterSpec;\n"
            + "import java.security.SecureRandom;\n"
            + "class T {\n"
            + "  void good() {\n"
            + "    SecureRandom r = new SecureRandom();\n"
            + "    byte[] iv = new byte[16];\n"
            + "    r.nextBytes(iv);\n"
            + "    new IvParameterSpec(iv);\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(RandomIvCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe759 UnsaltedOneWayHashCheck ----------

  @Test
  void unsaltedHash_digestWithoutUpdateFires() {
    String src =
        "import java.security.MessageDigest;\n"
            + "class T {\n"
            + "  byte[] bad() throws Exception {\n"
            + "    MessageDigest md = MessageDigest.getInstance(\"SHA-512\");\n"
            + "    return md.digest(\"hash me\".getBytes());\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UnsaltedOneWayHashCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-unsalted-one-way-hash");
  }

  @Test
  void unsaltedHash_updateBetweenSuppresses() {
    String src =
        "import java.security.MessageDigest;\n"
            + "import java.security.SecureRandom;\n"
            + "class T {\n"
            + "  byte[] good() throws Exception {\n"
            + "    MessageDigest md = MessageDigest.getInstance(\"SHA-512\");\n"
            + "    md.update(new SecureRandom().generateSeed(32));\n"
            + "    return md.digest(\"hash me\".getBytes());\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UnsaltedOneWayHashCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe523 UnprotectedCredTransportCheck ----------

  @Test
  void credTransport_httpFormFires() {
    String src =
        "class T {\n"
            + "  void bad(javax.servlet.http.HttpServletResponse r) throws Exception {\n"
            + "    r.getWriter().println(\"<form action='http://example.com/j_security_check'>\"\n"
            + "        + \"<input name='j_username'/><input name='j_password'"
            + " type='password'/></form>\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UnprotectedCredTransportCheck.class, src);
    assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-unprotected-cred-transport");
  }

  @Test
  void credTransport_httpsFormSuppresses() {
    String src =
        "class T {\n"
            + "  void good(javax.servlet.http.HttpServletResponse r) throws Exception {\n"
            + "    r.getWriter().println(\"<form action='https://example.com/j_security_check'>\"\n"
            + "        + \"<input name='j_username'/><input name='j_password'"
            + " type='password'/></form>\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UnprotectedCredTransportCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe600 UncaughtExceptionCheck ----------

  @Test
  void uncaughtException_riskyCallNoTryCatchFires() {
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    InetAddress addr = InetAddress.getByName(\"example.com\");\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UncaughtExceptionCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-cwe600-uncaught-exception");
  }

  @Test
  void uncaughtException_riskyCallWithTryCatchSuppresses() {
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void good() {\n"
            + "    try {\n"
            + "      InetAddress addr = InetAddress.getByName(\"example.com\");\n"
            + "    } catch (Exception e) { /* handle */ }\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(UncaughtExceptionCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe398 PoorCodeQualityCheck ----------

  @Test
  void poorCodeQuality_emptyBlockFires() {
    String src =
        "class T {\n"
            + "  void bad() {\n"
            + "    int x = 1;\n"
            + "    if (x > 0) {\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(PoorCodeQualityCheck.class, src);
    assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-poor-code-quality");
  }

  @Test
  void poorCodeQuality_emptyFunctionFires() {
    String src =
        "class T {\n"
            + "  public void doNothing() {\n"
            + "    // TODO: implement\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(PoorCodeQualityCheck.class, src);
    // BlockStmt empty (the comment is not a Statement), AND the
    // method body is also empty. Both should fire.
    assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
  }

  // ---------- Rule id sanity ----------

  @Test
  void allFiveCheckersUseCorrectRuleIds() {
    assertThat(new RandomIvCheck().name()).isEqualTo("random-iv-cbc");
    assertThat(new UnsaltedOneWayHashCheck().name()).isEqualTo("unsalted-one-way-hash");
    assertThat(new UnprotectedCredTransportCheck().name()).isEqualTo("unprotected-cred-transport");
    assertThat(new UncaughtExceptionCheck().name()).isEqualTo("cwe600-uncaught-exception");
    assertThat(new PoorCodeQualityCheck().name()).isEqualTo("poor-code-quality");
  }
}
