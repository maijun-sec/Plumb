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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.tac.ir.SourceLanguage;

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
    // family, a callee that the pattern matches resolves to a position the sink path can use.
    // There are three such positions: a non-negative index, the receiver sentinel, and the
    // last-argument sentinel. None of them can reach the legacy sinkArgumentIndex hook,
    // because TaintedCheckerBase.patternArgIndex returns all three directly.
    //
    // The probe is derived from the pattern's own compiled regex rather than from a hand-written
    // parser: substitute a matching string for the arity group and ask the pattern to match it.
    // If the probe does not match its own pattern the probe is wrong, not the engine — so that is
    // a hard failure rather than a skip.
    //
    // The per-language buckets are walked too, and resolved WITH their language: a bucket's
    // pattern is not in the family's shared table, so a language-neutral lookup would not find
    // it, and the contract would hold for the shared patterns only.
    int probed = 0;
    for (SinkFamily f : ConfigLoader.loadFromClasspath().sinkFamilies().all()) {
      for (SinkFamily.SinkPattern p : f.patterns()) {
        probed += assertUsableArgIndex(f, p, null);
      }
      for (SourceLanguage language : f.languageRules().languages()) {
        for (SinkFamily.SinkPattern p :
            f.languageRules().patternsForExactly(language).orElse(java.util.List.of())) {
          probed += assertUsableArgIndex(f, p, language);
        }
      }
    }
    assertThat(probed).as("the probe must actually have exercised patterns").isGreaterThan(300);
  }

  /**
   * Assert that the pattern's own probe resolves to a position the sink path can use, and report
   * how many patterns were exercised.
   */
  private static int assertUsableArgIndex(
      SinkFamily f, SinkFamily.SinkPattern p, SourceLanguage language) {
    String probe = probeSignature(p);
    if (probe == null) return 0;
    assertThat(p.compiled().matcher(probe).find())
        .as("probe %s must match its own pattern %s in family %s", probe, p.pattern(), f.name())
        .isTrue();
    int idx = language == null ? f.argIndexFor(probe) : f.argIndexFor(probe, language);
    assertThat(idx == SinkFamily.RECEIVER_ARG_INDEX || idx == SinkFamily.LAST_ARG_INDEX || idx >= 0)
        .as(
            "family %s resolved arg index %d for %s; only >= 0, the receiver sentinel or the"
                + " last-argument sentinel may reach the sink path, anything else falls through"
                + " to the legacy hook",
            f.name(), idx, probe)
        .isTrue();
    return 1;
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
    //
    // The escapes have to be UNESCAPED, not stripped. Deleting the backslash
    // of a word boundary leaves the letter b, so the probe for
    // `^builtins\.print\b(/\d+)?` used to be `builtins.printb/0` -- a probe
    // its own pattern does not match, which says nothing about the pattern
    // and fails the check for the wrong reason.
    //
    // A word boundary is a zero-width assertion, so dropping it leaves the
    // text the pattern matches. An escaped dot is a separator of a dotted
    // name. Any OTHER escape makes the head a regex rather than a name, and
    // a name cannot be turned into a probe.
    String head = regex.substring(0, group).replace("\\b", "").replace("\\.", ".");
    if (head.contains("\\")) return null;
    String plain = head.replace("^", "");
    if (!plain.matches("[A-Za-z_$][A-Za-z0-9_$.]*\\.[A-Za-z_$][A-Za-z0-9_$]*")) return null;
    return plain + "/0";
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
