/*
 * R787h unit tests for AST-based source-reading batch
 * (3 cwe source-reading checkers migrated from plumb-dfa
 * to plumb-style).
 */
package org.plumb.style.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.StyleContext;

class R787hSourceReadingBatchTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse %s", src).isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
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

  // ---------- Cwe580 ObjectModelCheck ----------

  @Test
  void objectModel_cloneReturnsNewFires() {
    String src =
        "class T implements Cloneable {\n"
            + "  @Override\n"
            + "  public Object clone() {\n"
            + "    return new T();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(ObjectModelCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-cwe580-clone-without-super");
  }

  @Test
  void objectModel_cloneReturnsSuperSuppresses() {
    String src =
        "class T implements Cloneable {\n"
            + "  @Override\n"
            + "  public Object clone() throws CloneNotSupportedException {\n"
            + "    return super.clone();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(ObjectModelCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe491 ClassBasedIssueCheck ----------

  @Test
  void classBased_protectedCloneNotFinalFires() {
    String src =
        "class T implements Cloneable {\n"
            + "  @Override\n"
            + "  protected Object clone() throws CloneNotSupportedException {\n"
            + "    return super.clone();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(ClassBasedIssueCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-class-based-issue-491");
  }

  @Test
  void classBased_protectedCloneFinalSuppresses() {
    String src =
        "class T implements Cloneable {\n"
            + "  @Override\n"
            + "  protected final Object clone() throws CloneNotSupportedException {\n"
            + "    return super.clone();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(ClassBasedIssueCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Cwe690 NullDerefFromReturnCheck ----------

  @Test
  void nullDeref_assignThenUseNoCheckFires() {
    String src =
        "class T {\n"
            + "  String getName() { return null; }\n"
            + "  void bad() {\n"
            + "    String x = getName();\n"
            + "    int len = x.length();\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(NullDerefFromReturnCheck.class, src);
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).ruleId()).isEqualTo("ast-style-null-deref-from-return");
  }

  @Test
  void nullDeref_assignThenNullCheckSuppresses() {
    String src =
        "class T {\n"
            + "  String getName() { return null; }\n"
            + "  void good() {\n"
            + "    String x = getName();\n"
            + "    if (x != null) {\n"
            + "      int len = x.length();\n"
            + "    }\n"
            + "  }\n"
            + "}\n";
    List<Finding> findings = run(NullDerefFromReturnCheck.class, src);
    assertThat(findings).isEmpty();
  }

  // ---------- Rule id sanity ----------

  @Test
  void allThreeCheckersUseCorrectRuleIds() {
    assertThat(new ObjectModelCheck().name()).isEqualTo("cwe580-clone-without-super");
    assertThat(new ClassBasedIssueCheck().name()).isEqualTo("class-based-issue-491");
    assertThat(new NullDerefFromReturnCheck().name()).isEqualTo("null-deref-from-return");
  }
}
