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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The catalogue declares taint SOURCE families; the engine has to believe it.
 *
 * <p><b>The property.</b> {@code sink-families.yaml} declares eleven source families -- {@code
 * taint.sources.web}, {@code taint.sources.io}, {@code ip-address.sources} and the rest -- each
 * with anchored patterns naming the API that carries untrusted data in. The engine used to decide
 * what an input source is from a SEPARATE hardcoded substring list that the catalogue never
 * reached, so the catalogue and the engine disagreed and the engine is what ran.
 *
 * <p>A declared source the engine does not recognise is a false NEGATIVE: the value is read from an
 * untrusted place, the engine calls it clean, and every sink downstream of it is blind.
 *
 * <p><b>Why the answer is a union.</b> The catalogue's patterns are anchored, so they do not match
 * {@code Integer.parseInt}; the substring list has no owner, so it matches {@code
 * com.example.MyReader.read}. Neither subsumes the other, so the engine asks both.
 *
 * <p><b>The negative control.</b> {@link #theSubstringListStillCoversWhatTheCatalogueDoesNot()}
 * pins the other half: the substring list alone still matches what only it matches. Without it, a
 * test asserting "the catalogue's sources are recognised" would also pass if the engine had simply
 * replaced the substring list with the catalogue.
 */
@DisplayName("the catalogue's taint source families are recognised by the engine")
class SourceFamilyMatcherTest {

  private static final SinkFamilyRegistry CATALOGUE =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  /** Whether the engine recognises the callee as a source, asking both lists. */
  private static boolean engineSays(String callee, SourceLanguage language) {
    return InputSourcePatterns.defaults().matches(callee, CATALOGUE, language);
  }

  @Test
  @DisplayName("the sources the catalogue declares are recognised")
  void theSourcesTheCatalogueDeclaresAreRecognised() {
    // Each of these is a source the catalogue declares and the engine did
    // not see. Every one of them is a false negative that was silent.
    assertThat(engineSays("java.lang.System.console", SourceLanguage.JAVA))
        .as("taint.sources.console declares java.lang.System.console")
        .isTrue();
    assertThat(engineSays("java.util.Scanner.next", SourceLanguage.JAVA))
        .as("taint.sources.io declares java.util.Scanner.next")
        .isTrue();
    assertThat(engineSays("java.net.URL.getHost", SourceLanguage.JAVA))
        .as("ip-address.sources declares java.net.URL.getHost")
        .isTrue();
    assertThat(engineSays("java.net.InetAddress.getByName", SourceLanguage.JAVA))
        .as("dns.sources declares java.net.InetAddress.getByName")
        .isTrue();
    assertThat(engineSays("java.security.Principal.getName", SourceLanguage.JAVA))
        .as("principal.sources declares java.security.Principal.getName")
        .isTrue();
    assertThat(engineSays("javax.servlet.request.getUserPrincipal", SourceLanguage.JAVA))
        .as("principal.sources declares javax.servlet.request.getUserPrincipal")
        .isTrue();
    assertThat(engineSays("org.springframework.http.HttpHeaders.getFirst", SourceLanguage.JAVA))
        .as("taint.sources.web declares org.springframework.http.HttpHeaders.getFirst")
        .isTrue();
    assertThat(
            engineSays(
                "io.undertow.server.HttpServerExchange.getQueryParameters", SourceLanguage.JAVA))
        .as("taint.sources.web declares io.undertow.server.HttpServerExchange.getQueryParameters")
        .isTrue();
  }

  @Test
  @DisplayName("the substring list still covers what the catalogue does not")
  void theSubstringListStillCoversWhatTheCatalogueDoesNot() {
    // The negative control. The catalogue does not declare Integer.parseInt
    // as a source, and it must not: the anchored patterns would never match
    // it. But a parsed number IS attacker-influenced, and the substring list
    // is what catches it. Replacing the substring list with the catalogue
    // would trade eight false negatives for a set of false negatives of its
    // own.
    assertThat(InputSourcePatterns.isInputSourceCall("java.lang.Integer.parseInt"))
        .as("the numeric parsers are sources the catalogue does not declare")
        .isTrue();
    assertThat(engineSays("java.lang.Integer.parseInt", SourceLanguage.JAVA))
        .as("and the union keeps them")
        .isTrue();
  }

  @Test
  @DisplayName("a language-restricted source is not blamed on another language")
  void aLanguageRestrictedSourceIsNotBlamedOnAnotherLanguage() {
    // kotlin.io.readLine is a Kotlin API. A Java file that happens to contain
    // that string is not reading Kotlin's stdin, so the Kotlin-only family
    // must not fire on Java code.
    assertThat(engineSays("kotlin.io.readLine", SourceLanguage.KOTLIN))
        .as("the Kotlin source family applies to Kotlin")
        .isTrue();
    assertThat(
            CATALOGUE.lookupAll("kotlin.io.readLine", SourceLanguage.JAVA).stream()
                .filter(f -> f.cweOrNull() == null)
                .toList())
        .as("and the registry itself excludes it for Java")
        .isEmpty();
  }

  @Test
  @DisplayName("a call that is not a source is not a source")
  void aCallThatIsNotASourceIsNotASource() {
    // The negative case for the union: the answer must not be "everything is
    // a source". A plain list append is not a source, in either list.
    assertThat(engineSays("java.util.ArrayList.add", SourceLanguage.JAVA)).isFalse();
    assertThat(engineSays("java.lang.String.valueOf", SourceLanguage.JAVA)).isFalse();
  }

  @Test
  @DisplayName("the matcher finds the catalogue's source families and no others")
  void theMatcherFindsTheCataloguesSourceFamiliesAndNoOthers() {
    SourceFamilyMatcher matcher = new SourceFamilyMatcher(CATALOGUE);

    assertThat(matcher.sourceFamilyNames())
        .as("a source family is one with no CWE -- the catalogue has no other way to say so")
        .contains(
            "taint.sources.web",
            "taint.sources.io",
            "taint.sources.network",
            "taint.sources.console",
            "taint.sources.env",
            "ip-address.sources",
            "dns.sources",
            "principal.sources",
            "rpc.sources",
            "login.sources",
            "taint.sources.groovy.console")
        .as("and no sink family")
        .doesNotContain("sql.sinks", "command.sinks", "xss.sinks");
  }
}
