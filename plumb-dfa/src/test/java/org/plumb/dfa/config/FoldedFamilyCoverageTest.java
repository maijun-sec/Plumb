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

import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The families that were folded away, and the content that has to stay watched.
 *
 * <p><b>The defect this pins.</b> A family that is folded into the family that owns its defect
 * class must not take its content with it. The catalogue had the same API listed under two names --
 * one watched, one not -- and folding the unwatched one into the watched one is the fix. But a fold
 * can also drop a pattern that nothing else named, and then the symptom is the very one the fold
 * was meant to remove: a call that matches nothing and reports nothing, under a family name that no
 * longer exists to look for.
 *
 * <p><b>What this asserts.</b> Two things about every family that was folded or retired. The name
 * is gone -- one defect class, one name. And the API it named is still watched, by the family that
 * owns that class now.
 *
 * <p>The APIs here were MEASURED: each is a call the catalogue reached before the fold, not a
 * pattern read off a list. A pattern's text and the API it matches are two different things, and a
 * witness taken from the text asserts a match that may not be there.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FoldedFamilyCoverageTest {

  /** The family that was folded away, the API it named, and the family that owns that API now. */
  private static final String[][] FOLDED = {
    {
      "oauth-credential.sinks",
      "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.registerConsumer/4",
      "credential.sinks"
    },
    {"db-credential.sinks", "java.sql.DriverManager.getConnection/3", "credential.sinks"},
    {"predictable-seed.sinks", "java.security.SecureRandom.<init>/1", "insecure-random.sinks"},
    {"cookie-write.sinks", "javax.servlet.http.HttpServletResponse.addCookie/1", "cookie.sinks"},
    {
      "http-header-write.sinks",
      "javax.servlet.http.HttpServletResponse.setHeader/2",
      "header.sinks"
    },
    {
      "redirect.sinks",
      "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.handleCallback/1",
      "open-redirect.sinks"
    },
    {
      "android-clipboard.sinks",
      "android.content.ClipboardManager.setPrimaryClip/1",
      "sensitive.exfil.sinks"
    },
    {"sensitive-url.sinks", "java.net.URL.getContent/0", "sensitive.exfil.sinks"},
    {"registry.sinks", "java.util.prefs.Preferences.importPreferences/1", "external.config.sinks"},
  };

  private SinkFamilyRegistry registry;

  @BeforeAll
  void load() {
    registry = ConfigLoader.loadFromClasspath().sinkFamilies();
  }

  @Test
  void theFoldedFamiliesAreGone() {
    for (String[] row : FOLDED) {
      assertThat(registry.byName(row[0])).as("the family %s", row[0]).isEmpty();
    }
  }

  @Test
  void theContentOfTheFoldedFamiliesIsStillWatched() {
    for (String[] row : FOLDED) {
      String retired = row[0];
      String api = row[1];
      String owner = row[2];

      assertThat(watchersOf(api))
          .as("the watchers of %s, folded out of %s", api, retired)
          .contains(owner);
    }
  }

  /** The families that watch the given callee signature, in any language. */
  private Set<String> watchersOf(String calleeSignature) {
    Set<String> names = new LinkedHashSet<>();
    for (SourceLanguage language : SourceLanguage.values()) {
      for (SinkFamily family : registry.lookupAll(calleeSignature, language)) {
        names.add(family.name());
      }
    }
    return names;
  }
}
