package org.plumb.dfa.config;

import java.util.EnumSet;
import java.util.Set;
import org.plumb.tac.ir.SourceOrigin;

/**
 * Engine-wide tuning knobs.
 *
 * <p>Loaded from {@code tuning.yaml}; can be overridden at runtime by passing a custom YAML to
 * {@link ConfigLoader#loadFromDirectory}.
 */
public final class TuningConfig {

  private final int contextDepth;
  private final int worklistMaxSize;
  private final int z3TimeoutMs;
  private final int summaryCacheSize;
  private final int reverseWalkMaxDepth;
  private final boolean preScanEnabled;
  private final boolean libraryCallClassification;
  private final boolean taintCallerStringConcat;
  private final String pathSensitivity;
  private final String reportingGroupBy;
  private final String outputFormat;
  private final boolean parallel;
  private final int perFunctionTimeoutMs;
  // Which function origins the engine
  // should analyse. Default: {SOURCE}. Setting
  // LIBRARY enables user-supplied library code
  // (--input some.jar). Setting SYSTEM_LIBRARY
  // additionally enables JDK runtime bodies
  // (only meaningful when explicit JDK stubs
  // have been loaded; in normal operation the
  // engine has no JDK bodies to analyse, so the
  // default exclusion is correct).
  private final Set<SourceOrigin> analysedOrigins;
  // Convenience — when true, the engine
  // walks the JVM-internal graph for
  // SYSTEM_LIBRARY functions (rare; mostly
  // useful for instrumented test runs). Has no
  // effect unless SYSTEM_LIBRARY is in
  // {@code analysedOrigins}.
  private final boolean analyseSystemLibraries;

  public TuningConfig(
      int contextDepth,
      int worklistMaxSize,
      int z3TimeoutMs,
      int summaryCacheSize,
      int reverseWalkMaxDepth,
      boolean preScanEnabled,
      boolean libraryCallClassification,
      boolean taintCallerStringConcat,
      String pathSensitivity,
      String reportingGroupBy,
      String outputFormat,
      boolean parallel,
      int perFunctionTimeoutMs) {
    this(
        contextDepth,
        worklistMaxSize,
        z3TimeoutMs,
        summaryCacheSize,
        reverseWalkMaxDepth,
        preScanEnabled,
        libraryCallClassification,
        taintCallerStringConcat,
        pathSensitivity,
        reportingGroupBy,
        outputFormat,
        parallel,
        perFunctionTimeoutMs,
        EnumSet.of(SourceOrigin.SOURCE),
        false);
  }

  /**
   * Full constructor. The {@code analysedOrigins} set controls which function bodies the DFA engine
   * analyses. Default is {@code EnumSet.of(SOURCE)} ? only user source. Setting {@code LIBRARY}
   * turns on analysis of user-supplied library code; setting {@code SYSTEM_LIBRARY} turns on
   * analysis of JDK runtime bodies (only when such bodies are available, which is rare). The {@code
   * analyseSystemLibraries} flag is a convenience: when true, SYSTEM_LIBRARY is implicitly added to
   * {@code analysedOrigins}.
   */
  public TuningConfig(
      int contextDepth,
      int worklistMaxSize,
      int z3TimeoutMs,
      int summaryCacheSize,
      int reverseWalkMaxDepth,
      boolean preScanEnabled,
      boolean libraryCallClassification,
      boolean taintCallerStringConcat,
      String pathSensitivity,
      String reportingGroupBy,
      String outputFormat,
      boolean parallel,
      int perFunctionTimeoutMs,
      Set<SourceOrigin> analysedOrigins,
      boolean analyseSystemLibraries) {
    this.contextDepth = contextDepth;
    this.worklistMaxSize = worklistMaxSize;
    this.z3TimeoutMs = z3TimeoutMs;
    this.summaryCacheSize = summaryCacheSize;
    this.reverseWalkMaxDepth = reverseWalkMaxDepth;
    this.preScanEnabled = preScanEnabled;
    this.libraryCallClassification = libraryCallClassification;
    this.taintCallerStringConcat = taintCallerStringConcat;
    this.pathSensitivity = pathSensitivity == null ? "full" : pathSensitivity;
    this.reportingGroupBy = reportingGroupBy == null ? "cwe_family" : reportingGroupBy;
    this.outputFormat = outputFormat == null ? "sarif" : outputFormat;
    this.parallel = parallel;
    this.perFunctionTimeoutMs = perFunctionTimeoutMs;
    EnumSet<SourceOrigin> origins =
        analysedOrigins == null
            ? EnumSet.noneOf(SourceOrigin.class)
            : EnumSet.copyOf(analysedOrigins);
    if (origins.isEmpty()) {
      // Defensive default: never silently
      // disable everything (would render the
      // engine useless). Fall back to
      // SOURCE-only, which is the historical
      // default.
      origins = EnumSet.of(SourceOrigin.SOURCE);
    }
    if (analyseSystemLibraries) {
      origins.add(SourceOrigin.SYSTEM_LIBRARY);
    }
    this.analysedOrigins = java.util.Collections.unmodifiableSet(origins);
    this.analyseSystemLibraries = analyseSystemLibraries;
    this.pointsToPolicy = null;
  }

  /**
   * R1107: the context-sensitivity policy the shared points-to analysis runs under.
   *
   * <p>{@code null} means "not configured", which resolves to {@link
   * org.plumb.dfa.points.AbstractObject.Policy#defaultPolicy()} rather than to any particular
   * policy. Keeping the unset case distinct from an explicit choice matters: a caller that never
   * mentions this knob must still get the engine default, and a caller that writes {@code
   * context_insensitive} must get exactly that even if the default later changes.
   *
   * <p>Deliberately not part of the main constructor's parameter list. That constructor already
   * takes fifteen arguments and is called from a dozen places; adding a sixteenth would have made
   * every one of them a place to silently pass the wrong policy.
   */
  private org.plumb.dfa.points.AbstractObject.Policy pointsToPolicy;

  /** The configured policy, or {@code null} when the tuning file does not set one. */
  public org.plumb.dfa.points.AbstractObject.Policy pointsToPolicy() {
    return pointsToPolicy;
  }

  /**
   * The policy to actually run, resolving the unset case to the engine default.
   *
   * <p>This is what the engine should call; {@link #pointsToPolicy()} is for reporting what the
   * configuration said.
   */
  public org.plumb.dfa.points.AbstractObject.Policy effectivePointsToPolicy() {
    return pointsToPolicy == null
        ? org.plumb.dfa.points.AbstractObject.Policy.defaultPolicy()
        : pointsToPolicy;
  }

  /** A copy with an explicit points-to policy. */
  public TuningConfig withPointsToPolicy(org.plumb.dfa.points.AbstractObject.Policy policy) {
    TuningConfig copy =
        new TuningConfig(
            contextDepth,
            worklistMaxSize,
            z3TimeoutMs,
            summaryCacheSize,
            reverseWalkMaxDepth,
            preScanEnabled,
            libraryCallClassification,
            taintCallerStringConcat,
            pathSensitivity,
            reportingGroupBy,
            outputFormat,
            parallel,
            perFunctionTimeoutMs,
            analysedOrigins,
            analyseSystemLibraries);
    copy.pointsToPolicy = policy;
    return copy;
  }

  public int contextDepth() {
    return contextDepth;
  }

  public int worklistMaxSize() {
    return worklistMaxSize;
  }

  public int z3TimeoutMs() {
    return z3TimeoutMs;
  }

  public int summaryCacheSize() {
    return summaryCacheSize;
  }

  public int reverseWalkMaxDepth() {
    return reverseWalkMaxDepth;
  }

  public boolean preScanEnabled() {
    return preScanEnabled;
  }

  public boolean libraryCallClassification() {
    return libraryCallClassification;
  }

  public boolean taintCallerStringConcat() {
    return taintCallerStringConcat;
  }

  public String pathSensitivity() {
    return pathSensitivity;
  }

  public String reportingGroupBy() {
    return reportingGroupBy;
  }

  public String outputFormat() {
    return outputFormat;
  }

  public boolean parallel() {
    return parallel;
  }

  public int perFunctionTimeoutMs() {
    return perFunctionTimeoutMs;
  }

  /**
   * Which function origins the engine analyses. Always contains at least {@link
   * SourceOrigin#SOURCE} (enforced in the constructor). Checkers use this to decide whether to run
   * on a given {@code TacFunction}.
   */
  public Set<SourceOrigin> analysedOrigins() {
    return analysedOrigins;
  }

  /**
   * Convenience ? should this origin be analysed? Equivalent to {@code
   * analysedOrigins().contains(origin)}. Returns false for null origin (defensive; a null origin is
   * treated as SOURCE in {@code TacFunction.origin()}).
   */
  public boolean shouldAnalyse(SourceOrigin origin) {
    if (origin == null) return analysedOrigins.contains(SourceOrigin.SOURCE);
    return analysedOrigins.contains(origin);
  }

  public boolean analyseSystemLibraries() {
    return analyseSystemLibraries;
  }
}
