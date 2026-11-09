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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The rules are loaded PER LANGUAGE, and this is what that has to mean.
 *
 * <h2>The request</h2>
 *
 * <p>"I said C# on the command line; do not load the Java rules." That is a statement about
 * LOADING, not about findings: a Java rule that stays loaded is a rule that runs, that walks every
 * instruction, and that can report a finding about code the run was never asked to look at.
 *
 * <h2>Why the tests read the real catalogue</h2>
 *
 * <p>The obvious way to test this is with synthetic families and synthetic checkers -- and that is
 * exactly the way to write a test that passes while the product is broken. A synthetic fixture
 * proves the filter can drop a fixture. It says nothing about the 80-odd families and 38 checkers
 * that actually ship, and those are the ones the user runs.
 *
 * <p>So these tests load the SHIPPED catalogue through {@link ConfigLoader#loadFromClasspath()} and
 * assert against it. A new checker that forgets to declare its languages fails the completeness
 * test below, rather than silently running in every language.
 */
class LanguageScopedRuleLoadingTest {

  /** The catalogue as it actually ships. */
  private static EngineConfig shipped() {
    return ConfigLoader.loadFromClasspath();
  }

  @Test
  @DisplayName("every shipped checker declares the languages it applies to")
  void everyCheckerDeclaresItsLanguages() {
    List<String> undeclared =
        shipped().checkers().all().stream()
            .filter(c -> c.languages().isEmpty())
            .map(CheckerConfig::name)
            .sorted()
            .toList();

    assertThat(undeclared)
        .as(
            "a checker with no language declaration runs in every language. That is the behaviour "
                + "the request asks to remove, so every shipped checker must name its languages.")
        .isEmpty();
  }

  @Test
  @DisplayName("a language-neutral checker applies to a language nobody has written down yet")
  void aNeutralCheckerCoversEveryLanguage() {
    // The language-neutral checkers declare `all` rather than spelling the languages out one by
    // one. A language added tomorrow must find the rules waiting for it: spelling them out would
    // make the new language load ZERO checkers the day it arrives.
    CheckerConfig divideByZero = shipped().checkers().byName("DivideByZero").orElseThrow();
    assertThat(divideByZero.languages()).containsExactly("all");

    for (SourceLanguage language : SourceLanguage.values()) {
      if (language == SourceLanguage.UNKNOWN) continue;
      assertThat(divideByZero.appliesTo(language))
          .as("a language-neutral checker must apply to " + language)
          .isTrue();
    }
  }

  @Test
  @DisplayName("the wildcard survives the filter for a language nobody has written down")
  void theWildcardSurvivesTheFilter() {
    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.CSHARP));
    assertThat(filter.keepsRule(Set.of("all")))
        .as("`all` means every language, so a C# run keeps the checker")
        .isTrue();

    CheckerRegistry loaded = filter.filterCheckers(shipped().checkers());
    assertThat(loaded.byName("DivideByZero"))
        .as("a language-neutral checker must survive a C# run")
        .isPresent();
  }

  @Test
  @DisplayName("a checker that names only the JVM is not kept by a C# run")
  void anExplicitJvmScopeIsHonoured() {
    LanguageFilter filter = LanguageFilter.of(Set.of(SourceLanguage.CSHARP));
    assertThat(filter.keepsRule(Set.of("java", "kotlin", "scala")))
        .as("an explicit JVM scope must not survive a C# run")
        .isFalse();
  }

  @Test
  @DisplayName("a C# run loads no checker that names only the JVM")
  void csharpRunLoadsNoJvmOnlyChecker() {
    CheckerRegistry loaded =
        LanguageFilter.of(Set.of(SourceLanguage.CSHARP)).filterCheckers(shipped().checkers());

    List<String> survivors = loaded.all().stream().map(CheckerConfig::name).sorted().toList();

    assertThat(survivors)
        .as("a C# run must not load the Java rules")
        .doesNotContain(
            "UncheckedReturn", // a JavaParser AST checker: it needs a Java AST
            "SensitiveDataLeak", // hardcodes System.getenv / System.getProperty
            "FilePermission", // its family's patterns are java.io.File only
            "ServerSideTemplateInjection", // its family is Velocity-only
            "OrmLoadNullCheckDfa", // JPA / Hibernate
            "OrmUnnecessaryGetDfa", // JPA / Hibernate
            "OrmLostUpdateDfa", // JPA / Hibernate + a JDK type deny-list
            "SingletonRaceDfa", // Spring MVC / javax.servlet
            "ServletAtomicityDfa"); // javax.servlet session attributes

    assertThat(survivors)
        .as("the language-neutral checkers must still load: they are the C# rules")
        .contains(
            "DivideByZero",
            "IntegerOverflow",
            "NullDereference",
            "ResourceLeak",
            "BoundaryOverflow",
            "BoundaryUnderflow",
            "BoundaryDivZero",
            "BoundaryArrayBounds",
            "RegexInjection",
            "CodeInjection",
            "CredentialExposure");
  }

  @Test
  @DisplayName("a C# run loads the checkers whose families carry a C# pattern table")
  void csharpRunLoadsTheCrossLanguageCheckers() {
    CheckerRegistry loaded =
        LanguageFilter.of(Set.of(SourceLanguage.CSHARP)).filterCheckers(shipped().checkers());

    // These checkers watch families that DO carry a `by_language.csharp` table, so the catalogue
    // knows their C# symbols. Dropping them would leave the C# run with no rules at all.
    for (String name :
        List.of(
            "RegexInjection",
            "CodeInjection",
            "CookieInjection",
            "CredentialExposure",
            "DenialOfService",
            "FormulaInjection",
            "HeaderInjection",
            "InsecureCommunication",
            "InsecureRandomFamily",
            "LogInjectionFamily",
            "PrivacyViolation",
            "ResourceInjection",
            "SettingManipulation",
            "JsonInjection",
            "NoSqlInjection",
            "XmlInjection",
            "XQueryInjection",
            "XsltInjection",
            "HttpParameterPollution",
            "DangerousFileInclusion")) {
      assertThat(loaded.byName(name)).as("the checker must survive a C# run: " + name).isPresent();
    }
  }

  @Test
  @DisplayName("a Java run keeps the JVM-only checkers")
  void javaRunKeepsTheJvmCheckers() {
    CheckerRegistry loaded =
        LanguageFilter.of(Set.of(SourceLanguage.JAVA)).filterCheckers(shipped().checkers());

    for (String name :
        List.of(
            "UncheckedReturn",
            "SensitiveDataLeak",
            "FilePermission",
            "ServerSideTemplateInjection",
            "OrmLoadNullCheckDfa",
            "SingletonRaceDfa")) {
      assertThat(loaded.byName(name))
          .as("a Java run must keep the Java rules: " + name)
          .isPresent();
    }
  }

  @Test
  @DisplayName("a run with no language selected loads every checker")
  void anUnselectedRunLoadsEverything() {
    CheckerRegistry loaded = LanguageFilter.all().filterCheckers(shipped().checkers());

    assertThat(loaded.all()).hasSameSizeAs(shipped().checkers().all());
  }

  @Test
  @DisplayName("every shipped family that names only JVM symbols says so")
  void jvmOnlyFamiliesDeclareThemselves() {
    List<String> unrestricted =
        shipped().sinkFamilies().all().stream()
            .filter(f -> f.languages().isEmpty())
            .map(f -> f.name())
            .sorted()
            .toList();

    // These are the families the catalogue leaves open. Each one either carries a C# pattern table
    // or names symbols that exist on more than one platform. The list is asserted so that a
    // family which BECOMES JVM-only has to be added here deliberately.
    assertThat(unrestricted)
        .contains(
            "sql.sinks",
            "command.sinks",
            "cookie.sinks",
            "credential.sinks",
            "regex.sinks",
            "code-injection.sinks",
            "xss.sinks",
            "weak-crypto.sinks");
  }

  @Test
  @DisplayName("a C# run drops the families whose patterns are JVM-only")
  void csharpRunDropsTheJvmOnlyFamilies() {
    SinkFamilyRegistry loaded =
        LanguageFilter.of(Set.of(SourceLanguage.CSHARP)).filterFamilies(shipped().sinkFamilies());

    assertThat(loaded.names())
        .as("a family whose every pattern is a JVM symbol cannot fire on C#")
        .doesNotContain(
            "jni.sinks",
            "jcr.sinks",
            "jsp-include.sinks",
            "expression-injection.sinks",
            "template-injection.sinks",
            "file-permission.sinks");

    assertThat(loaded.names())
        .as("the families with a C# table must survive")
        .contains(
            "sql.sinks",
            "command.sinks",
            "cookie.sinks",
            "regex.sinks",
            // The source families carry a C# table too: `System.Console.ReadLine`,
            // `System.Environment.GetEnvironmentVariable`, the .NET file reads
            // and the ASP.NET Core request. Without them a C# run has no
            // source at all, so every sink value would be untainted and the
            // scan would report nothing -- the failure that looks like "the
            // code is clean".
            "taint.sources.web",
            "taint.sources.io",
            "taint.sources.network",
            "taint.sources.console",
            "taint.sources.env");
  }

  @Test
  @DisplayName("a family's per-language table is reachable from the language it names")
  void everyPerLanguageTableIsReachable() {
    // A family that names a language in its per-language table but not in its scope has a table
    // that can never match: `appliesTo` gates the WHOLE family, so the table is dead weight.
    //
    // This is not a theoretical failure. The catalogue carried a Go table on a family whose scope
    // named only the JVM, and the ten Go patterns in it never fired -- a false negative that looks
    // exactly like "the code is clean".
    List<String> unreachable = new java.util.ArrayList<>();
    for (SinkFamily family : shipped().sinkFamilies().all()) {
      for (SourceLanguage language : family.languageRules().languages()) {
        if (!family.appliesTo(language)) {
          unreachable.add(family.name() + "." + language);
        }
      }
    }

    assertThat(unreachable)
        .as(
            "a per-language table whose language is not in the family's scope can never match: "
                + "the scope gates the whole family")
        .isEmpty();
  }

  @Test
  @DisplayName("a checker's declared languages are the ones its rule names")
  void theConfigScopeAndTheRuleScopeAgree() {
    // A checker whose configuration names only the JVM is Java-only even when the family it
    // watches spans every language: the configuration is what says what the checker can SEE.
    CheckerConfig uncheckedReturn = shipped().checkers().byName("UncheckedReturn").orElseThrow();
    assertThat(uncheckedReturn.languages()).containsExactly("java", "kotlin", "scala");
    assertThat(uncheckedReturn.appliesTo(SourceLanguage.JAVA)).isTrue();
    assertThat(uncheckedReturn.appliesTo(SourceLanguage.CSHARP)).isFalse();

    CheckerConfig regex = shipped().checkers().byName("RegexInjection").orElseThrow();
    assertThat(regex.languages()).containsExactly("all");
    assertThat(regex.appliesTo(SourceLanguage.CSHARP)).isTrue();
  }
}
