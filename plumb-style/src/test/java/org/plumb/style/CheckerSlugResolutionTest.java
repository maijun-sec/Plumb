/*
 * Verifies the rule-catalog invariants that keep a user-facing rule slug
 * pointing at the rule that actually owns it.
 *
 * Background:
 *   StyleContext.setCurrentRule(c.name()) uses the checker's slug as the
 *   parameter-override scope key, and a user writes that same slug in
 *   `checks.<slug>.params.<name>` or passes it to `--rule`. If two checkers
 *   share a slug, or a slug is not resolvable to its own rule, an override
 *   lands on the wrong rule or nowhere at all -- silently, with no error.
 *
 * The three invariants asserted here:
 *   I1  No two AST checkers share a slug.
 *   I2  Every checker slug resolves to that checker's OWN rule.
 *   I3  Every checker that declares parameters can read its declared default
 *       through its own slug (the path StyleContext.paramOverride uses).
 */
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.rules.RuleRegistry;

class CheckerSlugResolutionTest {

  private static RuleRegistry registryWithAllCheckers() {
    RuleRegistry registry = new RuleRegistry().withBuiltinDefaults(true);
    for (AstChecker checker : new AstCheckerRegistry().builtins().all()) {
      registry.registerFromChecker(checker);
    }
    return registry;
  }

  private static List<AstChecker> allCheckers() {
    return new ArrayList<>(new AstCheckerRegistry().builtins().all());
  }

  @Test
  void noTwoCheckersShareTheSameSlug() {
    Map<String, List<String>> slugToCheckers = new LinkedHashMap<>();
    for (AstChecker checker : allCheckers()) {
      slugToCheckers
          .computeIfAbsent(checker.name(), k -> new ArrayList<>())
          .add(checker.getClass().getName());
    }

    Map<String, List<String>> collisions = new LinkedHashMap<>();
    slugToCheckers.forEach(
        (slug, owners) -> {
          if (owners.size() > 1) collisions.put(slug, owners);
        });

    assertThat(collisions)
        .as(
            "Two checkers sharing a slug share one parameter-override scope, so a "
                + "checks.<slug>.params.* override silently applies to both, and the "
                + "registry can only resolve the slug to one of them.")
        .isEmpty();
  }

  @Test
  void everyCheckerSlugResolvesToItsOwnRule() {
    RuleRegistry registry = registryWithAllCheckers();

    List<String> unresolved = new ArrayList<>();
    for (AstChecker checker : allCheckers()) {
      RuleDef own = checker.rule();
      if (own == null || own.ruleId() == null) continue;
      RuleDef resolved = registry.byAlias(checker.name()).orElse(null);
      if (resolved == null || !resolved.ruleId().id().equals(own.ruleId().id())) {
        unresolved.add(
            checker.name()
                + " (id="
                + own.ruleId().id()
                + ") resolved to "
                + (resolved == null ? "nothing" : resolved.ruleId().id()));
      }
    }

    assertThat(unresolved)
        .as(
            "A checker slug that does not resolve to its own rule makes the slug's "
                + "parameter scope point at a foreign rule, so declared defaults are "
                + "unreachable and overrides land in the wrong place.")
        .isEmpty();
  }

  @Test
  void declaredParameterDefaultsAreReadableThroughTheCheckerSlug() {
    RuleRegistry registry = registryWithAllCheckers();

    List<String> unreachable = new ArrayList<>();
    for (AstChecker checker : allCheckers()) {
      RuleDef own = checker.rule();
      if (own == null || own.ruleId() == null) continue;
      for (RuleParameter parameter : own.parameters()) {
        Class<?> type =
            switch (parameter.type()) {
              case INT -> Integer.class;
              case BOOLEAN -> Boolean.class;
              default -> String.class;
            };
        Object viaSlug = registry.param(checker.name(), parameter.name(), type);
        if (viaSlug == null) {
          unreachable.add(
              checker.name() + "." + parameter.name() + " (" + own.ruleId().id() + ")");
        }
      }
    }

    assertThat(unreachable)
        .as(
            "StyleContext.paramOverride falls back to RuleRegistry.param(slug, ...) "
                + "for the declared default. A null result means the checker silently "
                + "uses its own hard-coded fallback instead of the declared value.")
        .isEmpty();
  }

  @Test
  void aSlugOwnedByOneCheckerIsNotClaimedByAnother() {
    // The two loose-coupling rules used to both answer to "loose-coupling".
    // The type-level rule now owns "loose-type-coupling" and the package-level
    // rule owns "loose-package-coupling"; neither may claim the other's.
    RuleRegistry registry = registryWithAllCheckers();

    RuleDef typeLevel = registry.byAlias("loose-type-coupling").orElse(null);
    RuleDef packageLevel = registry.byAlias("loose-package-coupling").orElse(null);

    assertThat(typeLevel).isNotNull();
    assertThat(packageLevel).isNotNull();
    assertThat(typeLevel.ruleId().id()).isEqualTo("ast-design-loose-coupling");
    assertThat(packageLevel.ruleId().id()).isEqualTo("ast-design-loose-coupling-2");
    assertThat(typeLevel.ruleId().id()).isNotEqualTo(packageLevel.ruleId().id());
  }

  @Test
  void aCanonicalIdIsNeverShadowedByAnotherRulesAlias() {
    // An alias is a compatibility mechanism; a canonical id is what a finding is
    // actually emitted with. The canonical id must win.
    RuleRegistry registry = registryWithAllCheckers();

    RuleDef byId = registry.byId("ast-design-loose-coupling").orElse(null);
    RuleDef byAlias = registry.byAlias("ast-design-loose-coupling").orElse(null);

    assertThat(byId).isNotNull();
    assertThat(byAlias).isNotNull();
    assertThat(byAlias.ruleId().id()).isEqualTo(byId.ruleId().id());
  }

  @Test
  void aForeignToolAliasStillResolvesToItsOwningRule() {
    // "SonarQube:java:S00108" is a foreign id that several equivalent Plumb
    // rules declare. It must still resolve to one of them, not to nothing.
    RuleRegistry registry = registryWithAllCheckers();

    assertThat(registry.byAlias("SonarQube:java:S00108")).isPresent();
  }

  @Test
  void anOverrideWrittenUnderTheSlugReachesTheOwningRule() {
    // End-to-end: the YAML shape a user writes
    //
    //   checks:
    //     loose-type-coupling:
    //       params:
    //         allowedConcreteTypes: HashMap
    //
    // is resolved through the same composite key StyleContext builds from the
    // checker slug, so the override must land on the loose-type-coupling rule
    // and not on the loose-package-coupling rule that used to own the slug.
    RuleRegistry registry = registryWithAllCheckers();

    Map<String, Map<String, Object>> ruleParams = new HashMap<>();
    Map<String, Object> looseCoupling = new HashMap<>();
    looseCoupling.put("allowedConcreteTypes", "HashMap");
    ruleParams.put("loose-type-coupling", looseCoupling);

    ParsedSource parsed =
        new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    StyleContext context = new StyleContext(parsed, registry, ruleParams);
    context.setCurrentRule("loose-type-coupling");

    assertThat(context.paramOverride("allowedConcreteTypes", String.class)).isEqualTo("HashMap");
  }

  @Test
  void anOverrideWrittenUnderOneSlugDoesNotLeakIntoAnotherRule() {
    // The regression this file guards: two rules shared the slug
    // "loose-coupling", so an override written for one was applied to both.
    RuleRegistry registry = registryWithAllCheckers();

    Map<String, Map<String, Object>> ruleParams = new HashMap<>();
    Map<String, Object> looseCoupling = new HashMap<>();
    looseCoupling.put("allowedConcreteTypes", "HashMap");
    ruleParams.put("loose-type-coupling", looseCoupling);

    ParsedSource parsed =
        new JavaSourceParser().parseFromString(Path.of("Test.java"), "class T {}");
    StyleContext context = new StyleContext(parsed, registry, ruleParams);

    // The package-level rule has its own slug and its own (absent) override.
    context.setCurrentRule("loose-package-coupling");
    assertThat(context.paramOverride("allowedConcreteTypes", String.class)).isNull();
  }

  // ---------------------------------------------------------------------
  // Registry-level behaviour, pinned with synthetic rules.
  //
  // The end-state assertions above can only fail while a collision exists.
  // Once the colliding slugs are renamed they stay green even if the registry
  // hardening is reverted, so the hardening itself needs its own tests. These
  // reproduce the defect shapes with synthetic rules, which is what makes a
  // future regression detectable.
  // ---------------------------------------------------------------------

  private static RuleDef syntheticRule(
      String id, String name, List<String> aliases, List<RuleParameter> params) {
    return new RuleDef(
        new RuleId(id, name),
        "synthetic rule for slug resolution",
        RuleDef.Severity.LOW,
        RuleDef.Type.STYLE,
        List.of(),
        "org.plumb.test.Synthetic",
        true,
        aliases,
        params);
  }

  @Test
  void anAliasCannotShadowAnotherRulesCanonicalId() {
    // "ast-other-rule" is the canonical id of one rule and an alias of another.
    // The canonical id must win, otherwise the first rule becomes unreachable.
    RuleRegistry registry = new RuleRegistry();
    registry.register(syntheticRule("ast-other-rule", "OTHER_RULE", List.of(), List.of()));
    registry.register(syntheticRule("ast-hijacker", "HIJACKER", List.of("ast-other-rule"), List.of()));

    assertThat(registry.byAlias("ast-other-rule"))
        .isPresent()
        .get()
        .extracting(d -> d.ruleId().id())
        .isEqualTo("ast-other-rule");
  }

  @Test
  void aSharedForeignAliasResolvesToOneOfItsOwners() {
    // A foreign tool id may legitimately be declared by several equivalent
    // rules. It must still resolve to one of them.
    RuleRegistry registry = new RuleRegistry();
    registry.register(syntheticRule("ast-first", "FIRST", List.of("SonarQube:java:S00108"), List.of()));
    registry.register(syntheticRule("ast-second", "SECOND", List.of("SonarQube:java:S00108"), List.of()));

    RuleDef resolved = registry.byAlias("SonarQube:java:S00108").orElse(null);
    assertThat(resolved).isNotNull();
    assertThat(resolved.ruleId().id()).isIn("ast-first", "ast-second");
  }

  @Test
  void aSharedForeignAliasResolvesToTheFirstRegisteredOwner() {
    // A foreign tool id may legitimately be declared by several equivalent
    // rules. It must resolve to one of them, and to the same one on every
    // lookup (no order-of-lookup flakiness).
    RuleRegistry registry = new RuleRegistry();
    registry.register(syntheticRule("ast-first", "FIRST", List.of("SonarQube:java:S00108"), List.of()));
    registry.register(syntheticRule("ast-second", "SECOND", List.of("SonarQube:java:S00108"), List.of()));

    RuleDef first = registry.byAlias("SonarQube:java:S00108").orElse(null);
    RuleDef second = registry.byAlias("SonarQube:java:S00108").orElse(null);
    assertThat(first).isNotNull();
    assertThat(first.ruleId().id()).isEqualTo("ast-first");
    assertThat(second).isNotNull();
    assertThat(second.ruleId().id()).isEqualTo("ast-first");
  }

  @Test
  void aCheckerSlugIsClaimedEvenWhenTheRuleDoesNotListIt() {
    // The registry derives the slug from the checker itself, so a checker
    // cannot forget to list its own slug in the rule's alias list.
    RuleRegistry registry = new RuleRegistry();
    registry.registerFromChecker(new SlugOnlyChecker());

    assertThat(registry.byAlias("my-checker-slug"))
        .isPresent()
        .get()
        .extracting(d -> d.ruleId().id())
        .isEqualTo("ast-synthetic-slug-only");
  }

  @Test
  void aCheckerSlugIsClaimedByTheRuleThatOwnsIt() {
    // Two checkers, two slugs. Each slug must land on its own rule even
    // though neither rule lists the other's slug.
    RuleRegistry registry = new RuleRegistry();
    registry.registerFromChecker(new SlugOnlyChecker());
    registry.registerFromChecker(new OtherSlugOnlyChecker());

    assertThat(registry.byAlias("my-checker-slug"))
        .isPresent()
        .get()
        .extracting(d -> d.ruleId().id())
        .isEqualTo("ast-synthetic-slug-only");
    assertThat(registry.byAlias("other-checker-slug"))
        .isPresent()
        .get()
        .extracting(d -> d.ruleId().id())
        .isEqualTo("ast-synthetic-other-slug");
  }

  /**
   * A checker whose rule deliberately does not list its own slug as an alias.
   *
   * <p>The class is {@code public} on purpose: {@link RuleRegistry#registerFromChecker} invokes
   * {@code rule()} and {@code name()} reflectively, and a package-private or private checker
   * class makes that call fail with an access error rather than a rule problem.
   */
  public static final class SlugOnlyChecker implements AstChecker {
    private static final RuleDef RULE =
        syntheticRule("ast-synthetic-slug-only", "SYNTHETIC_SLUG_ONLY", List.of(), List.of());

    @Override
    public String name() {
      return "my-checker-slug";
    }

    @Override
    public void init(StyleContext ctx) {}

    @Override
    public RuleDef rule() {
      return RULE;
    }
  }

  /** A second checker with a different slug, also not listed in its aliases. */
  public static final class OtherSlugOnlyChecker implements AstChecker {
    private static final RuleDef RULE =
        syntheticRule("ast-synthetic-other-slug", "SYNTHETIC_OTHER_SLUG", List.of(), List.of());

    @Override
    public String name() {
      return "other-checker-slug";
    }

    @Override
    public void init(StyleContext ctx) {}

    @Override
    public RuleDef rule() {
      return RULE;
    }
  }
}
