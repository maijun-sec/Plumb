/*
 * R-PATCH v2 → C-1 (audit 2026-10-08): rewritten to drop the
 * dependency on `RuleRegistry.fromResources(classpath)` —
 * the JSON metadata under `rules/<domain>/<name>.json` was
 * removed because every entry's `checker.class` FQCN was
 * stale (the package refactor moved classes into
 * sub-directories without updating the JSON).  Alias
 * resolution is now exercised on the `withBuiltinDefaults()`
 * registry directly, plus a programmatic RuleDef that
 * carries an alias ? same code path, no classpath scan.
 *
 * The four behaviours verified here:
 *   1. legacy rule_id resolves to the canonical plumb.<d>.<n>
 *   2. canonical rule_id stays canonical
 *   3. unknown rule_id passes through unchanged
 *   4. null registry preserves legacy behavior (no rewrite)
 */
package org.plumb.dfa.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleRegistry;

class FindingReporterAliasTest {

  private static RuleRegistry newRegistryWithAlias() {
    // Use withBuiltinDefaults() so the registry has the
    // pre-defined rules, then add a synthetic entry that
    // mirrors the historical JSON shape (canonical id +
    // an alias that legacy findings use).
    RuleDef boundary =
        new RuleDef(
            new RuleId("dfa-boundary-integer-overflow", "INTEGER_OVERFLOW"),
            "Integer arithmetic may overflow (Z3 range analysis).",
            RuleDef.Severity.MEDIUM,
            RuleDef.Type.CORRECTNESS,
            List.of("CWE-190"),
            "org.plumb.dfa.checkers.boundary.OverflowConstraintChecker",
            true,
            List.of("loose.numeric.integer-overflow", "plumb.numeric.integer-overflow"));
    return new RuleRegistry().withBuiltinDefaults().register(boundary);
  }

  @Test
  void legacyRuleIdIsResolvedToCanonical() {
    RuleRegistry registry = newRegistryWithAlias();
    assertThat(registry.byId("dfa-boundary-integer-overflow")).isPresent();
    assertThat(registry.hasAlias("loose.numeric.integer-overflow")).isTrue();

    FindingReporter reporter = new FindingReporter();
    reporter.setRuleRegistry(registry);

    Finding f =
        Finding.violation(
            "TestChecker",
            "loose.numeric.integer-overflow",
            "msg",
            RuleDef.Severity.MEDIUM,
            "CWE-190",
            42,
            5,
            "Foo()V",
            "");
    reporter.report(f);

    var all = reporter.all();
    assertThat(all).hasSize(1);
    assertThat(all.get(0).ruleId()).isEqualTo("dfa-boundary-integer-overflow");
  }

  @Test
  void canonicalRuleIdStaysCanonical() {
    RuleRegistry registry = newRegistryWithAlias();
    FindingReporter reporter = new FindingReporter();
    reporter.setRuleRegistry(registry);

    Finding f =
        Finding.violation(
            "TestChecker",
            "dfa-boundary-integer-overflow",
            "msg",
            RuleDef.Severity.MEDIUM,
            "CWE-190",
            42,
            5,
            "Foo()V",
            "");
    reporter.report(f);
    assertThat(reporter.all().get(0).ruleId()).isEqualTo("dfa-boundary-integer-overflow");
  }

  @Test
  void unknownRuleIdStaysUnknown() {
    RuleRegistry registry = newRegistryWithAlias();
    FindingReporter reporter = new FindingReporter();
    reporter.setRuleRegistry(registry);

    Finding f =
        Finding.violation(
            "TestChecker",
            "some.legacy.id.not.in.registry",
            "msg",
            RuleDef.Severity.MEDIUM,
            "CWE-000",
            42,
            5,
            "Foo()V",
            "");
    reporter.report(f);
    assertThat(reporter.all().get(0).ruleId()).isEqualTo("some.legacy.id.not.in.registry");
  }

  @Test
  void nullRegistryPreservesLegacyBehavior() {
    FindingReporter reporter = new FindingReporter();
    Finding f =
        Finding.violation(
            "TestChecker",
            "loose.numeric.integer-overflow",
            "msg",
            RuleDef.Severity.MEDIUM,
            "CWE-190",
            42,
            5,
            "Foo()V",
            "");
    reporter.report(f);
    assertThat(reporter.all().get(0).ruleId()).isEqualTo("loose.numeric.integer-overflow");
  }
}
