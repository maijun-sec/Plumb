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
package org.plumb.dfa.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.plumb.tac.ir.SourceLanguage;

/**
 * Aggregated engine configuration.
 *
 * <p>An immutable snapshot of all 5 YAML files plus any programmatic registrations. Created by
 * {@link ConfigLoader#loadFromDirectory} or {@link ConfigLoader#loadFromClasspath}, and then
 * consumed by the {@code DfaEngine} to wire up checkers, fact kinds, and sink families.
 */
public final class EngineConfig {

  private final SinkFamilyRegistry sinkFamilies;
  private final FactKindRegistry factKinds;
  private final CheckerRegistry checkers;
  private final Map<String, String> cweMappings;
  private final TuningConfig tuning;

  /**
   * The receiver-passthrough table. The passthrough in {@code
   * TaintedCheckerBase.propagateTaintFromCalleeSummary} only fires when the callee signature
   * matches one of these rules AND the rule is for the language being analysed. Without this gate
   * the fallback would mark ANY JDK method returning data from a tainted receiver as tainted,
   * producing FP on POJO getters, util-class factories, etc.
   *
   * <p>The table keeps the language scope each rule was written with. A rule that names languages
   * applies to those languages only, so a rule written for one language's library does not fire on
   * another language's code.
   */
  private final ReceiverPassthrough receiverPassthrough;

  /**
   * The per-language library semantics tables.
   *
   * <p>This is what tells the engine how a standard-library call affects taint, per language
   * family. It was previously a set of inline {@code "java.lang.String.trim/"} string comparisons
   * scattered through the taint engine -- knowledge about Java's library embedded in
   * language-agnostic code.
   */
  private final org.plumb.dfa.semantics.LanguageSemanticsRegistry languageSemantics;

  /**
   * The rules that say an ARGUMENT's taint reaches the callee's RESULT.
   *
   * <p>Never {@code null}: a configuration with no argument table gets an empty one, so a checker
   * can always ask and get an answer.
   */
  private final ArgumentPassthrough argumentPassthrough;

  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning,
      List<Pattern> receiverPassthroughPatterns) {
    this(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        ReceiverPassthrough.of(receiverPassthroughPatterns),
        org.plumb.dfa.semantics.LanguageSemanticsRegistry.withDefaults(),
        ArgumentPassthrough.empty());
  }

  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning,
      List<Pattern> receiverPassthroughPatterns,
      org.plumb.dfa.semantics.LanguageSemanticsRegistry languageSemantics) {
    this(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        ReceiverPassthrough.of(receiverPassthroughPatterns),
        languageSemantics,
        ArgumentPassthrough.empty());
  }

  /**
   * The full constructor: the two passthrough tables and the language semantics.
   *
   * <p>The argument table is the one that says an ARGUMENT's taint reaches the callee's RESULT --
   * the shape the receiver table cannot express.
   */
  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning,
      List<Pattern> receiverPassthroughPatterns,
      org.plumb.dfa.semantics.LanguageSemanticsRegistry languageSemantics,
      ArgumentPassthrough argumentPassthrough) {
    this(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        ReceiverPassthrough.of(receiverPassthroughPatterns),
        languageSemantics,
        argumentPassthrough);
  }

  /**
   * The full constructor: the two passthrough tables and the language semantics.
   *
   * <p>This is the one that keeps the receiver table's language scope. The overload that takes a
   * flat list of patterns cannot carry it, so a caller that uses it gets the scope-free table.
   *
   * @param receiverPassthrough the receiver-passthrough table, with its language scope
   */
  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning,
      ReceiverPassthrough receiverPassthrough,
      org.plumb.dfa.semantics.LanguageSemanticsRegistry languageSemantics,
      ArgumentPassthrough argumentPassthrough) {
    this.sinkFamilies = Objects.requireNonNull(sinkFamilies, "sinkFamilies");
    this.factKinds = Objects.requireNonNull(factKinds, "factKinds");
    this.checkers = Objects.requireNonNull(checkers, "checkers");
    this.cweMappings = cweMappings == null ? Map.of() : Map.copyOf(cweMappings);
    this.tuning = Objects.requireNonNull(tuning, "tuning");
    this.receiverPassthrough =
        receiverPassthrough == null ? ReceiverPassthrough.empty() : receiverPassthrough;
    this.languageSemantics =
        languageSemantics == null
            ? org.plumb.dfa.semantics.LanguageSemanticsRegistry.withDefaults()
            : languageSemantics;
    this.argumentPassthrough =
        argumentPassthrough == null ? ArgumentPassthrough.empty() : argumentPassthrough;
  }

  /**
   * The per-language library semantics tables.
   *
   * <p>Never {@code null}. A language with no registered table gets an empty one, so a checker can
   * always ask and get an answer.
   */
  public org.plumb.dfa.semantics.LanguageSemanticsRegistry languageSemantics() {
    return languageSemantics;
  }

  /** Legacy 5-arg constructor for backward compat. */
  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning) {
    this(sinkFamilies, factKinds, checkers, cweMappings, tuning, List.of());
  }

  public SinkFamilyRegistry sinkFamilies() {
    return sinkFamilies;
  }

  public FactKindRegistry factKinds() {
    return factKinds;
  }

  public CheckerRegistry checkers() {
    return checkers;
  }

  public Map<String, String> cweMappings() {
    return cweMappings;
  }

  public TuningConfig tuning() {
    return tuning;
  }

  /**
   * The receiver-passthrough patterns, flattened.
   *
   * <p>The flattened view loses the language scope each rule was written with. A caller that needs
   * the scope asks for {@link #receiverPassthroughTable()}.
   *
   * @return the patterns
   */
  public List<Pattern> receiverPassthroughPatterns() {
    return receiverPassthrough.patterns();
  }

  /**
   * The receiver-passthrough table, with the language scope of each rule.
   *
   * @return the table
   */
  public ReceiverPassthrough receiverPassthroughTable() {
    return receiverPassthrough;
  }

  /**
   * The rules that say an ARGUMENT's taint reaches the callee's RESULT.
   *
   * <p>Never {@code null}.
   */
  public ArgumentPassthrough argumentPassthrough() {
    return argumentPassthrough;
  }

  /**
   * true iff the callee signature matches one of the configured receiver-passthrough patterns. The
   * passthrough fallback uses this to gate the receiver → dest taint inference.
   */
  public boolean isReceiverPassthroughEligible(String calleeSig) {
    return receiverPassthrough.matchesAnyLanguage(calleeSig);
  }

  /**
   * Whether the receiver-passthrough fallback applies to this callee, in the given language.
   *
   * <p>This is the view the engine uses: a rule written for one language's library does not apply
   * to another language's code.
   *
   * @param calleeSig the callee's signature
   * @param language the analysed language; null means unknown
   * @return true when the fallback applies
   */
  public boolean isReceiverPassthroughEligible(String calleeSig, SourceLanguage language) {
    return receiverPassthrough.matches(calleeSig, language);
  }

  /**
   * Build a fresh {@code EngineConfig} that is the same as this one but with the given checker
   * registry. Useful for programmatically enabling / disabling a checker without reloading the
   * YAML.
   */
  public EngineConfig withCheckers(CheckerRegistry newCheckers) {
    return new EngineConfig(
        sinkFamilies,
        factKinds,
        newCheckers,
        cweMappings,
        tuning,
        receiverPassthrough,
        languageSemantics,
        argumentPassthrough);
  }

  public EngineConfig withSinkFamilies(SinkFamilyRegistry newFamilies) {
    return new EngineConfig(
        newFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        receiverPassthrough,
        languageSemantics,
        argumentPassthrough);
  }

  public EngineConfig withFactKinds(FactKindRegistry newKinds) {
    return new EngineConfig(
        sinkFamilies,
        newKinds,
        checkers,
        cweMappings,
        tuning,
        receiverPassthrough,
        languageSemantics,
        argumentPassthrough);
  }

  public EngineConfig withTuning(TuningConfig newTuning) {
    return new EngineConfig(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        newTuning,
        receiverPassthrough,
        languageSemantics,
        argumentPassthrough);
  }

  public EngineConfig withReceiverPassthroughPatterns(List<Pattern> patterns) {
    return withReceiverPassthroughTable(ReceiverPassthrough.of(patterns));
  }

  /**
   * A copy of this configuration with a different receiver-passthrough table.
   *
   * @param table the table, with its language scope
   * @return a new configuration; this one is unchanged
   */
  public EngineConfig withReceiverPassthroughTable(ReceiverPassthrough table) {
    return new EngineConfig(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        table,
        languageSemantics,
        argumentPassthrough);
  }

  /** The same configuration with a different argument-passthrough table. */
  public EngineConfig withArgumentPassthrough(ArgumentPassthrough table) {
    return new EngineConfig(
        sinkFamilies,
        factKinds,
        checkers,
        cweMappings,
        tuning,
        receiverPassthrough,
        languageSemantics,
        table);
  }
}
