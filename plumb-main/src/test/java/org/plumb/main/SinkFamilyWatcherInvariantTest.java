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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.taint.TaintedCheckerBase;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A sink family that no checker watches is dead configuration.
 *
 * <p><b>The defect this pins.</b> A sink family is a set of patterns; a checker is what looks at
 * them. The pattern matches a call, and then the match leads nowhere, because no checker declares
 * that family. The defect class cannot produce a finding at all -- and nothing says so: the
 * configuration looks complete, and a coverage report counts the family as a capability it does not
 * have.
 *
 * <p><b>How the gap was found.</b> By asking which families a rule watches, and then confirming the
 * answer by looking for each family key in the whole tree. Nineteen families came back. They held
 * three hundred and ninety-four patterns between them, and not one of those patterns could ever
 * fire.
 *
 * <p><b>Why this test lives here and not in the engine module.</b> Because the watcher has to be
 * the checker the SCAN builds, not the checker the catalogue names. The engine takes its checker
 * list from its caller, and the caller builds that list by hand; a checker that is only in the
 * catalogue's configuration never runs. That is not a hypothesis: the SSRF checker was catalogued,
 * tested, and shipped, and never fired once, because nothing built it.
 *
 * <p><b>What this test asserts.</b> Three things. Every sink family in the catalogue is watched by
 * a checker the scan builds; every checker the catalogue names is built by the scan; and the
 * families that used to be dead still match the APIs they name, so that folding them into a watched
 * family did not quietly break their patterns.
 */
class SinkFamilyWatcherInvariantTest {

  /**
   * The families that used to be dead, and the API each one names.
   *
   * <p><b>Every API here was MEASURED.</b> Each was found by asking the registry which families a
   * known API reaches, not by reading the pattern list by eye: a pattern's text and the API it
   * actually matches are two different things, and a test written from the text would assert a
   * family matches an API it does not.
   *
   * <p>The first six were folded into a family that already had a watcher; the last six got a
   * checker of their own.
   */
  private static final Map<String, String[]> REACHABLE_NOW =
      new LinkedHashMap<>() {
        {
          // Folded into a family that already had a watcher.
          put(
              "sensitive.exfil.sinks",
              new String[] {"android.content.ClipboardManager.setPrimaryClip/1"});
          put(
              "credential.sinks",
              new String[] {
                "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.registerConsumer/3"
              });
          put(
              "open-redirect.sinks",
              new String[] {"org.glassfish.jersey.server.oauth1.OAuth1Provider.handleCallback/1"});
          put(
              "external.config.sinks",
              new String[] {"java.util.prefs.Preferences.importPreferences/1"});
          put("cookie.sinks", new String[] {"play.mvc.Http.response.setCookie/2"});
          put("header.sinks", new String[] {"play.mvc.Http.response.setHeader/2"});
          // Given a checker of their own.
          put("ssrf.sinks", new String[] {"java.net.URL.openConnection/0"});
          put(
              "xxe.sinks",
              new String[] {"javax.xml.transform.TransformerFactory.newTransformer/0"});
          put("weak-crypto.sinks", new String[] {"java.security.MessageDigest.getInstance/1"});
          put(
              "unencrypted-credential.sinks",
              new String[] {"org.glassfish.jersey.server.oauth1.OAuth1Provider.addAccessToken/2"});
          put(
              "android-implicit-intent.sinks",
              new String[] {"android.app.Activity.startActivityForResult/1"});
          put("cors.sinks", new String[] {"System.Net.Http.Headers.HttpHeaders.Add/2"});
        }
      };

  /**
   * The families a built checker watches WITHOUT a taint rule.
   *
   * <p>A taint rule names its family in the rule it is built with, and that is where the engine
   * reads it from. A rule that is not a taint rule -- where the dangerous thing is the CALL rather
   * than a value reaching it -- looks the family up in the registry instead, so the name lives in a
   * constant of the checker and not in a rule.
   */
  private static final Map<String, String> WATCHED_WITHOUT_A_RULE =
      Map.of("file-permission.sinks", "FilePermissionChecker looks the family up in the registry");

  /** The language each measured API is written in. */
  private static final Map<String, SourceLanguage> LANGUAGE_OF =
      new LinkedHashMap<>() {
        {
          put("cors.sinks", SourceLanguage.CSHARP);
        }
      };

  private SinkFamilyRegistry load() {
    return ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  /**
   * The checkers the scan builds, with every rule enabled.
   *
   * <p>The flags are switched on because the question is "what CAN the tool do", not "what does it
   * do by default". A rule that is off by default is still a rule the catalogue promises, and it
   * still has to be built.
   */
  private List<DfaChecker> builtCheckers() {
    ScanConfig config = new ScanConfig();
    for (java.lang.reflect.Field field : ScanConfig.class.getDeclaredFields()) {
      if (field.getType() == boolean.class && field.getName().startsWith("run")) {
        try {
          field.setAccessible(true);
          field.setBoolean(config, true);
        } catch (IllegalAccessException e) {
          throw new AssertionError("the flag " + field.getName() + " cannot be read", e);
        }
      }
    }
    return new ScanRunner(config).buildDfaCheckers();
  }

  /** The sink families a built checker watches. */
  private Map<String, String> watchersByFamily() {
    Map<String, String> out = new LinkedHashMap<>(WATCHED_WITHOUT_A_RULE);
    for (DfaChecker checker : builtCheckers()) {
      if (checker instanceof TaintedCheckerBase taint && taint.rule() != null) {
        String family = taint.rule().sinkFamilyKey();
        if (family != null) {
          out.putIfAbsent(family, checker.getClass().getSimpleName());
        }
      }
    }
    return out;
  }

  /** The family names a callee signature reaches. */
  private Set<String> familiesFor(String calleeSignature, SourceLanguage language) {
    return load().lookupAll(calleeSignature, language).stream()
        .map(SinkFamily::name)
        .collect(java.util.stream.Collectors.toSet());
  }

  @Test
  @DisplayName("every sink family in the catalogue is watched by a checker the scan builds")
  void everySinkFamilyHasABuiltWatcher() {
    // The catalogue's source families carry no CWE: they say where data comes
    // in, and the source matcher reads them. Every other family is a sink
    // family, and a sink family with no watcher is dead configuration.
    List<String> sinkFamilies = new ArrayList<>();
    for (SinkFamily family : load().all()) {
      if (family.cwe() != null && !family.cwe().isBlank()) {
        sinkFamilies.add(family.name());
      }
    }

    Set<String> watched = new LinkedHashSet<>(watchersByFamily().keySet());
    List<String> unwatched = new ArrayList<>();
    for (String family : sinkFamilies) {
      if (!watched.contains(family)) {
        unwatched.add(family);
      }
    }

    assertThat(unwatched)
        .as("sink families with patterns that no built checker watches: %s", unwatched)
        .isEmpty();
  }

  @Test
  @DisplayName("every checker the catalogue names is built by the scan")
  void everyCataloguedCheckerIsBuilt() {
    // The catalogue is the list of what the tool can do. A checker on that
    // list that the scan never builds is a promise the tool does not keep --
    // and it is silent: the configuration still says the rule is enabled.
    Set<String> built = new LinkedHashSet<>();
    for (DfaChecker checker : builtCheckers()) {
      built.add(checker.getClass().getName());
    }

    List<String> missing = new ArrayList<>();
    for (var entry : ConfigLoader.loadFromClasspath().checkers().all()) {
      String className = entry.className();
      if (className == null || className.isBlank()) continue;
      // The style module's AST checks are not DFA checkers: they are built
      // by the style module's own registry, not by the scan.
      if (!className.startsWith("org.plumb.dfa.checkers.")) continue;
      if (!built.contains(className)) {
        missing.add(entry.name() + " -> " + className);
      }
    }

    assertThat(missing).as("catalogued checkers the scan never builds: %s", missing).isEmpty();
  }

  @Test
  @DisplayName("the families that used to be dead still match the APIs they name")
  void theFamiliesThatUsedToBeDeadStillMatchTheirApis() {
    // Folding a family into a watched one, and curating a family's patterns,
    // can both break a pattern. This is the half of the contract that the
    // reachability test cannot see.
    List<String> unmatched = new ArrayList<>();
    for (Map.Entry<String, String[]> e : REACHABLE_NOW.entrySet()) {
      SourceLanguage language = LANGUAGE_OF.getOrDefault(e.getKey(), SourceLanguage.JAVA);
      if (!familiesFor(e.getValue()[0], language).contains(e.getKey())) {
        unmatched.add(e.getKey() + " does not match " + e.getValue()[0]);
      }
    }

    assertThat(unmatched).as("families whose patterns no longer match: %s", unmatched).isEmpty();
  }
}
