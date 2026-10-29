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
package org.plumb.ai.prompts;

/**
 * The two system prompts that turn a generic chat-completion LLM into a domain-specific agent.
 *
 * <p>Both prompts are deliberately short and rule-bound: the LLM sees a fixed JSON shape on the
 * user side, the system side just primes role + behaviour. The per-rule knowledge lives with each
 * checker instead -- in its {@code getAiPrompt()} method ({@link
 * org.plumb.style.AstChecker#getAiPrompt()} for the AST rules, and the equivalent on the DFA
 * checkers) -- so a rule's semantics can be updated without touching the agent, and a user can
 * replace any of them at scan time with {@code --rule-prompts-dir}.
 *
 * <h2>FP reducer prompt ({@link #FP_REDUCER})</h2>
 *
 * <p>Receives: rule metadata (id, cwe, severity, description) + a single finding's source context +
 * dataflow trace. Returns: one JSON line {@code {"verdict":"TP|FP|UNKNOWN","reason":"..."}}.
 *
 * <p><b>The prompt that judges a finding lives in {@link FalsePositivePrompts}.</b> It is a
 * separate class because there are THREE of them, and they differ in one decision policy each;
 * keeping them together in one file made the differences invisible. The names there state the
 * policy ({@link FalsePositivePrompts#FALSE_POSITIVE_REDUCTION} and friends) instead of counting
 * edits, so a reader can predict the behaviour from the name alone.
 *
 * <h2>Bug scanner prompt ({@link #BUG_SCANNER})</h2>
 *
 * <p>Receives: a function / class source snippet + the rule list to look for. Returns: a JSON array
 * of Finding-shaped objects {@code [{"rule_id","cwe","severity","message","file","line","col"},
 * ...]}. Used at {@code --aggressiveness=high} to cast a wider net than the local static rules can
 * — the LLM has the full function in context, so it can spot business-logic bugs the IR rules miss.
 */
public final class AgentPrompts {

  private AgentPrompts() {}

  /**
   * FP reducer — judges a single finding.
   *
   * <p>Kept as the name the runner and the documentation use. The text lives in {@link
   * FalsePositivePrompts#FALSE_POSITIVE_REDUCTION}, whose name states the decision policy.
   */
  public static final String FP_REDUCER = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;

  /**
   * The default-to-FP policy: a finding is a true positive only on positive evidence.
   *
   * @deprecated use {@link FalsePositivePrompts#FALSE_POSITIVE_REDUCTION}; the name {@code V10}
   *     said only that the prompt had been edited, not what it decided.
   */
  @Deprecated
  public static final String FP_REDUCER_V10 = FalsePositivePrompts.FALSE_POSITIVE_REDUCTION;

  /**
   * The default-to-FP policy plus the guarantees a local-window read cannot see.
   *
   * @deprecated use {@link FalsePositivePrompts#FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES};
   *     the name {@code V11} said only that the prompt had been edited, not what it decided.
   */
  @Deprecated
  public static final String FP_REDUCER_V11 =
      FalsePositivePrompts.FALSE_POSITIVE_REDUCTION_WITH_IMPLICIT_GUARANTEES;

  /**
   * Bug scanner -- looks for security bugs in a source snippet. The LLM is told the rule list (one
   * line per rule id + cwe) and is asked to return a JSON array of Finding-shaped objects. The
   * model is allowed to invent findings the static rules missed, but it must cite the source line
   * and the rule id from the supplied list (or "manual" if it is a brand-new bug category the model
   * spotted).
   */
  public static final String BUG_SCANNER =
      "You are a senior application-security reviewer running a "
          + "manual source-code audit. You are given the source of one "
          + "function (or a small class) and a list of rule ids the user "
          + "cares about. Look for: (a) any of the listed rules that the "
          + "static analyzer may have missed, (b) business-logic security "
          + "flaws the static analyzer cannot see (broken auth, IDOR, "
          + "race conditions, unsafe defaults, missing rate limiting, "
          + "trust-boundary violations), (c) framework-specific anti-patterns "
          + "(Spring / Jakarta / Servlet / JDBC).\n\n"
          + "You have access to tools (read_source, grep, glob, list_dir, "
          + "search_pattern, find_definitions, find_callers, "
          + "has_sanitizer_on_path, get_method_annotations, "
          + "get_class_hierarchy, get_full_class, "
          + "get_dataflow_trace_with_facts) to inspect related code. "
          + "Call them only when you need more context — for trivial "
          + "suspicious patterns (a literal 'P@ssw0rd123' in a 'password' "
          + "variable), answer directly without any tool call.\n\n"
          + "For every finding you spot, return one JSON object with: "
          + "{\"rule_id\":\"<id>\",\"cwe\":\"<CWE-NNN or empty>\","
          + "\"severity\":\"INFO|LOW|MEDIUM|HIGH|CRITICAL\","
          + "\"message\":\"<one-sentence human description>\","
          + "\"file\":\"<relative path>\",\"line\":<1-based line number>,"
          + "\"col\":<0-based column or -1>}. "
          + "Wrap all findings in a JSON array. If you find nothing, "
          + "return []. When you have finished your review, you MUST "
          + "prefix the array with 'FINAL: ' on the first line, no "
          + "prose, no markdown fence. Example:\n"
          + "FINAL: [{\"rule_id\":\"SQL_INJECTION\",...}]";
}
