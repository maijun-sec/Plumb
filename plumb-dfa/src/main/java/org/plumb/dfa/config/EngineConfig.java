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
   * R698: compiled regex patterns for the receiver-passthrough fallback. The passthrough in {@code
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

  /** R698: receiver-passthrough patterns. */
  public List<Pattern> receiverPassthroughPatterns() {
    return receiverPassthroughPatterns;
  }

  /**
   * R698: true iff the callee signature matches one of the configured receiver-passthrough
   * patterns. The passthrough fallback uses this to gate the receiver → dest taint inference.
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
