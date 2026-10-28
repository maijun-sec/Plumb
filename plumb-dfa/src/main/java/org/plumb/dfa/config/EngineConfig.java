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
   * compiled regex patterns for the receiver-passthrough fallback. The passthrough in {@code
   * TaintedCheckerBase.propagateTaintFromCalleeSummary} only fires when the callee signature
   * matches one of these patterns. Without this gate the fallback would mark ANY JDK method
   * returning data from a tainted receiver as tainted, producing FP on POJO getters, util-class
   * factories, etc.
   */
  private final List<Pattern> receiverPassthroughPatterns;

  public EngineConfig(
      SinkFamilyRegistry sinkFamilies,
      FactKindRegistry factKinds,
      CheckerRegistry checkers,
      Map<String, String> cweMappings,
      TuningConfig tuning,
      List<Pattern> receiverPassthroughPatterns) {
    this.sinkFamilies = Objects.requireNonNull(sinkFamilies, "sinkFamilies");
    this.factKinds = Objects.requireNonNull(factKinds, "factKinds");
    this.checkers = Objects.requireNonNull(checkers, "checkers");
    this.cweMappings = cweMappings == null ? Map.of() : Map.copyOf(cweMappings);
    this.tuning = Objects.requireNonNull(tuning, "tuning");
    this.receiverPassthroughPatterns =
        receiverPassthroughPatterns == null ? List.of() : List.copyOf(receiverPassthroughPatterns);
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

  /** receiver-passthrough patterns. */
  public List<Pattern> receiverPassthroughPatterns() {
    return receiverPassthroughPatterns;
  }

  /**
   * true iff the callee signature matches one of the configured receiver-passthrough patterns. The
   * passthrough fallback uses this to gate the receiver → dest taint inference.
   */
  public boolean isReceiverPassthroughEligible(String calleeSig) {
    if (calleeSig == null) return false;
    String normalized = calleeSig.replace('#', '.');
    for (Pattern p : receiverPassthroughPatterns) {
      if (p.matcher(normalized).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Build a fresh {@code EngineConfig} that is the same as this one but with the given checker
   * registry. Useful for programmatically enabling / disabling a checker without reloading the
   * YAML.
   */
  public EngineConfig withCheckers(CheckerRegistry newCheckers) {
    return new EngineConfig(
        sinkFamilies, factKinds, newCheckers, cweMappings, tuning, receiverPassthroughPatterns);
  }

  public EngineConfig withSinkFamilies(SinkFamilyRegistry newFamilies) {
    return new EngineConfig(
        newFamilies, factKinds, checkers, cweMappings, tuning, receiverPassthroughPatterns);
  }

  public EngineConfig withFactKinds(FactKindRegistry newKinds) {
    return new EngineConfig(
        sinkFamilies, newKinds, checkers, cweMappings, tuning, receiverPassthroughPatterns);
  }

  public EngineConfig withTuning(TuningConfig newTuning) {
    return new EngineConfig(
        sinkFamilies, factKinds, checkers, cweMappings, newTuning, receiverPassthroughPatterns);
  }

  public EngineConfig withReceiverPassthroughPatterns(List<Pattern> patterns) {
    return new EngineConfig(sinkFamilies, factKinds, checkers, cweMappings, tuning, patterns);
  }
}
