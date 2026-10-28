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
package org.plumb.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.ai.agent.LlmConnection;
import org.plumb.ai.prompts.AgentPrompts;

/**
 * Smoke tests for the plumb-ai agent framework (Step 7). The system prompts for the FP reducer and
 * bug scanner agents live in {@link AgentPrompts}; the LLM connection shape lives in {@link
 * LlmConnection}. Both are pure-data and tested without an LLM round-trip.
 */
class AgentPromptsTest {

  @Test
  void fpReducerPromptAsksForJsonLine() {
    String s = AgentPrompts.FP_REDUCER;
    assertThat(s).contains("verdict");
    assertThat(s).contains("TP");
    assertThat(s).contains("FP");
    assertThat(s).contains("UNKNOWN");
  }

  @Test
  void fpReducerV10PromptAsksForJsonLine() {
    // default-to-FP variant
    String s = AgentPrompts.FP_REDUCER_V10;
    assertThat(s).contains("verdict");
    assertThat(s).contains("TP");
    assertThat(s).contains("FP");
    assertThat(s).contains("UNKNOWN");
    // key markers: positive-evidence framing + default-to-FP
    assertThat(s).contains("POSITIVE EVIDENCE");
    assertThat(s).contains("Default to FP");
    assertThat(s).contains("USER INPUT");
    assertThat(s).contains("NO SANITIZATION");
    assertThat(s).contains("EXPLOITABLE SINK");
    // Should NOT contain v9's "default to TP" instruction
    org.assertj.core.api.Assertions.assertThat(s.toLowerCase()).doesNotContain("default to tp");
  }

  @Test
  void fpReducerV11PromptAsksForJsonLine() {
    // default-to-FP + implicit-guarantee hint
    String s = AgentPrompts.FP_REDUCER_V11;
    assertThat(s).contains("verdict");
    assertThat(s).contains("TP");
    assertThat(s).contains("FP");
    assertThat(s).contains("UNKNOWN");
    // markers (carried forward)
    assertThat(s).contains("POSITIVE EVIDENCE");
    assertThat(s).contains("Default to FP");
    // new section
    assertThat(s).contains("IMPLICIT GUARANTEES");
    assertThat(s).contains("JADOVAC NON-NULL CONTRACT");
    assertThat(s).contains("FRESHLY CONSTRUCTED OBJECT");
    assertThat(s).contains("UTILITY-CLASS");
    assertThat(s).contains("CONSTRUCTOR INVARIANT");
  }

  @Test
  void bugScannerPromptAsksForJsonArray() {
    String s = AgentPrompts.BUG_SCANNER;
    assertThat(s).contains("rule_id");
    assertThat(s).contains("severity");
    assertThat(s).contains("message");
    assertThat(s).contains("line");
  }

  @Test
  void llmConnectionDisabledIsNotUsable() {
    LlmConnection c = LlmConnection.disabled();
    assertThat(c.isUsable()).isFalse();
    assertThat(c.model()).isEmpty();
    assertThat(c.baseUrl()).isEmpty();
  }

  @Test
  void llmConnectionUsableWhenConfigured() {
    LlmConnection c =
        new LlmConnection(
            LlmConnection.Provider.OPENAI_COMPATIBLE,
            "https://api.example.com",
            "sk-test",
            "gpt-4o-mini",
            30,
            "",
            null);
    assertThat(c.isUsable()).isTrue();
    assertThat(c.model()).isEqualTo("gpt-4o-mini");
    assertThat(c.baseUrl()).isEqualTo("https://api.example.com");
  }

  @Test
  void llmConnectionAnthropicRequiresKey() {
    LlmConnection c =
        new LlmConnection(
            LlmConnection.Provider.ANTHROPIC,
            "https://api.anthropic.com",
            "", // no key
            "claude-3-5-sonnet-20250109",
            30,
            "",
            null);
    assertThat(c.isUsable()).isFalse();
  }

  @Test
  void llmConnectionWithApiKeyOverridesBlank() {
    LlmConnection c = LlmConnection.disabled().withApiKey("sk-test");
    assertThat(c.isUsable()).isFalse(); // still no model / baseUrl
    assertThat(c.apiKey()).isEqualTo("sk-test");
  }

  // ─── per-model max_tokens lookup ─────────────────────

  @Test
  void defaultMaxTokensForKnownLongContextModels() {
    // Long-context tier (Claude Sonnet/Opus, o1, o3,
    // MiniMax-M3, GPT-5) all land on 200K.
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "claude-sonnet-4-5-20250101"))
        .isEqualTo(200_000);
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "claude-opus-4-5"))
        .isEqualTo(200_000);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "o1-preview"))
        .isEqualTo(32_768);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "o1"))
        .isEqualTo(200_000);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "o3-mini"))
        .isEqualTo(100_000);
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "MiniMax-M3"))
        .isEqualTo(200_000);
  }

  @Test
  void defaultMaxTokensForShortContextModels() {
    // Smaller-context models get smaller caps so we
    // don't 400 the API.
    assertThat(
            LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "gpt-3.5-turbo-0125"))
        .isEqualTo(4_096);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "gpt-4"))
        .isEqualTo(8_192);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "gpt-4o"))
        .isEqualTo(16_384);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, "gpt-4o-mini"))
        .as("substring match: gpt-4o-mini contains gpt-4o")
        .isEqualTo(16_384);
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "deepseek-v3"))
        .isEqualTo(64_000);
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "llama-3.1-70b"))
        .isEqualTo(16_384);
  }

  @Test
  void defaultMaxTokensForUnknownModelFallsBackToDefault() {
    // Unknown model → DEFAULT_MAX_TOKENS (200K).
    assertThat(
            LlmConnection.defaultMaxTokensFor(
                LlmConnection.Provider.OPENAI_COMPATIBLE, "mystery-model-9000"))
        .isEqualTo(LlmConnection.DEFAULT_MAX_TOKENS);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, ""))
        .isEqualTo(LlmConnection.DEFAULT_MAX_TOKENS);
    assertThat(LlmConnection.defaultMaxTokensFor(LlmConnection.Provider.OPENAI, null))
        .isEqualTo(LlmConnection.DEFAULT_MAX_TOKENS);
  }

  @Test
  void effectiveMaxTokensPrefersExplicitFieldOverLookup() {
    // When the caller sets maxTokens explicitly, that
    // wins over the per-model default.
    LlmConnection c =
        new LlmConnection(
            LlmConnection.Provider.OPENAI_COMPATIBLE,
            "https://api.example.com",
            "sk-test",
            "gpt-3.5-turbo",
            30,
            "",
            1234);
    assertThat(c.effectiveMaxTokens()).isEqualTo(1234);

    // When the caller leaves it null, the lookup wins.
    LlmConnection c2 =
        new LlmConnection(
            LlmConnection.Provider.OPENAI_COMPATIBLE,
            "https://api.example.com",
            "sk-test",
            "gpt-3.5-turbo",
            30,
            "",
            null);
    assertThat(c2.effectiveMaxTokens()).isEqualTo(4_096);
  }

  @Test
  void withMaxTokensReturnsNewConnection() {
    LlmConnection c = LlmConnection.disabled();
    LlmConnection c2 = c.withMaxTokens(8192);
    assertThat(c2.maxTokens()).isEqualTo(8192);
    // Original is unchanged (record immutability).
    assertThat(c.maxTokens()).isNull();
  }
}
