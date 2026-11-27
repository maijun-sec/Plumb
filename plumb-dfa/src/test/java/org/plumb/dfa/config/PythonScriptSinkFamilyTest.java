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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * Python's XPath and LDAP entry points are sinks.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>Neither {@code xpath.sinks} nor {@code ldap.sinks} had a python3 entry, so a tainted value
 * reaching an XPath expression or an LDAP filter reached no sink at all. Measured against the OWASP
 * Benchmark for Python (1230 files), that was 51 missed XPath cases and 16 missed LDAP ones.
 *
 * <p>The names below are the engine's own callee keys, read off the TAC the Python side car
 * produces: {@code lxml.etree.XPath(query)} arrives as {@code lxml.etree.XPath/1}, and {@code
 * conn.search(base, filter)} as {@code <unknown>.search/2} -- the connection comes from a helper,
 * so the owner is the placeholder for an object the front end could not resolve.
 *
 * <p>The negative control matters: the LDAP entry is the widest pattern in the catalogue for
 * Python, and a rule that claims every {@code .search} on every object would report searches that
 * are not directory searches at all.
 *
 * <h2>Why the lookup is language-aware</h2>
 *
 * <p>{@code lookupAll(signature)} matches the family's SHARED patterns only; the {@code
 * by_language} buckets are reached through {@code lookupAll(signature, language)} -- which is the
 * call the engine itself makes, with the analysed file's language. A lookup without the language
 * therefore answers "no" for a python3-only entry, and a test written that way would report the
 * missing catalogue entry as a missing finding.
 */
@DisplayName("Python's XPath and LDAP entry points are sinks")
class PythonScriptSinkFamilyTest {

  private static final SinkFamilyRegistry CATALOGUE =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  /** The families this test speaks about. */
  private static final String XPATH = "xpath.sinks";

  private static final String LDAP = "ldap.sinks";

  @Test
  @DisplayName("the XPath expression is a sink")
  void theXpathExpressionIsASink() {
    assertThat(CATALOGUE.lookupAll("lxml.etree.XPath/1", SourceLanguage.PYTHON3))
        .as("lxml builds the query object from the expression")
        .anyMatch(family -> XPATH.equals(family.name()));
  }

  @Test
  @DisplayName("the LDAP filter is a sink")
  void theLdapFilterIsASink() {
    assertThat(CATALOGUE.lookupAll("<unknown>.search/2", SourceLanguage.PYTHON3))
        .as("a connection's search takes the filter as its second argument")
        .anyMatch(family -> LDAP.equals(family.name()));
  }

  @Test
  @DisplayName("a search that is not a directory search is not a sink")
  void aSearchThatIsNotADirectorySearchIsNotASink() {
    // The control. The pattern is anchored on the method name, so it is
    // scoped to python3 -- a Java program that searches a collection is not
    // writing an LDAP filter.
    assertThat(CATALOGUE.lookupAll("<unknown>.search/2", SourceLanguage.JAVA))
        .as("the python3 bucket does not leak into another language")
        .noneMatch(family -> LDAP.equals(family.name()));
    assertThat(CATALOGUE.lookupAll("mylist.search/2", SourceLanguage.PYTHON3))
        .as("a search on an unrelated object is not a directory search")
        .noneMatch(family -> LDAP.equals(family.name()));
  }
}
