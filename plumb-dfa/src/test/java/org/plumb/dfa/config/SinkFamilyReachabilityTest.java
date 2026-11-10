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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A sink family with a pattern set but NO watcher is dead configuration.
 *
 * <p><b>The defect this pins.</b> A sink family is a set of patterns; a checker is what looks at
 * them. The pattern matches a call, and then the match leads nowhere, because no checker declares
 * that family. The defect class cannot produce a finding at all -- and nothing says so: the
 * configuration looks complete, and the coverage report counts the family as a capability.
 *
 * <p><b>How the gap was found.</b> By asking which families are named by no checker and no rule,
 * and then confirming the answer by searching the whole tree for each family key. Twenty-nine
 * families came back. Twelve of them were produced by the rule-pack migration, so their patterns
 * were real and their CWEs were real -- but nothing watched them.
 *
 * <p><b>What this test asserts.</b> For each of the twelve: the family's own patterns still match
 * the API they name (the migration did not corrupt them), AND a checker exists that watches the
 * family. The first half is easy to break by "fixing" the family; the second is easy to break by
 * forgetting to register the checker.
 */
class SinkFamilyReachabilityTest {

  /**
   * The families that carried a pattern set with no watcher, and the API each one names.
   *
   * <p><b>Every API here was MEASURED.</b> Each was found by asking the registry which families a
   * known API reaches, not by reading the pattern list by eye: a pattern's text and the API it
   * actually matches are two different things, and a test written from the text would assert a
   * family matches an API it does not.
   */
  private static final Map<String, String> REACHABLE_NOW =
      new LinkedHashMap<>() {
        {
          put("code-injection.sinks", "javax.script.ScriptEngine.eval/1");
          put("cookie.sinks", "javax.servlet.http.Cookie.setValue/1");
          put("credential.sinks", "javax.mail.Transport.send/1");
          put("dos.sinks", "java.lang.Thread.sleep/1");
          put("formula-injection.sinks", "org.apache.poi.ss.usermodel.Cell.setCellFormula/1");
          put("header-injection.sinks", "javax.mail.Message.setSubject/1");
          put("insecure-comms.sinks", "javax.net.ssl.SSLParameters.setEnabledCipherSuites/1");
          put("insecure-random.sinks", "java.util.Random.setSeed/1");
          put("log-injection.sinks", "javax.servlet.GenericServlet.log/1");
          put("privacy.sinks", "java.util.Properties.store/2");
          put("resource-injection.sinks", "javax.mail.Transport.send/1");
          put("setting-manipulation.sinks", "java.lang.System.setProperty/2");
        }
      };

  /**
   * The checkers that watch the twelve families.
   *
   * <p>The key is the CONFIG key -- the name the checker is registered under in checkers.yaml --
   * because that is where a checker's language scope is declared. It is NOT what turns the checker
   * on: the scan builds the checker list, and a checker the configuration does not catalogue has
   * declared no scope to contradict, so it stays. Whether the scan BUILDS a checker is asserted
   * where the scan lives, in plumb-main.
   */
  private static final Map<String, String> WATCHER_BY_FAMILY =
      new LinkedHashMap<>() {
        {
          put("code-injection.sinks", "CodeInjection");
          put("cookie.sinks", "CookieInjection");
          put("credential.sinks", "CredentialExposure");
          put("dos.sinks", "DenialOfService");
          put("formula-injection.sinks", "FormulaInjection");
          put("header-injection.sinks", "HeaderInjection");
          put("insecure-comms.sinks", "InsecureCommunication");
          put("insecure-random.sinks", "InsecureRandomFamily");
          put("log-injection.sinks", "LogInjectionFamily");
          put("privacy.sinks", "PrivacyViolation");
          put("resource-injection.sinks", "ResourceInjection");
          put("setting-manipulation.sinks", "SettingManipulation");
        }
      };

  private SinkFamilyRegistry load() {
    return ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  /** The family names a callee signature reaches. */
  private Set<String> familiesFor(String calleeSignature) {
    return load().lookupAll(calleeSignature, SourceLanguage.JAVA).stream()
        .map(SinkFamily::name)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("every family the migration produced has a checker that watches it")
  void everyMigratedFamilyHasAWatcher() {
    // The twelve families the migration fills, and the checker that watches each.
    // A family with no watcher is dead configuration, whatever its patterns say.
    Map<String, String> expected = new LinkedHashMap<>(REACHABLE_NOW);
    expected.keySet().removeIf(f -> !WATCHER_BY_FAMILY.containsKey(f));

    List<String> unwatched = new ArrayList<>();
    for (String family : expected.keySet()) {
      if (!watcherExists(family)) {
        unwatched.add(family);
      }
    }

    assertThat(unwatched).as("families with patterns but no checker: %s", unwatched).isEmpty();
  }

  @Test
  @DisplayName("the twelve families still match the APIs they name")
  void theTwelveFamiliesStillMatchTheirApis() {
    // The other half of the contract: a family that is watched but whose
    // patterns were broken by a "cleanup" is just as dead.
    List<String> unmatched = new ArrayList<>();
    for (Map.Entry<String, String> e : REACHABLE_NOW.entrySet()) {
      if (!familiesFor(e.getValue()).contains(e.getKey())) {
        unmatched.add(e.getKey() + " does not match " + e.getValue());
      }
    }

    assertThat(unmatched).as("families whose patterns no longer match: %s", unmatched).isEmpty();
  }

  @Test
  @DisplayName("the twelve families are not the same defect class as an existing one")
  void theTwelveFamiliesAreDistinctFromExistingOnes() {
    // The families must not collide with a family that already had a checker:
    // two checkers on one family means one of them can never fire.
    Set<String> alreadyWatched =
        Set.of(
            "sql.sinks",
            "ldap.sinks",
            "command.sinks",
            "format-string.sinks",
            "xss.sinks",
            "log.sinks",
            "ssrf.sinks",
            "xxe.sinks",
            "deserialization.sinks",
            "weak-crypto.sinks",
            "regex.sinks",
            "open-redirect.sinks",
            "file-path.sinks",
            "expression-injection.sinks",
            "json-injection.sinks",
            "nosql.sinks",
            "xml-injection.sinks",
            "xpath.sinks",
            "template-injection.sinks",
            "trust-boundary.sinks");

    List<String> collisions = new ArrayList<>();
    for (String family : REACHABLE_NOW.keySet()) {
      if (alreadyWatched.contains(family)) {
        collisions.add(family);
      }
    }

    assertThat(collisions).as("families that would double-watch an existing one").isEmpty();
  }

  @Test
  @DisplayName("a family that a checker watches has the checker registered in the config")
  void everyWatcherIsConfigured() {
    // The configuration records the checker's language scope. A checker that is absent
    // from it still runs -- it simply declares no scope -- so this asserts the
    // bookkeeping, not the wiring.
    List<String> missing = new ArrayList<>();
    for (String watcher : new TreeSet<>(WATCHER_BY_FAMILY.values())) {
      if (ConfigLoader.loadFromClasspath().checkers().byName(watcher).isEmpty()) {
        missing.add(watcher);
      }
    }

    assertThat(missing).as("checkers that exist but are not configured: %s", missing).isEmpty();
  }

  /** Whether some checker declares the given family. */
  private boolean watcherExists(String family) {
    for (DfaChecker checker : newCheckers()) {
      if (checker instanceof TaintedCheckerBase taint) {
        if (taint.rule() != null && family.equals(taint.rule().sinkFamilyKey())) {
          return true;
        }
      }
    }
    return false;
  }

  /** The checker instances this test inspects. */
  private static List<DfaChecker> newCheckers() {
    List<DfaChecker> out = new ArrayList<>();
    out.add(new org.plumb.dfa.checkers.taint.CodeInjectionChecker());
    out.add(new org.plumb.dfa.checkers.taint.CookieInjectionChecker());
    out.add(new org.plumb.dfa.checkers.taint.CredentialExposureChecker());
    out.add(new org.plumb.dfa.checkers.taint.DenialOfServiceChecker());
    out.add(new org.plumb.dfa.checkers.taint.FormulaInjectionChecker());
    out.add(new org.plumb.dfa.checkers.taint.HeaderInjectionChecker());
    out.add(new org.plumb.dfa.checkers.taint.InsecureCommunicationChecker());
    out.add(new org.plumb.dfa.checkers.taint.InsecureRandomFamilyChecker());
    out.add(new org.plumb.dfa.checkers.taint.LogInjectionFamilyChecker());
    out.add(new org.plumb.dfa.checkers.taint.PrivacyViolationChecker());
    out.add(new org.plumb.dfa.checkers.taint.ResourceInjectionChecker());
    out.add(new org.plumb.dfa.checkers.taint.SettingManipulationChecker());
    return out;
  }
}
