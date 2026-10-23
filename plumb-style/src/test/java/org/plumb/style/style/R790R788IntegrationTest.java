/* R788 integration test: AST checker's DEFAULT_RULE id is
 * `Cwe600`. R788 register adds canonical `plumb.style.cwe600-uncaught-exception`
 * with `Cwe600` as an alias. The StyleContext should resolve the alias
 * and emit a finding with rule_id = `plumb.style.cwe600-uncaught-exception`. */
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

class R790R788IntegrationTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse").isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
    // Use the registry as scan does, with R788 built-ins.
    RuleRegistry r = new RuleRegistry().withBuiltinDefaults(true);
    return new StyleContext(ps, r);
  }

  @Test
  void uncaughtExceptionEmitsCanonicalRuleId() {
    String src =
        "import java.net.InetAddress;\n"
            + "class T {\n"
            + "  void bad() throws Exception {\n"
            + "    InetAddress addr = InetAddress.getByName(\"localhost\");\n"
            + "  }\n"
            + "}\n";
    StyleContext ctx = ctxFor(src);
    try {
      // R-816: use AstDispatcher so per-node visit overloads fire.
      new org.plumb.style.AstDispatcher()
          .register(UncaughtExceptionCheck.class.getDeclaredConstructor().newInstance())
          .run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    List<Finding> findings = ctx.findings();
    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).rule().ruleId().id())
        .isEqualTo("ast-style-cwe600-uncaught-exception");
  }
}
