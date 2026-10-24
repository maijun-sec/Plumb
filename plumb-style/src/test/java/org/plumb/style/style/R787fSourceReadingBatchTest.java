/*
 * R787f unit tests for 5 plumb-style source-reading
 * AstCheckers migrated from plumb-dfa:
 *
 * 1. CommentInfoLeakCheck      (CWE-615)
 * 2. EnvVarInfoExposureCheck   (CWE-526)
 * 3. InfoExposureQueryStringCheck (CWE-598)
 * 4. InsufficientSessionExpirationCheck (CWE-613)
 * 5. MissingCryptoStepCheck    (CWE-325)
 *
 * All five were regex-based source-reading checkers in
 * dfa/. R787f replaces them with plumb-style AST
 * checks that walk the CompilationUnit.
 */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

class R787fSourceReadingBatchTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  /** Run all 6 checkers in one dispatch and return findings. */
  private static List<Finding> runAll(String name, String sourceText) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name), sourceText);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    AstDispatcher d =
        new AstDispatcher()
            .register(new CommentInfoLeakCheck())
            .register(new EnvVarInfoExposureCheck())
            // InfoExposureQueryStringCheck covers the CWE-89 SQL-string
            // heuristic; FormGetPasswordCheck covers the CWE-598
            // form-method=get + sensitive-field pattern. The smoke
            // tests for CWE-89 / CWE-598 expect findings under their
            // own ruleIds respectively.
            .register(new InfoExposureQueryStringCheck())
            .register(new FormGetPasswordCheck())
            .register(new InsufficientSessionExpirationCheck())
            .register(new MissingCryptoStepCheck());
    d.run(ctx);
    return ctx.findings();
  }

  private static long countByRuleId(List<Finding> findings, String ruleId) {
    return findings.stream().filter(f -> ruleId.equals(f.ruleId())).count();
  }

  // ─────── CWE-615 (CommentInfoLeak) ───────

  @Test
  void cwe615_passwordInCommentFires() {
    String src =
        "class Cwe615Pwd {\n"
            + "    /*\n"
            + "     * db password: P@ssw0rd123!\n"
            + "     */\n"
            + "    void m() {}\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe615Pwd.java", src), "ast-style-comment-info-leak"))
        .as("password mention in comment fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe615_awsKeyInCommentFires() {
    String src =
        "class Cwe615Aws {\n" + "    // AWS: AKIAIOSFODNN7EXAMPLE\n" + "    void m() {}\n" + "}\n";
    assertThat(countByRuleId(runAll("Cwe615Aws.java", src), "ast-style-comment-info-leak"))
        .as("AKIA-style access key in comment fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe615_noLeakInCommentDoesNotFire() {
    String src =
        "class Cwe615Ok {\n" + "    // add user to the system\n" + "    void m() {}\n" + "}\n";
    assertThat(countByRuleId(runAll("Cwe615Ok.java", src), "ast-style-comment-info-leak"))
        .as("non-secret comment does not fire")
        .isZero();
  }

  // ─────── CWE-526 (EnvVarInfoExposure) ───────

  @Test
  void cwe526_getenvInResponseWritesFires() {
    String src =
        "import java.io.IOException;\n"
            + "class Cwe526Bad {\n"
            + "    void m(javax.servlet.http.HttpServletResponse r) throws IOException {\n"
            + "        r.getWriter().println(\"PATH=\" + System.getenv(\"PATH\"));\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe526Bad.java", src), "ast-style-env-var-info-exposure"))
        .as("System.getenv piped to response writer fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe526_getenvWithoutWriterDoesNotFire() {
    String src =
        "class Cwe526Ok {\n"
            + "    void m() {\n"
            + "        String p = System.getenv(\"PATH\");\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe526Ok.java", src), "ast-style-env-var-info-exposure"))
        .as("getenv without writer does not fire")
        .isZero();
  }

  // ─────── CWE-598 (InfoExposureQueryString) ───────

  @Test
  void cwe598_formGetWithPasswordFieldFires() {
    // Form tag and sensitive field in the same
    // file, but on different lines (the dfa
    // regex matched this only because both were on
    // a single scanned line; the AST check matches
    // the file as a whole).
    String src =
        "class Cwe598Bad {\n"
            + "    void m(javax.servlet.http.HttpServletResponse r) {\n"
            + "        r.getWriter().println(\"<form method=\\\"get\\\">\");\n"
            + "        r.getWriter().println(\"<input name=\\\"password\\\">\");\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe598Bad.java", src), "ast-style-form-get-password"))
        .as("form method=get + password field in same file fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe598_postMethodDoesNotFire() {
    // The good1 form: same shape but method="post".
    String src =
        "class Cwe598Good1 {\n"
            + "    void m(javax.servlet.http.HttpServletResponse r) {\n"
            + "        r.getWriter().println(\"<form method=\\\"post\\\">\");\n"
            + "        r.getWriter().println(\"<input name=\\\"password\\\">\");\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe598Good1.java", src), "ast-style-form-get-password"))
        .as("form method=post does not fire")
        .isZero();
  }

  // ─────── CWE-613 (InsufficientSessionExpiration) ───────

  @Test
  void cwe613_negativeMaxInactiveFires() {
    String src =
        "import javax.servlet.http.HttpSession;\n"
            + "class Cwe613Bad {\n"
            + "    void m(HttpSession s) {\n"
            + "        s.setMaxInactiveInterval(-1);\n"
            + "    }\n"
            + "}\n";
    assertThat(
            countByRuleId(
                runAll("Cwe613Bad.java", src), "ast-style-insufficient-session-expiration"))
        .as("setMaxInactiveInterval(-1) fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe613_zeroMaxInactiveFires() {
    String src =
        "import javax.servlet.http.HttpSession;\n"
            + "class Cwe613Zero {\n"
            + "    void m(HttpSession s) {\n"
            + "        s.setMaxInactiveInterval(0);\n"
            + "    }\n"
            + "}\n";
    assertThat(
            countByRuleId(
                runAll("Cwe613Zero.java", src), "ast-style-insufficient-session-expiration"))
        .as("setMaxInactiveInterval(0) fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe613_positiveMaxInactiveDoesNotFire() {
    String src =
        "import javax.servlet.http.HttpSession;\n"
            + "class Cwe613Ok {\n"
            + "    void m(HttpSession s) {\n"
            + "        s.setMaxInactiveInterval(1800);\n"
            + "    }\n"
            + "}\n";
    assertThat(
            countByRuleId(
                runAll("Cwe613Ok.java", src), "ast-style-insufficient-session-expiration"))
        .as("setMaxInactiveInterval(1800) does not fire")
        .isZero();
  }

  // ─────── CWE-325 (MissingCryptoStep) ───────

  @Test
  void cwe325_cipherWithoutMacFires() {
    String src =
        "import javax.crypto.Cipher;\n"
            + "class Cwe325Bad {\n"
            + "    void m() throws Exception {\n"
            + "        Cipher c = Cipher.getInstance(\"AES\");\n"
            + "        c.init(Cipher.ENCRYPT_MODE, null);\n"
            + "        c.doFinal(new byte[0]);\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe325Bad.java", src), "ast-style-missing-crypto-step"))
        .as("Cipher use without Mac fires")
        .isEqualTo(1L);
  }

  @Test
  void cwe325_cipherWithMacDoesNotFire() {
    String src =
        "import javax.crypto.Cipher;\n"
            + "import javax.crypto.Mac;\n"
            + "class Cwe325Ok {\n"
            + "    void m() throws Exception {\n"
            + "        Cipher c = Cipher.getInstance(\"AES\");\n"
            + "        c.init(Cipher.ENCRYPT_MODE, null);\n"
            + "        c.doFinal(new byte[0]);\n"
            + "        Mac mac = Mac.getInstance(\"HmacSHA256\");\n"
            + "        mac.init(null);\n"
            + "        mac.doFinal(new byte[0]);\n"
            + "    }\n"
            + "}\n";
    assertThat(countByRuleId(runAll("Cwe325Ok.java", src), "ast-style-missing-crypto-step"))
        .as("Cipher with sibling Mac does not fire")
        .isZero();
  }

  // ─────── Style integration: rule id alignment ───────

  @Test
  void allFiveRulesFireUnderCweRuleId() {
    // Sanity check: each rule's findings carry the
    // canonical `Cwe<NNN>` rule id (Lesson 1154)
    // so eval hits the plumb_rule_to_cwe.json
    // mapping.
    String src =
        "import javax.crypto.Cipher;\n"
            + "import javax.servlet.http.HttpSession;\n"
            + "import javax.servlet.http.HttpServletResponse;\n"
            + "import java.io.IOException;\n"
            + "class R787fRuleIds {\n"
            + "    /* db password: P@ssw0rd! */\n"
            + "    void m(HttpSession s, HttpServletResponse r)\n"
            + "            throws IOException {\n"
            + "        s.setMaxInactiveInterval(-1);\n"
            + "        r.getWriter().println(\"PATH=\" + System.getenv(\"PATH\"));\n"
            + "        r.getWriter().println(\"<form method=\\\"get\\\">\");\n"
            + "        r.getWriter().println(\"<input name=\\\"password\\\">\");\n"
            + "    }\n"
            + "    void crypto() throws Exception {\n"
            + "        Cipher c = Cipher.getInstance(\"AES\");\n"
            + "        c.doFinal(new byte[0]);\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = runAll("R787fRuleIds.java", src);
    assertThat(findings.stream().anyMatch(f -> "ast-style-comment-info-leak".equals(f.ruleId())))
        .as("at least one Cwe615 finding")
        .isTrue();
    assertThat(
            findings.stream().anyMatch(f -> "ast-style-env-var-info-exposure".equals(f.ruleId())))
        .as("at least one Cwe526 finding")
        .isTrue();
    assertThat(findings.stream().anyMatch(f -> "ast-style-form-get-password".equals(f.ruleId())))
        .as("at least one Cwe598 finding (form method=get + password)")
        .isTrue();
    assertThat(
            findings.stream()
                .anyMatch(f -> "ast-style-insufficient-session-expiration".equals(f.ruleId())))
        .as("at least one Cwe613 finding")
        .isTrue();
    assertThat(findings.stream().anyMatch(f -> "ast-style-missing-crypto-step".equals(f.ruleId())))
        .as("at least one Cwe325 finding")
        .isTrue();
  }
}
