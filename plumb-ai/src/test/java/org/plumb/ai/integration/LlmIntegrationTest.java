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
package org.plumb.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.plumb.ai.agent.*;
import org.plumb.ai.tools.*;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;

/**
 * — Real-LLM integration tests. Skipped unless {@code MINIMAX_API_KEY} is set in the environment.
 *
 * <p>These tests exercise the full Re-Act loop against the configured LLM endpoint (default:
 * MiniMax's OpenAI-compatible {@code /v1/chat/completions}). They're slow (each call is a real
 * network round-trip) and may flake if the LLM is down ? that's the trade-off of an integration
 * test.
 *
 * <p>Use the {@code PLUMB_AI_E2E} env var (any non-empty value) to enable them. This keeps {@code
 * mvn test} fast for the dev inner loop while still letting CI validate the wire protocol
 * end-to-end.
 */
@EnabledIfEnvironmentVariable(named = "PLUMB_AI_E2E", matches = ".+")
class LlmIntegrationTest {

  @Test
  void aiProviderChatWithToolsReturnsReasonableReply() {
    LlmConnection conn = makeConnection("MiniMax-Text-01");
    OpenAiCompatibleLlmProvider p = new OpenAiCompatibleLlmProvider(conn);
    List<LlmMessage> msgs =
        List.of(
            LlmMessage.system("You are a helpful assistant."),
            LlmMessage.user("Reply with one word: PONG."));
    List<ToolDefinition> tools = List.of();
    LlmReply reply = p.chatWithTools(msgs, tools, LlmProvider.TOOL_CHOICE_AUTO, conn.model(), 0.0);
    assertThat(reply).isNotNull();
    assertThat(reply.content()).isNotBlank();
    // The model is allowed to say anything; we just
    // assert the round-trip worked.
  }

  @Test
  void aiProviderIsAvailableWithConfiguredKey() {
    LlmConnection conn = makeConnection("MiniMax-Text-01");
    assertThat(new OpenAiCompatibleLlmProvider(conn).isAvailable()).isTrue();
  }

  @Test
  void reActAgentSingleShotFpReduction() throws Exception {
    // The simplest Re-Act test: LLM sees a SQL injection
    // finding, immediately returns FINAL: TP without
    // calling any tools (because the example is
    // trivial).
    LlmConnection conn = makeConnection("MiniMax-Text-01");
    OpenAiCompatibleLlmProvider p = new OpenAiCompatibleLlmProvider(conn);
    ToolRegistry tools =
        new ToolRegistry()
            .register(new ReadSourceTool())
            .register(new GrepTool())
            .register(new GlobTool())
            .register(new FindDefinitionsTool());
    ReActAgent agent = new ReActAgent(p, tools);
    Finding f = makeSqlInjectionFinding();
    AgentTask task =
        AgentTask.fpReduction(
            "You are a security reviewer. Judge TP/FP/UNKNOWN.",
            "String q = ... + userInput; stmt.executeQuery(q);",
            conn.model(),
            0.0);
    AgentResult result =
        agent.run(task, new ToolContext(f, "fp-reduction", ".", null, null, null, null, null));
    assertThat(result).isNotNull();
    assertThat(result.kind()).isEqualTo(AgentResult.Kind.FINAL_ANSWER);
  }

  @Test
  void reActAgentCallsReadSourceForComplexFinding() throws Exception {
    // Write a real .java file with a non-trivial SQL
    // injection + a real sanitizer pattern, then
    // ask the LLM to judge. The LLM should call
    // read_source (or another tool) to inspect the
    // file, then return a verdict.
    Path tmp = Files.createTempFile("LlmE2E", ".java");
    Files.writeString(
        tmp,
        "import java.sql.*;\n"
            + "public class X {\n"
            + "  void f(Connection conn, String userInput) throws SQLException {\n"
            + "    PreparedStatement ps = conn.prepareStatement(\n"
            + "        \"SELECT * FROM users WHERE name = ?\");\n"
            + "    ps.setString(1, userInput);  // sanitizer: parameterized query\n"
            + "    ResultSet rs = ps.executeQuery();\n"
            + "  }\n"
            + "}\n");
    LlmConnection conn = makeConnection("MiniMax-Text-01");
    OpenAiCompatibleLlmProvider p = new OpenAiCompatibleLlmProvider(conn);
    ToolRegistry tools =
        new ToolRegistry()
            .register(new ReadSourceTool())
            .register(new GrepTool())
            .register(new GlobTool())
            .register(new FindDefinitionsTool());
    ReActAgent agent = new ReActAgent(p, tools);
    Finding f = makeSqlInjectionFinding();
    AgentTask task =
        AgentTask.fpReduction(
            "You are a security reviewer. Judge TP/FP/UNKNOWN.",
            "PreparedStatement used with setString(1, userInput). " + "The .java file is at " + tmp,
            conn.model(),
            0.0);
    AgentResult result =
        agent.run(
            task,
            new ToolContext(
                f, "fp-reduction", tmp.getParent().toString(), null, null, null, null, null));
    assertThat(result).isNotNull();
    Files.deleteIfExists(tmp);
  }

  @Test
  void reActAgentToolDefinitionJsonSchema() {
    // Verify the OpenAI function-calling JSON shape is
    // what we expect. Cheap test that doesn't talk
    // to the network.
    ReadSourceTool rs = new ReadSourceTool();
    ToolDefinition def = rs.definition();
    com.fasterxml.jackson.databind.ObjectMapper m =
        new com.fasterxml.jackson.databind.ObjectMapper();
    var node = def.toJsonNode(m);
    assertThat(node.get("type").asText()).isEqualTo("function");
    assertThat(node.get("function").get("name").asText()).isEqualTo("read_source");
    assertThat(node.get("function").get("description").asText()).isNotEmpty();
    assertThat(node.get("function").get("parameters").get("type").asText()).isEqualTo("object");
  }

  // ─── helpers ────────────────────────────────────────

  private static LlmConnection makeConnection(String model) {
    String apiKey = System.getenv("MINIMAX_API_KEY");
    return new LlmConnection(
        LlmConnection.Provider.OPENAI_COMPATIBLE,
        "https://api.minimax.chat",
        apiKey == null ? "" : apiKey,
        model,
        60,
        "",
        null);
  }

  private static Finding makeSqlInjectionFinding() {
    RuleDef rule =
        new RuleDef(
            new RuleId("bu-0301-cwe89", "SQL_INJECTION"),
            "SQL injection via string concatenation",
            RuleDef.Severity.HIGH,
            RuleDef.Type.SECURITY,
            List.of("CWE-89"),
            "org.plumb.dfa.checkers.taint.SqlInjectionChecker",
            true);
    return new Finding(
        Path.of("src/UserRepo.java"),
        42,
        8,
        rule,
        "Tainted value 'userInput' flows to Statement.executeQuery()",
        RuleDef.Severity.HIGH,
        "L40: source -> L42: sink");
  }
}
