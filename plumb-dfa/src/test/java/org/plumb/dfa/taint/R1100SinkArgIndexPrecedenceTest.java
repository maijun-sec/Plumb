package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;

/**
 * R1100 regression gate: arg-index precedence between {@code sink-families.yaml} and the legacy
 * {@code sinkArgumentIndex} override.
 *
 * <h2>What this pins down</h2>
 *
 * <p>{@code TaintedCheckerBase.patternArgIndex} is only reached after a {@link SinkFamily} has
 * already matched the callee. {@link SinkFamily#argIndexFor} returns {@code -1} <i>only</i> when no
 * pattern matches, and {@code ConfigLoader} defaults {@code default_arg_index} to {@code 0} — never
 * {@code -1}. Therefore the legacy {@code sinkArgumentIndex} override cannot run for any shipped
 * checker, and every effective arg index comes from the yaml.
 *
 * <p>This matters because {@code XssInjectionChecker.sinkArgumentIndex} returns {@code 1} while
 * {@code xss.sinks} declares {@code default_arg_index: 0}. Both values look correct in isolation;
 * only the yaml is live. Deleting the "dead" overrides on the assumption that they are the fallback
 * would therefore be safe behaviourally, but keeping them while believing they are configuration is
 * not — hence the documentation now states the reachability rule, and this test keeps it true.
 */
class R1100SinkArgIndexPrecedenceTest {

  private final SinkFamily xss = family("xss.sinks");
  private final SinkFamily ldap = family("ldap.sinks");
  private final SinkFamily deserialization = family("deserialization.sinks");

  @Test
  void yamlDefaultArgIndexIsAlwaysPresentSoLegacyHookNeverSeesNegative() {
    // Every family in the shipped yaml resolves to a non-negative index for a matched
    // callee. That non-negative value is what short-circuits the legacy hook.
    assertThat(xss.defaultArgIndex()).isZero();
    assertThat(ldap.defaultArgIndex()).isEqualTo(1);
  }

  @Test
  void yamlArgIndexWinsOverTheCheckerOverrideAndTheTwoCasesDiffer() {
    // This is the pair that makes the contract observable.
    //
    // XssInjectionChecker.sinkArgumentIndex() returns 1 unconditionally. The yaml splits the same
    // family in two: PrintWriter.print has no arg_index, so it falls to default_arg_index: 0,
    // while sendError declares arg_index: 1 explicitly. If the legacy hook were reachable, both
    // would resolve to 1 and the second assertion would fail. The yaml is live; the override is
    // not.
    //
    // (Both values are individually defensible — PrintWriter.print(String) really does carry the
    // payload at user-arg 0 — which is exactly why "the override disagrees with the yaml" is not a
    // usable way to detect this, and the reachability rule has to be documented instead.)
    assertThat(xss.argIndexFor("java.io.PrintWriter.print/1"))
        .as("PrintWriter.print carries taint at user-arg 0 via the family default")
        .isZero();
    assertThat(xss.argIndexFor("javax.servlet.http.HttpServletResponse.sendError/2"))
        .as("sendError declares an explicit arg_index: 1 that overrides the family default")
        .isEqualTo(1);
  }

  @Test
  void everyMatchedPatternResolvesToAUsableArgIndex() {
    // The load-bearing half of the reachability argument: for every pattern in every shipped
    // family, a callee that the pattern matches resolves to either a non-negative index or the
    // explicit receiver sentinel. Neither value can reach the legacy sinkArgumentIndex hook,
    // because TaintedCheckerBase.patternArgIndex returns both directly.
    //
    // The probe is derived from the pattern's own compiled regex rather than from a hand-written
    // parser: substitute a matching string for the arity group and ask the pattern to match it.
    // If the probe does not match its own pattern the probe is wrong, not the engine — so that is
    // a hard failure rather than a skip.
    int probed = 0;
    for (SinkFamily f : ConfigLoader.loadFromClasspath().sinkFamilies().all()) {
      for (SinkFamily.SinkPattern p : f.patterns()) {
        String probe = probeSignature(p);
        if (probe == null) continue;
        probed++;
        assertThat(p.compiled().matcher(probe).find())
            .as("probe %s must match its own pattern %s in family %s", probe, p.pattern(), f.name())
            .isTrue();
        int idx = f.argIndexFor(probe);
        assertThat(idx == SinkFamily.RECEIVER_ARG_INDEX || idx >= 0)
            .as(
                "family %s resolved arg index %d for %s; only >= 0 or the receiver sentinel may"
                    + " reach the sink path, anything else falls through to the legacy hook",
                f.name(), idx, probe)
            .isTrue();
      }
    }
    assertThat(probed).as("the probe must actually have exercised patterns").isGreaterThan(300);
  }

  /**
   * Derives a callee signature guaranteed to match the given pattern by substituting {@code /0} for
   * the trailing arity group that every sink pattern ends with. Returns null for patterns with no
   * arity group, which cannot be probed this way.
   */
  private static String probeSignature(SinkFamily.SinkPattern p) {
    String regex = p.pattern();
    int group = regex.lastIndexOf("(");
    if (group < 0) return null;
    // Keep the leading anchored literal name, which always ends in '.method'.
    String head = regex.substring(0, group).replace("\\", "").replace("^", "");
    if (!head.matches("[A-Za-z_$][A-Za-z0-9_$.]*\\.[A-Za-z_$][A-Za-z0-9_$]*")) return null;
    return head + "/0";
  }

  @Test
  void explicitPatternArgIndexWinsOverFamilyDefault() {
    // deserialization.sinks marks the zero-arg deserializers as receiver-tainted (arg_index: -2)
    // even though the family has no default_arg_index. An explicit pattern index must not be
    // clobbered by the family default.
    assertThat(deserialization.argIndexFor("java.io.ObjectInputStream.readObject/0"))
        .isEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
    assertThat(deserialization.argIndexFor("java.io.ObjectInputStream.readUnshared/0"))
        .isEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  void nonDeserializationSinksInTheSameFamilyKeepTheFamilyDefault() {
    // ObjectMapper.readValue takes the untrusted data as a real argument, so it must NOT be
    // treated as receiver-tainted. This guards against over-broad sentinel propagation within a
    // family once one pattern opts into arg_index: -2.
    assertThat(
            deserialization.argIndexFor("com.fasterxml.jackson.databind.ObjectMapper.readValue/1"))
        .isNotEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  void unmatchedCalleeYieldsMinusOneWhichIsTheOnlyWayToReachTheLegacyHook() {
    // The single remaining path into sinkArgumentIndex. Not reachable from a matched family,
    // but documented here so the contract is stated in one place.
    assertThat(xss.argIndexFor("java.lang.String.notASink/0")).isEqualTo(-1);
  }

  @Test
  void taintSourceFamiliesResolveWithoutAPositiveArgIndex() {
    // Taint sources are read positionally; they carry no default_arg_index in the yaml and must
    // still resolve through the framework default rather than tripping the legacy hook.
    SinkFamily web = family("taint.sources.web");
    assertThat(web.argIndexFor("javax.servlet.http.HttpServletRequest.getParameter/1")).isZero();
    assertThat(web.defaultArgIndex()).isZero();
  }

  private static SinkFamily family(String name) {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    return cfg.sinkFamilies()
        .byName(name)
        .orElseThrow(() -> new AssertionError("sink family missing from yaml: " + name));
  }
}
