package org.plumb.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.plumb.ai.agent.ToolResult;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * — Tests that the IR-aware tools (find_definitions, find_callers, has_sanitizer_on_path) fall back
 * to raw-text regex search when the {@code TacProgram} is {@code null}. The user said: "tools
 * shouldn't all bind to AST / TAC; the LLM should be able to look at the code even when IR is
 * unavailable."
 */
class IrfFallbackTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private Path tmpDir;

  @BeforeEach
  void setup() throws Exception {
    tmpDir = Files.createTempDirectory("IrfFallbackTest");
    Files.writeString(
        tmpDir.resolve("UserRepo.java"),
        "package x;\n"
            + "import java.sql.*;\n"
            + "class UserRepo {\n"
            + "  Connection conn;\n"
            + "  PreparedStatement ps;\n"
            + "  void findByName(String userInput) {\n"
            + "    String q = \"SELECT * FROM users WHERE name='\" + userInput + \"'\";\n"
            + "    ps.executeQuery(q);\n"
            + "  }\n"
            + "  void findByNameSafe(String userInput) {\n"
            + "    ps.setString(1, userInput);\n"
            + "    ps.executeQuery();\n"
            + "  }\n"
            + "}\n");
  }

  @AfterEach
  void teardown() throws Exception {
    if (tmpDir != null) {
      Files.walk(tmpDir)
          .sorted((a, b) -> b.toString().compareTo(a.toString()))
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignore) {
                }
              });
    }
  }

  @Test
  void findCallersFallsBackToGrepWhenNoTacProgram() {
    FindCallersTool tool = new FindCallersTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("method", "executeQuery");
    // ToolContext with program=null → grep fallback.
    ToolContext ctx =
        new ToolContext(
            null,
            "fp-reduction",
            tmpDir.toString(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null);
    ToolResult r = tool.execute(ctx, args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("executeQuery");
    assertThat(r.body()).contains("(grep fallback)");
  }

  @Test
  void findDefinitionsFallsBackToGrepWhenNoTacProgram() {
    FindDefinitionsTool tool = new FindDefinitionsTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("symbol", "userInput");
    args.put("kind", "var");
    ToolContext ctx =
        new ToolContext(
            null,
            "fp-reduction",
            tmpDir.toString(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null);
    ToolResult r = tool.execute(ctx, args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("userInput");
    assertThat(r.body()).contains("(grep fallback)");
  }

  @Test
  void hasSanitizerOnPathFallsBackToGrepWhenNoTacProgram() {
    // The "safe" method calls setString before
    // executeQuery. The grep fallback should find it.
    HasSanitizerOnPathTool tool = new HasSanitizerOnPathTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("var", "userInput");
    args.put("from", 10);
    args.put("to", 14);
    Finding f = finding(tmpDir.resolve("UserRepo.java"));
    ToolContext ctx =
        new ToolContext(
            f,
            "fp-reduction",
            tmpDir.toString(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null);
    ToolResult r = tool.execute(ctx, args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("setString");
    assertThat(r.body()).contains("(grep fallback)");
  }

  @Test
  void hasSanitizerOnPathGrepFindsNoSanitizerInUnsafeBlock() {
    // Lines 7-9 don't contain setString / escapeHtml /
    // any known sanitiser. Grep fallback should report
    // "no guard / sanitiser".
    HasSanitizerOnPathTool tool = new HasSanitizerOnPathTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("var", "q");
    args.put("from", 7);
    args.put("to", 9);
    Finding f = finding(tmpDir.resolve("UserRepo.java"));
    ToolContext ctx =
        new ToolContext(
            f,
            "fp-reduction",
            tmpDir.toString(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null);
    ToolResult r = tool.execute(ctx, args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("no guard");
  }

  @Test
  void hasSanitizerOnPathGrepFindsBranchGuard() throws Exception {
    // Line 4: "Connection conn;" — no branch there. We
    // construct a finding with a fake file that has
    // a branch on the var.
    Path file = tmpDir.resolve("Branchy.java");
    Files.writeString(
        file,
        "class B {\n"
            + "  void f(String x) {\n"
            + "    if (x != null) { use(x); }\n"
            + "  }\n"
            + "  void use(String s) {}\n"
            + "}\n");
    HasSanitizerOnPathTool tool = new HasSanitizerOnPathTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("var", "x");
    args.put("from", 1);
    args.put("to", 5);
    Finding f = finding(file);
    ToolContext ctx =
        new ToolContext(
            f,
            "fp-reduction",
            tmpDir.toString(),
            java.util.List.of(),
            java.util.List.of(),
            null,
            null,
            null);
    ToolResult r = tool.execute(ctx, args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("BRANCH");
    assertThat(r.body()).contains("x");
  }

  private static Finding finding(Path file) {
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
            "SQL injection",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            java.util.List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);
    return new Finding(file, 7, 4, rule, "msg", RuleDef.Severity.HIGH, "head");
  }
}
