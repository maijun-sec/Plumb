/*
 * R787e unit tests for plumb-style
 * DoubleCheckedLockingCheck (CWE-609).
 *
 * <p>Mirrors the Juliet CWE-609__Thread_01 pattern:
 * the {@code helperBad()} method (lines 20-33) has the
 * classic dcl shape and the target field
 * {@code stringBad} (line 16) is non-volatile. The
 * {@code helperGood1()} (lines 68-81) adds {@code volatile}
 * to {@code stringGood1} (line 66) and should NOT
 * fire.</p>
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

class R787eDoubleCheckedLockingCheckTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private static List<Finding> run(String name, String sourceText) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of(name), sourceText);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    AstDispatcher d = new AstDispatcher().register(new DoubleCheckedLockingCheck());
    d.run(ctx);
    return ctx.findings();
  }

  private static long countCwe609(List<Finding> findings) {
    return findings.stream()
        .filter(f -> "ast-style-double-checked-locking".equals(f.ruleId()))
        .count();
  }

  // ─────── Juliet canonical form (R787e) ───────

  @Test
  void julietBadFormFires() {
    // Mirrors Juliet CWE609__Thread_01 (simplified to
    // a single test method for unit-test simplicity).
    // The pattern is the classic dcl anti-pattern.
    String src =
        "class Cwe609Bad {\n"
            + "    private static String stringBad = null;\n"
            + "    public static String helperBad() {\n"
            + "        if (stringBad == null) {\n"
            + "            synchronized(Cwe609Bad.class) {\n"
            + "                if (stringBad == null) {\n"
            + "                    stringBad = \"value\";\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "        return stringBad;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609Bad.java", src);
    assertThat(countCwe609(findings)).as("classic dcl on non-volatile field fires").isEqualTo(1L);
  }

  @Test
  void volatileFieldSuppressesFinding() {
    // Mirrors Juliet CWE609__Thread_01 good1():
    // `volatile` modifier on the field makes the
    // pattern correct.
    String src =
        "class Cwe609Good1 {\n"
            + "    private volatile static String stringGood1 = null;\n"
            + "    public static String helperGood1() {\n"
            + "        if (stringGood1 == null) {\n"
            + "            synchronized(Cwe609Good1.class) {\n"
            + "                if (stringGood1 == null) {\n"
            + "                    stringGood1 = \"value\";\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "        return stringGood1;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609Good1.java", src);
    assertThat(countCwe609(findings))
        .as("volatile field suppresses finding (regex fired, AST correctly suppresses)")
        .isZero();
  }

  @Test
  void localVariableDclDoesNotFire() {
    // Local variables are not affected by the JMM
    // publication issue — they live on the stack,
    // which is thread-safe. The check requires the
    // target to be a FieldDeclaration.
    String src =
        "class Cwe609Local {\n"
            + "    public static String helper() {\n"
            + "        String x = null;\n"
            + "        if (x == null) {\n"
            + "            synchronized(Cwe609Local.class) {\n"
            + "                if (x == null) {\n"
            + "                    x = \"value\";\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609Local.java", src);
    assertThat(countCwe609(findings)).as("local variable dcl: not a JMM issue, no fire").isZero();
  }

  @Test
  void fullySynchronizedDoesNotFire() {
    // Juliet good3() form: entire body inside one
    // synchronized block, not the dcl shape. The
    // AST check requires the nested-null-check
    // structure, which this doesn't have.
    String src =
        "class Cwe609Good3 {\n"
            + "    private static String stringGood3 = null;\n"
            + "    public static String helper() {\n"
            + "        synchronized(Cwe609Good3.class) {\n"
            + "            if (stringGood3 == null) {\n"
            + "                stringGood3 = \"value\";\n"
            + "            }\n"
            + "        }\n"
            + "        return stringGood3;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609Good3.java", src);
    assertThat(countCwe609(findings))
        .as("fully-synchronized block is not dcl shape → no fire")
        .isZero();
  }

  @Test
  void methodSynchronizedDoesNotFire() {
    // Juliet good2() form: synchronized modifier on
    // the method itself. The AST check requires
    // synchronized *statement*, not the modifier.
    String src =
        "class Cwe609Good2 {\n"
            + "    private static String stringGood2 = null;\n"
            + "    public synchronized static String helper() {\n"
            + "        if (stringGood2 == null) {\n"
            + "            stringGood2 = \"value\";\n"
            + "        }\n"
            + "        return stringGood2;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609Good2.java", src);
    assertThat(countCwe609(findings))
        .as("method-level synchronized is not dcl shape → no fire")
        .isZero();
  }

  @Test
  void noNullCheckAtAllDoesNotFire() {
    // Just a synchronized block, no null check.
    // The AST check requires the outer
    // `if (X == null)` shape.
    String src =
        "class Cwe609NoNull {\n"
            + "    private static String x = null;\n"
            + "    public static void helper() {\n"
            + "        if (true) {\n"
            + "            synchronized(Cwe609NoNull.class) {\n"
            + "                x = \"value\";\n"
            + "            }\n"
            + "        }\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609NoNull.java", src);
    assertThat(countCwe609(findings)).as("no null check → no fire").isZero();
  }

  // ─────── Style integration: fires under plumb-style rule id ───────

  @Test
  void firesWithCwe609RuleId() {
    // Lesson 1154: rule_id must align with
    // plumb_rule_to_cwe.json so eval hits
    // Cwe609 → ["609"].
    String src =
        "class Cwe609RuleId {\n"
            + "    private static String x = null;\n"
            + "    public static String helper() {\n"
            + "        if (x == null) {\n"
            + "            synchronized(Cwe609RuleId.class) {\n"
            + "                if (x == null) {\n"
            + "                    x = \"value\";\n"
            + "                }\n"
            + "            }\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";
    List<Finding> findings = run("Cwe609RuleId.java", src);
    assertThat(findings).isNotEmpty();
    Finding f = findings.get(0);
    assertThat(f.ruleId()).isEqualTo("ast-style-double-checked-locking");
    assertThat(f.rule()).isNotNull();
    assertThat(f.rule().cwe()).containsExactly("CWE-609");
  }
}
