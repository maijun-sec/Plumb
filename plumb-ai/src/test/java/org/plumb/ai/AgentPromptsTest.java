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
import org.plumb.ai.prompts.FalsePositivePrompts;

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
    // The deprecated name, still resolving. It keeps its ORIGINAL meaning:
    // the default-to-FP policy that requires positive evidence.
    String s = AgentPrompts.FP_REDUCER_V10;
    assertThat(s).contains("verdict");
    assertThat(s).contains("TP");
    assertThat(s).contains("FP");
    assertThat(s).contains("UNKNOWN");
    // key markers: positive-evidence framing + default-to-FP
    assertThat(s).contains("POSITIVE EVIDENCE");
    assertThat(s).contains("Default to FP");
    assertThat(s).contains("USER INPUT");
    assertThat(s).contains("NO SANITISATION");
    assertThat(s).contains("EXPLOITABLE SINK");
    // Should NOT contain the opposite default.
    org.assertj.core.api.Assertions.assertThat(s.toLowerCase()).doesNotContain("default to tp");
  }

  @Test
  void fpReducerV11PromptAsksForJsonLine() {
    // The deprecated name, still resolving. It now points at the prompt
    // with the implicit-guarantee hints.
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
    assertThat(s).contains("DOCUMENTED NON-NULL CONTRACT");
    assertThat(s).contains("FRESHLY CONSTRUCTED OBJECT");
    assertThat(s).contains("UTILITY-CLASS");
    assertThat(s).contains("CONSTRUCTOR INVARIANT");
  }

  // ─── the named prompts carry the analysis method ──────────────────
  //
  // The names describe a DECISION POLICY. A prompt that implements the
  // policy has to carry the method that produces it, so these tests
  // assert the method's five questions are present -- not just that the
  // string exists.
  //
  // A test that only asserted "the prompt mentions TP and FP" would pass
  // for a prompt that says nothing about how to decide, which is the
  // failure this whole change exists to prevent.

  @Test
  void theNamedPromptsCarryTheFiveAnalysisQuestions() {
    for (String s :
        new String[] {
          FalsePositivePrompts.FALSE_POSITIVE_REDUCTION,
          FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_STRICT,
          FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES
        }) {
      assertThat(s).contains("QUESTION 1");
      assertThat(s).contains("QUESTION 2");
      assertThat(s).contains("QUESTION 3");
      assertThat(s).contains("QUESTION 4");
      assertThat(s).contains("QUESTION 5");
    }
  }

  @Test
  void theAnalysisMethodCoversSourceSinkReachabilityConditionsAndSanitiser() {
    String s = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;
    // Q1: does the source really introduce tainted data?
    assertThat(s).contains("DOES THE SOURCE REALLY INTRODUCE");
    // Q2: does the sink really cause this kind of problem?
    assertThat(s).contains("DOES THE SINK REALLY CAUSE");
    // Q3: is the trace reachable -- and is every hop real?
    assertThat(s).contains("IS THE TRACE ACTUALLY REACHABLE");
    // Q4: can the conditions on the path be satisfied?
    assertThat(s).contains("CAN THE CONDITIONS ON THE PATH BE SATISFIED");
    // Q5: is there an effective sanitiser on the path?
    assertThat(s).contains("IS THERE AN EFFECTIVE SANITISER ON THE PATH");
  }

  @Test
  void theAnalysisMethodExplainsCallByValueSemantics() {
    // The hop that is most often wrong: a value passed to a function is a
    // COPY, and the callee may discard it. A trace that assumes otherwise
    // is the most common false positive.
    String s = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;
    assertThat(s).contains("CALL-BY-VALUE SEMANTICS");
    assertThat(s).contains("discards it");
  }

  @Test
  void theActivePromptRequiresPositiveEvidenceOnAllFiveQuestions() {
    String s = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;
    assertThat(s).contains("POSITIVE EVIDENCE REQUIRED FOR TP");
    // The five evidence requirements, one per analysis question.
    assertThat(s).contains("USER INPUT");
    assertThat(s).contains("NO SANITISATION");
    assertThat(s).contains("REACHABLE TRACE");
    assertThat(s).contains("SATISFIABLE CONDITIONS");
    assertThat(s).contains("EXPLOITABLE SINK");
    // The default is FP, and the prompt must not tell the model the
    // opposite.
    assertThat(s).contains("Default to FP");
    assertThat(s.toLowerCase()).doesNotContain("default to tp");
  }

  @Test
  void theStrictPromptDefaultsTheOtherWay() {
    // The strict policy is the mirror image: the finding stands unless a
    // neutralisation is visible. The two differ in ONE thing each, and
    // that difference has to be visible in the text.
    String s = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_STRICT;
    assertThat(s).contains("default to TP");
    assertThat(s.toLowerCase()).doesNotContain("default to fp");
  }

  @Test
  void theImplicitGuaranteePromptAddsTheFourGuarantees() {
    String s = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES;
    assertThat(s).contains("IMPLICIT GUARANTEES");
    assertThat(s).contains("DOCUMENTED NON-NULL CONTRACT");
    assertThat(s).contains("FRESHLY CONSTRUCTED OBJECT");
    assertThat(s).contains("UTILITY-CLASS / STATIC FACTORY");
    assertThat(s).contains("CONSTRUCTOR INVARIANT");
    // It keeps the active policy too.
    assertThat(s).contains("POSITIVE EVIDENCE REQUIRED FOR TP");
  }

  @Test
  void theDeprecatedNamesStillResolveToTheNamedPrompts() {
    // The old names are kept so nothing outside the module breaks, but
    // they must point at the NEW text -- a deprecated alias that pointed
    // at a stale copy of the prompt would be a second source of truth.
    //
    // The mapping preserves each alias's ORIGINAL meaning: V10 was the
    // default-to-FP policy, and V11 was that policy plus the implicit
    // guarantees. Renaming must not silently change what they mean.
    assertThat(AgentPrompts.FP_REDUCER).isEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION);
    assertThat(AgentPrompts.FP_REDUCER_V10)
        .isEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION);
    assertThat(AgentPrompts.FP_REDUCER_V11)
        .isEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES);
  }

  @Test
  void theThreePromptsAreDistinct() {
    // Three names, three behaviours. If two of them were the same text
    // under two names, the naming would be decoration.
    assertThat(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION)
        .isNotEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_STRICT);
    assertThat(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION)
        .isNotEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES);
    assertThat(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_STRICT)
        .isNotEqualTo(FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES);
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
