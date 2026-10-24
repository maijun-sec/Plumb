package org.plumb.ai.agent;

import java.util.Map;

/**
 * Plain-data record for the LLM HTTP connection. Replaces the deleted {@code
 * org.plumb.ai.AIConfig}; this is the only AI config the runner needs to know about because the
 * per-rule prompts live with the rules (in {@code DfaChecker.buildAiPrompt(Finding)} and {@code
 * AstChecker.buildAiPrompt(Finding)}), not in a separate config block.
 *
 * <p>Two wire protocols are supported:
 *
 * <ul>
 *   <li>OpenAI {@code /v1/chat/completions} ? used for {@link Provider#OPENAI} and {@link
 *       Provider#OPENAI_COMPATIBLE} (and for {@link Provider#ANTHROPIC} when an OpenAI-compatible
 *       proxy like LiteLLM is in front of the Anthropic API). Full tool-calling support.
 *   <li>Anthropic {@code /v1/messages} ? only used for {@link Provider#ANTHROPIC} without a proxy.
 *       Single-prompt chat is implemented; tool-calling throws {@link
 *       UnsupportedOperationException}. Most production users run an OpenAI-compatible proxy
 *       (LiteLLM) so they get tool calling and a uniform schema.
 * </ul>
 *
 * <h2>{@code max_tokens} configuration</h2>
 *
 * <p>Modern LLMs ship with context windows from 16K up to 1M tokens. The right output cap depends
 * on the model: a 2048-cap on a 200K-context Claude wastes capacity; a 200K-cap on a 16K-context
 * model hits the API's hard limit. The runner sets {@code max_tokens} per request from:
 *
 * <ol>
 *   <li>the explicit {@link #maxTokens} field on this record (if non-null), else
 *   <li>{@link #defaultMaxTokensFor(Provider, String)}, which looks up a per-model known-good value
 *       (Claude Sonnet/Opus: 200K, GPT-4o: 128K, GPT-3.5: 16K, o1/o3: 200K, DeepSeek V3: 64K,
 *       MiniMax-M3: 200K ? see the lookup table), or
 *   <li>{@link #DEFAULT_MAX_TOKENS} = 200_000 as the final fallback for unknown models.
 * </ol>
 *
 * <p>The 200K default matches the operator expectation that "modern large models support hundreds
 * of thousands of tokens". For models with smaller windows, the per-model map returns the right
 * value automatically ? no operator config required for the common case.
 */
public record LlmConnection(
    Provider provider,
    String baseUrl,
    String apiKey,
    String model,
    int timeoutSeconds,
    String extraHeader,
    Integer maxTokens) {

  /**
   * The output-token cap we apply when the model isn't in the per-model lookup table. 200K matches
   * the modern "long-context" tier (Claude Sonnet 4 / Opus 4, o1, MiniMax-M3) and is safely under
   * the API limits of every model we currently wire to.
   */
  public static final int DEFAULT_MAX_TOKENS = 200_000;

  /**
   * Per-model output-token caps. Keyed by lowercased substring of the model name so we match {@code
   * "claude-3-5-sonnet-20250101"} and {@code "claude-3-5-sonnet-latest"} the same way. Values are
   * conservative ? most vendors allow more in beta, but we stay on the GA cap so over-eager
   * providers don't 400 us.
   *
   * <p>Sourced from each vendor's published model card as of early 2026. Add new entries here when
   * adopting a new model; unknown models fall through to {@link #DEFAULT_MAX_TOKENS}.
   */
  private static final Map<String, Integer> MODEL_MAX_TOKENS =
      Map.ofEntries(
          // Anthropic Claude — all 200K context, output cap matches.
          Map.entry("claude-opus-4", 200_000),
          Map.entry("claude-opus-4-5", 200_000),
          Map.entry("claude-sonnet-4", 200_000),
          Map.entry("claude-sonnet-4-5", 200_000),
          Map.entry("claude-3-7-sonnet", 200_000),
          Map.entry("claude-3-5-sonnet", 200_000),
          Map.entry("claude-3-5-haiku", 200_000),
          Map.entry("claude-haiku-4", 200_000),
          Map.entry("claude-3-opus", 200_000),
          // OpenAI
          Map.entry("gpt-5", 200_000),
          Map.entry("gpt-4.1", 64_000),
          Map.entry("gpt-4o", 16_384),
          Map.entry("gpt-4-turbo", 16_384),
          Map.entry("gpt-4", 8_192),
          Map.entry("gpt-3.5-turbo", 4_096),
          // OpenAI reasoning models
          Map.entry("o1", 200_000),
          Map.entry("o1-mini", 65_536),
          Map.entry("o1-preview", 32_768),
          Map.entry("o3", 200_000),
          Map.entry("o3-mini", 100_000),
          Map.entry("o4-mini", 100_000),
          // DeepSeek
          Map.entry("deepseek-v3", 64_000),
          Map.entry("deepseek-r1", 64_000),
          // Meta Llama
          Map.entry("llama-3.1-405b", 16_384),
          Map.entry("llama-3.1-70b", 16_384),
          Map.entry("llama-3.2-90b", 16_384),
          // Mistral
          Map.entry("mistral-large-2", 32_768),
          Map.entry("mixtral-8x22b", 16_384),
          // Qwen
          Map.entry("qwen-2.5-72b", 32_768),
          Map.entry("qwen-max", 32_768),
          // MiniMax-M3 (this project's own model).
          Map.entry("m3", 200_000));

  /**
   * Look up the recommended {@code max_tokens} cap for a {@code (provider, model)} pair. The model
   * string is lowercased and substring-matched against {@link #MODEL_MAX_TOKENS}; unknown models
   * fall through to {@link #DEFAULT_MAX_TOKENS}. Provider is currently a hint (most caps are
   * vendor-specific, not model-specific) but is included for future per-vendor overrides.
   *
   * <p><b>Longest match wins</b>. The lookup uses substring matching (so {@code "claude-opus-4"}
   * matches both {@code "claude-opus-4"} and {@code "claude-opus-4-5-20250101"}), but the entry
   * list is sorted by key length descending so that {@code "o1-preview"} is matched before {@code
   * "o1"}. Without the length sort, {@code "o1"} would shadow {@code "o1-preview"} (the map
   * iteration order is insertion order, and shorter prefixes inserted later would never win). The
   * map is small (~25 entries) so the O(n log n) sort is cheaper than maintaining a trie.
   */
  public static int defaultMaxTokensFor(Provider provider, String model) {
    if (model == null || model.isEmpty()) {
      return DEFAULT_MAX_TOKENS;
    }
    String lower = model.toLowerCase();
    // Snapshot the entry set then sort by key length
    // desc. We don't store the sorted list because the
    // map is static / small and re-sorting is cheaper
    // than the constant memory overhead.
    return MODEL_MAX_TOKENS.entrySet().stream()
        .filter(e -> lower.contains(e.getKey()))
        .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(DEFAULT_MAX_TOKENS);
  }

  /**
   * Resolve the {@code max_tokens} value to send to the API. Order of precedence: explicit field on
   * the connection, then per-model lookup, then the default.
   */
  public int effectiveMaxTokens() {
    if (maxTokens != null && maxTokens > 0) return maxTokens;
    return defaultMaxTokensFor(provider, model);
  }

  public enum Provider {
    /**
     * OpenAI's own {@code /v1/chat/completions} endpoint. Wire-equivalent to any other OpenAI-
     * shape endpoint; the {@link #OPENAI_COMPATIBLE} alias exists so callers can make their intent
     * explicit (e.g. "this is an Azure / Ollama / LocalAI URL, not api.openai.com") but the wire
     * behaviour and the {@link LlmConnection#isUsable()} check are identical to {@link #OPENAI}.
     */
    OPENAI,
    /**
     * Anthropic's native {@code /v1/messages} endpoint. The single-prompt {@code chat()} path is
     * supported (no tool calling); the tool-calling path requires an OpenAI-compatible proxy
     * (LiteLLM, etc.) and throws {@link UnsupportedOperationException} if invoked with this
     * provider ? see {@link OpenAiCompatibleLlmProvider}. {@link LlmConnection#isUsable()} returns
     * {@code false} when no API key is set for this provider.
     */
    ANTHROPIC,
    /**
     * Explicit alias for an OpenAI-shape endpoint hosted elsewhere (Azure, Ollama, vLLM, LocalAI,
     * LiteLLM proxy, etc.). Wire-equivalent to {@link #OPENAI}: same tool schema, same response
     * parsing, same auth (or keyless) handling. The enum value exists purely as self-documentation
     * for the runner config ("I'm pointing at an Azure / local endpoint, not api.openai.com"); the
     * HTTP client doesn't treat it differently from {@link #OPENAI}.
     */
    OPENAI_COMPATIBLE
  }

  public LlmConnection {
    // Defensive copies / normalisation. The runner
    // builds these from CLI / YAML; we want to be
    // robust against nulls in any field.
    if (provider == null) provider = Provider.OPENAI;
    if (baseUrl == null) baseUrl = "";
    if (apiKey == null) apiKey = "";
    if (model == null) model = "";
    if (extraHeader == null) extraHeader = "";
    if (timeoutSeconds <= 0) timeoutSeconds = 30;
    // maxTokens may legitimately be null (= use the
    // per-model default); we only clamp negatives /
    // zero to null so effectiveMaxTokens falls through.
    if (maxTokens != null && maxTokens <= 0) maxTokens = null;
  }

  /**
   * Disabled / no-op connection. {@link #isUsable()} returns false; the runner skips the AI pass.
   */
  public static LlmConnection disabled() {
    return new LlmConnection(Provider.OPENAI, "", "", "", 30, "", null);
  }

  /**
   * Liveness check used by the agent before starting the loop. True iff we have enough to attempt a
   * request.
   */
  public boolean isUsable() {
    if (baseUrl.isEmpty() || model.isEmpty()) return false;
    // Anthropic + plain OpenAI both require a key.
    // OpenAI-compatible local endpoints (Ollama, vLLM)
    // may not.
    if (apiKey.isEmpty() && provider == Provider.ANTHROPIC) return false;
    return true;
  }

  public LlmConnection withApiKey(String key) {
    return new LlmConnection(provider, baseUrl, key, model, timeoutSeconds, extraHeader, maxTokens);
  }

  /**
   * Override the per-request {@code max_tokens} cap. Pass {@code null} to fall back to the
   * per-model default ({@link #defaultMaxTokensFor(Provider, String)}).
   */
  public LlmConnection withMaxTokens(Integer maxTokens) {
    return new LlmConnection(
        provider, baseUrl, apiKey, model, timeoutSeconds, extraHeader, maxTokens);
  }
}
