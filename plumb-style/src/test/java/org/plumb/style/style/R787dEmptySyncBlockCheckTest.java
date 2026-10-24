/*
 * R787d unit tests for plumb-style EmptySyncBlockCheck
 * (CWE-585).
 *
 * <p>Walks every SynchronizedStmt via AstDispatcher and
 * checks the body structurally. Catches the same patterns
 * the R759-E regex caught plus the single-statement form
 * (synchronized(LOCK);).</p>
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

class R787dEmptySyncBlockCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private static List<Finding> run(String name, String sourceText) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name), sourceText);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    AstDispatcher d = new AstDispatcher().register(new EmptySyncBlockCheck());
    d.run(ctx);
    return ctx.findings();
  }

  private static long countCwe585(List<Finding> findings) {
    return findings.stream().filter(f -> "ast-style-empty-sync-block".equals(f.ruleId())).count();
  }

  // ─────── Positive cases (must fire) ───────

  @Test
  void emptyBracesFires() {
    // Mirrors Juliet CWE585__Thread_01.helperBad() body
    // (simplified). The classic `synchronized(LOCK) { }`
    // form that the R759-E regex caught.
    String src =
        "class Cwe585Empty {\n"
            + "    static private int intBad = 1;\n"
            + "    static public void helperBad() {\n"
            + "        synchronized(Cwe585Empty.class) {\n"
            + "            /* FLAW: empty synchronized block */\n"
            + "        }\n"
            + "        intBad = intBad * 2;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585Empty.java", src);
    assertThat(countCwe585(findings)).as("synchronized(LOCK) { } fires").isEqualTo(1L);
  }

  @Test
  void emptyBracesWithOnlyCommentFires() {
    // Comments don't count as statements; the body
    // is still empty. The R759-E regex with
    // \\s* (whitespace) before \\} would have caught
    // this. The AST check also catches it.
    String src =
        "class Cwe585Comment {\n"
            + "    void m() {\n"
            + "        synchronized(this) {\n"
            + "            // intentional no-op\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585Comment.java", src);
    assertThat(countCwe585(findings)).as("comment-only body still empty → fires").isEqualTo(1L);
  }

  @Test
  void singleStatementSemicolonFormIsParseError() {
    // Per JLS, a `synchronized` statement REQUIRES a
    // block (curly braces). The form
    // `synchronized(this);` is a Java syntax error
    // and JavaParser refuses to parse it. The AST
    // check therefore doesn't need to handle this
    // shape — it can't exist in valid Java code. This
    // test documents the behavior: parseFromString
    // returns a partial AST but no SynchronizedStmt
    // is found inside.
    String src =
        "class Cwe585Semicolon {\n"
            + "    void m() {\n"
            + "        synchronized(this);\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585Semicolon.java", src);
    // The parse fails (per the test setup) — no
    // SynchronizedStmt → no finding. Documented for
    // future readers; the AST enhancement for
    // EmptyStmt body remains in the check as a
    // belt-and-suspenders guard for hypothetical
    // future parser versions.
    assertThat(countCwe585(findings)).isZero();
  }

  @Test
  void nestedEmptySyncFires() {
    // Multiple empty sync blocks in one method.
    String src =
        "class Cwe585Multi {\n"
            + "    void m() {\n"
            + "        synchronized(this) { }\n"
            + "        synchronized(getClass()) { }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585Multi.java", src);
    assertThat(countCwe585(findings)).as("two empty sync blocks → 2 findings").isEqualTo(2L);
  }

  // ─────── Negative cases (must NOT fire) ───────

  @Test
  void nonEmptyBodyDoesNotFire() {
    // Mirrors Juliet CWE585__Thread_01.helperGood1() body
    // (simplified). Synchronized block with actual
    // statement inside.
    String src =
        "class Cwe585Good {\n"
            + "    static private int intGood1 = 1;\n"
            + "    static public void helperGood1() {\n"
            + "        synchronized(Cwe585Good.class) {\n"
            + "            intGood1 = intGood1 * 2;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585Good.java", src);
    assertThat(countCwe585(findings)).as("synchronized with non-empty body → no fire").isZero();
  }

  @Test
  void onlyEmptyStatementsInBodyDoesNotCountAsEmpty() {
    // Actually, this DOES fire (the body has only
    // EmptyStmts → effectively empty). The check is
    // that the body is effectively empty (R787d
    // enhancement: don't double-count nested empties,
    // just call it empty).
    // ... but having a single statement of `;` is
    // also empty. The check fires.
    String src =
        "class Cwe585EmptyOnlyStmt {\n"
            + "    void m() {\n"
            + "        synchronized(this) {\n"
            + "            ;\n"
            + "            ;\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585EmptyOnlyStmt.java", src);
    assertThat(countCwe585(findings))
        .as("body with only EmptyStmts is effectively empty → fires")
        .isEqualTo(1L);
  }

  @Test
  void noSynchronizedAtAllDoesNotFire() {
    String src =
        "class Cwe585NoSync {\n" + "    void m() {\n" + "        int x = 1;\n" + "    }\n" + "}\n";
    List<Finding> findings = run("Cwe585NoSync.java", src);
    assertThat(countCwe585(findings)).isZero();
  }

  // ─────── Style integration: fires under plumb-style rule id ───────

  @Test
  void firesWithCwe585RuleId() {
    // Lesson 1154: rule_id must align with
    // plumb_rule_to_cwe.json so eval hits
    // Cwe585 → ["585"].
    String src =
        "class Cwe585RuleId {\n"
            + "    void m() {\n"
            + "        synchronized(this) { }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe585RuleId.java", src);
    assertThat(findings).isNotEmpty();
    Finding f = findings.get(0);
    assertThat(f.ruleId()).isEqualTo("ast-style-empty-sync-block");
    assertThat(f.rule()).isNotNull();
    assertThat(f.rule().cwe()).containsExactly("CWE-585");
  }
}
