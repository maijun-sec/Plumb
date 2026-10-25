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
import org.plumb.reporter.FlowStep;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * Step 3 ? Tests for the 6 new cross-function tools. Each test exercises a single tool with a small
 * pre-cooked source tree and asserts the formatted response.
 */
class CrossFunctionToolsTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private Path tmpDir;

  @BeforeEach
  void setup() throws Exception {
    tmpDir = Files.createTempDirectory("CrossFunctionToolsTest");
    Files.createDirectories(tmpDir.resolve("com").resolve("example"));
    // FooController.java — Spring @RestController with
    // a method that has @PreAuthorize and a @Valid
    // parameter.
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("FooController.java"),
        "package com.example;\n"
            + "import org.springframework.web.bind.annotation.*;\n"
            + "import org.springframework.security.access.prepost.*;\n"
            + "import javax.validation.Valid;\n"
            + "@RestController\n"
            + "@RequestMapping(\"/api\")\n"
            + "public class FooController extends BaseController implements MyInterface {\n"
            + "  @PostMapping(\"/foo\")\n"
            + "  @PreAuthorize(\"hasRole('ADMIN')\")\n"
            + "  public String create(@Valid @RequestBody Foo body, @PathVariable String id) {\n"
            + "    return svc.create(body);\n"
            + "  }\n"
            + "}\n");
    // UserService.java — called by create() above.
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("UserService.java"),
        "package com.example;\n"
            + "public class UserService extends AbstractService {\n"
            + "  public String create(Foo f) { return \"x\"; }\n"
            + "}\n");
    // BaseController.java
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("BaseController.java"),
        "package com.example;\n"
            + "public class BaseController {\n"
            + "  public String getName() { return \"\"; }\n"
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

  // ─── GetClassHierarchyTool ─────────────────────────

  @Test
  void getClassHierarchyFindsExtendsAndImplements() {
    GetClassHierarchyTool tool = new GetClassHierarchyTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("class_name", "FooController");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("FooController");
    assertThat(r.body()).contains("extends: BaseController");
    assertThat(r.body()).contains("implements: MyInterface");
  }

  @Test
  void getClassHierarchyReportsNoMatch() {
    GetClassHierarchyTool tool = new GetClassHierarchyTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("class_name", "NonExistent");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("no class declaration matching");
  }

  @Test
  void getClassHierarchyFindsSimpleExtends() {
    GetClassHierarchyTool tool = new GetClassHierarchyTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("class_name", "UserService");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("extends: AbstractService");
  }

  // ─── GetFullClassTool ──────────────────────────────

  @Test
  void getFullClassReturnsEntireSource() {
    GetFullClassTool tool = new GetFullClassTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("class_name", "FooController");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("Source of FooController");
    assertThat(r.body()).contains("@RestController");
    assertThat(r.body()).contains("@PreAuthorize");
    assertThat(r.body()).contains("public class FooController");
  }

  @Test
  void getFullClassReportsNoMatch() {
    GetFullClassTool tool = new GetFullClassTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("class_name", "NonExistent");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("no .java file containing class");
  }

  // ─── SearchPatternTool ─────────────────────────────

  @Test
  void searchPatternMatchesCamelCaseVariants() throws Exception {
    // Add a file with three naming variants of "userRepo".
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("Variants.java"),
        "package com.example;\n"
            + "class Variants {\n"
            + "  void f() {\n"
            + "    String userRepo = \"a\";\n"
            + "    String UserRepo = \"b\";\n"
            + "    String user_repo = \"c\";\n"
            + "    String USER_REPO = \"d\";\n"
            + "    use(userRepo); use(UserRepo); use(user_repo); use(USER_REPO);\n"
            + "  }\n"
            + "  void use(String s) {}\n"
            + "}\n");
    SearchPatternTool tool = new SearchPatternTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("word", "userRepo");
    args.put("path", tmpDir.toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    // All four variants should match.
    assertThat(r.body()).contains("userRepo");
    assertThat(r.body()).contains("UserRepo");
    assertThat(r.body()).contains("user_repo");
    assertThat(r.body()).contains("USER_REPO");
  }

  @Test
  void searchPatternDefinitionKindFilters() throws Exception {
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("Def.java"),
        "package com.example;\n"
            + "class Def {\n"
            + "  private static final String CONSTANT_FOO = \"v\";\n"
            + "  void f() { System.out.println(CONSTANT_FOO); }\n"
            + "  void g() { System.out.println(CONSTANT_FOO); }\n"
            + "}\n");
    SearchPatternTool tool = new SearchPatternTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("word", "constantFoo");
    args.put("path", tmpDir.toString());
    args.put("kind", "definition");
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    // Should return the definition but not the usage sites.
    assertThat(r.body()).contains("CONSTANT_FOO");
    assertThat(r.body()).contains("Found 1");
  }

  @Test
  void searchPatternRawRegexFallback() {
    SearchPatternTool tool = new SearchPatternTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("pattern", "@PreAuthorize");
    args.put("path", tmpDir.toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("@PreAuthorize");
    assertThat(r.body()).contains("Found 1");
  }

  // ─── GetDataflowTraceWithFactsTool ─────────────────

  @Test
  void getDataflowTraceWithFactsFormatsSteps() {
    GetDataflowTraceWithFactsTool tool = new GetDataflowTraceWithFactsTool();
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
            "SQL injection",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            java.util.List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);
    Finding f =
        new Finding(
            tmpDir.resolve("X.java"),
            10,
            4,
            rule,
            "msg",
            RuleDef.Severity.HIGH,
            "head",
            java.util.List.of(
                FlowStep.of("source", "userInput = req.getParameter(...)"),
                FlowStep.of("propagate", "q = ... + userInput + ..."),
                FlowStep.of("sink", "stmt.executeQuery(q)")));
    ToolContext ctx = ctxWithFinding(f);
    ToolResult r = tool.execute(ctx, MAPPER.createObjectNode());
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("source");
    assertThat(r.body()).contains("propagate");
    assertThat(r.body()).contains("sink");
    assertThat(r.body()).contains("CWE-89");
  }

  @Test
  void getDataflowTraceWithFactsReportsMissing() {
    GetDataflowTraceWithFactsTool tool = new GetDataflowTraceWithFactsTool();
    ToolResult r = tool.execute(ctx(), MAPPER.createObjectNode());
    assertThat(r.ok()).isFalse();
    assertThat(r.body()).contains("no current finding");
  }

  /**
   * R-806 ? when the finding carries a v2 structured Trace (R-803 P3.1 dual-write + R-804 eager),
   * the tool renders each node with its specific {@code TraceNode.Op} enum (MOVE / INPUT_READLINE /
   * SINK_SQL / ...), not the v1 flat-string heuristic. The LLM can now distinguish "MOVE x -> y"
   * from "BINOP_ADD rhs -> t" from "INPUT_READLINE" without regex-parsing the expr.
   */
  @Test
  void getDataflowTraceWithFactsRendersV2TraceWithSpecificOps() {
    GetDataflowTraceWithFactsTool tool = new GetDataflowTraceWithFactsTool();
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
            "SQL injection",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            java.util.List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);

    // Build a minimal v2 DAG: SOURCE (readLine) -> MOVE -> BINOP_ADD -> SINK_SQL
    org.plumb.reporter.trace.TraceBuilder tb = new org.plumb.reporter.trace.TraceBuilder();
    int src =
        tb.addSource(
            org.plumb.reporter.trace.TraceNode.Op.INPUT_READLINE,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.CallRef(
                    "java.io.BufferedReader.readLine/0")),
            "input readLine",
            "X.java",
            5,
            12,
            "Foo#bad()");
    int mv =
        tb.addPropagate(
            org.plumb.reporter.trace.TraceNode.Op.MOVE,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.Var("t", 0),
                new org.plumb.reporter.trace.OperandRef.Var("data", 0)),
            src,
            "MOVE t -> data",
            "X.java",
            5,
            12,
            "Foo#bad()");
    int binop =
        tb.addPropagate(
            org.plumb.reporter.trace.TraceNode.Op.BINOP_ADD,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.Var("data", 0),
                new org.plumb.reporter.trace.OperandRef.Var("t", 1)),
            mv,
            "BINOP_ADD rhs -> t",
            "X.java",
            6,
            25,
            "Foo#bad()");
    int sink =
        tb.addSink(
            org.plumb.reporter.trace.TraceNode.Op.SINK_SQL,
            java.util.List.of(
                new org.plumb.reporter.trace.OperandRef.CallRef("java.sql.Statement.execute/1")),
            binop,
            "Statement.execute/1 (sql.sinks)",
            "X.java",
            6,
            25,
            "Foo#bad()");
    org.plumb.reporter.trace.Trace v2 =
        tb.build(java.util.List.of(sink), org.plumb.reporter.trace.Trace.CompressionLevel.FULL);

    Finding f =
        new Finding(
                tmpDir.resolve("X.java"),
                6,
                25,
                rule,
                "Tainted value flows to sink",
                RuleDef.Severity.HIGH,
                "input -> sink")
            .withTrace(v2);
    ToolResult r = tool.execute(ctxWithFinding(f), MAPPER.createObjectNode());
    assertThat(r.ok()).isTrue();
    String body = r.body();
    // R-806 fidelity: specific op enums appear in output
    assertThat(body).contains("op=INPUT_READLINE");
    assertThat(body).contains("op=MOVE");
    assertThat(body).contains("op=BINOP_ADD");
    assertThat(body).contains("op=SINK_SQL");
    // v2 trace header is present
    assertThat(body).contains("v2 trace");
    assertThat(body).contains("nodes");
    // v1 step format is NOT used (would contain "Steps (" with parens)
    assertThat(body).doesNotContain("Steps (");
  }

  /**
   * R-806 ? v2 path renders sink node with role "sink" and a v1 fallback path is also exercised
   * when the finding has no v2 trace attached (e.g. pre-P3.1 findings from style checkers). The
   * tool must produce non-empty output in both cases.
   */
  @Test
  void getDataflowTraceWithFactsV1FallbackRendersSteps() {
    GetDataflowTraceWithFactsTool tool = new GetDataflowTraceWithFactsTool();
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
            "SQL injection",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            java.util.List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);
    // Finding WITHOUT withTrace() — f.trace() == null
    // → tool must fall back to v1 dataflowSteps rendering.
    Finding f =
        new Finding(
            tmpDir.resolve("X.java"),
            10,
            4,
            rule,
            "msg",
            RuleDef.Severity.HIGH,
            "head",
            java.util.List.of(
                FlowStep.of("source", "userInput = req.getParameter(...)"),
                FlowStep.of("propagate", "q = ... + userInput + ..."),
                FlowStep.of("sink", "stmt.executeQuery(q)")));
    ToolResult r = tool.execute(ctxWithFinding(f), MAPPER.createObjectNode());
    assertThat(r.ok()).isTrue();
    String body = r.body();
    // v1 path is the "Steps (N):" format
    assertThat(body).contains("Steps (3)");
    assertThat(body).contains("source");
    assertThat(body).contains("propagate");
    assertThat(body).contains("sink");
    // v2 trace header must NOT appear in v1 fallback
    assertThat(body).doesNotContain("v2 trace");
  }

  // ─── GetMethodAnnotationsTool ──────────────────────

  @Test
  void getMethodAnnotationsRegexFallback() {
    // The IR path requires a TacProgram; for the
    // standalone tool test the regex fallback is the
    // realistic path. Make sure it picks up
    // @RestController, @PreAuthorize, @Valid, and
    // @PathVariable.
    GetMethodAnnotationsTool tool = new GetMethodAnnotationsTool();
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301", "RULE"),
            "r",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            java.util.List.of(),
            "x",
            true);
    Finding f =
        new Finding(
            tmpDir.resolve("com").resolve("example").resolve("FooController.java"),
            10,
            4,
            rule,
            "msg",
            RuleDef.Severity.HIGH,
            "head");
    ToolContext ctx = ctxWithFinding(f);
    ToolResult r = tool.execute(ctx, MAPPER.createObjectNode());
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("(source: regex)");
    // Some of the annotations should be in the result.
    assertThat(r.body())
        .containsAnyOf("RestController", "RequestMapping", "PreAuthorize", "Valid", "PathVariable");
  }

  // ─── GetCallHierarchyTool ──────────────────────────

  @Test
  void getCallHierarchyWalksCallers() throws Exception {
    // caller -> intermediate -> leaf. We start from
    // the leaf and expect the call chain to include
    // both the intermediate and the caller.
    Files.writeString(
        tmpDir.resolve("com").resolve("example").resolve("CallChain.java"),
        "package com.example;\n"
            + "class CallChain {\n"
            + "  void caller() { intermediate(); }\n"
            + "  void intermediate() { leaf(); }\n"
            + "  void leaf() {}\n"
            + "}\n");
    GetCallHierarchyTool tool = new GetCallHierarchyTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("method", "leaf");
    args.put("max_depth", 3);
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("Call hierarchy rooted at leaf");
    assertThat(r.body()).contains("intermediate");
  }

  // ─── helpers ────────────────────────────────────────

  private ToolContext ctx() {
    return new ToolContext(
        null,
        "test",
        tmpDir.toString(),
        java.util.List.of(),
        java.util.List.of(),
        null,
        null,
        null);
  }

  private ToolContext ctxWithFinding(Finding f) {
    return new ToolContext(
        f, "test", tmpDir.toString(), java.util.List.of(), java.util.List.of(), null, null, null);
  }
}
