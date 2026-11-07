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

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The catalogue's taint SOURCE families, matched by the engine's own pattern rules.
 *
 * <p><b>Why this class exists.</b> The catalogue declares eleven source families -- {@code
 * taint.sources.web}, {@code taint.sources.io}, {@code ip-address.sources} and the rest -- each
 * with anchored patterns naming the API that carries untrusted data in. The engine, however,
 * decided what an input source is from a **separate hardcoded substring list** ({@link
 * InputSourcePatterns}) that the catalogue never reaches.
 *
 * <p>So the catalogue and the engine disagreed, and the engine is what runs. Measured against the
 * catalogue, the substring list misses eight sources it declares:
 *
 * <table>
 *   <caption>Sources the catalogue declares and the engine did not see</caption>
 *   <tr><th>Callee</th><th>Family</th></tr>
 *   <tr><td>{@code java.lang.System.console}</td><td>{@code taint.sources.console}</td></tr>
 *   <tr><td>{@code java.util.Scanner.next}</td><td>{@code taint.sources.io}</td></tr>
 *   <tr><td>{@code java.net.URL.getHost}</td><td>{@code ip-address.sources}</td></tr>
 *   <tr><td>{@code java.net.InetAddress.getByName}</td><td>{@code dns.sources}</td></tr>
 *   <tr><td>{@code java.security.Principal.getName}</td><td>{@code principal.sources}</td></tr>
 *   <tr><td>{@code javax.servlet.request.getUserPrincipal}</td><td>{@code principal.sources}</td></tr>
 *   <tr><td>{@code org.springframework.http.HttpHeaders.getFirst}</td><td>{@code taint.sources.web}</td></tr>
 *   <tr><td>{@code io.undertow.server.HttpServerExchange.getQueryParameters}</td><td>{@code
 *       taint.sources.web}</td></tr>
 * </table>
 *
 * <p>A declared source the engine does not recognise is a **false negative**: the value is read
 * from an untrusted place, the engine calls it clean, and every sink downstream of it is blind.
 *
 * <p><b>Why the union, and not a replacement.</b> The substring list covers sources the catalogue
 * does not declare -- {@code Integer.parseInt} and the other numeric parsers are the clearest
 * examples, and they matter because a parsed number is still attacker-influenced. Replacing the
 * substring list with the catalogue would trade eight false negatives for a set of false negatives
 * of its own. The two lists answer different questions, so the answer is their union.
 *
 * <p><b>What a source family IS.</b> A family with no CWE. The catalogue distinguishes a source
 * from a sink by the absence of a {@code cwe:} key ({@link SinkFamily#cweOrNull()} returns {@code
 * null} for them), and that is the only distinction available: there is no role marker in the YAML.
 *
 * <p><b>Why the match is language-aware.</b> A language-restricted family must not be blamed for
 * another language's call: {@code kotlin.io.readLine} is a Kotlin API, and a Java file that happens
 * to contain that string is not reading Kotlin's stdin. The registry already answers "does this
 * family apply to this language" ({@link SinkFamilyRegistry#lookupAll(String, SourceLanguage)}), so
 * the question is asked there rather than here.
 */
public final class SourceFamilyMatcher {

  /**
   * The catalogue families that are sources, per language.
   *
   * <p>Computed once per registry rather than per lookup: the same registry is consulted for every
   * CALL instruction in every function, and the family list is fixed for the run.
   */
  private final SinkFamilyRegistry registry;

  /** The source families, keyed by the language they apply to. {@code null} key means "all". */
  private final java.util.Map<SourceLanguage, java.util.List<SinkFamily>> byLanguage;

  /**
   * Cache of the answer per (signature, language). The same callee signature is looked up once per
   * CALL instruction in every function, so the match is hot.
   */
  private final ConcurrentHashMap<String, Boolean> cache = new ConcurrentHashMap<>();

  /**
   * A matcher over the given registry.
   *
   * @param registry the catalogue
   */
  public SourceFamilyMatcher(SinkFamilyRegistry registry) {
    this.registry = registry == null ? SinkFamilyRegistry.empty() : registry;
    this.byLanguage = computeSourceFamilies(this.registry);
  }

  /**
   * The catalogue's source families, grouped by the language they apply to.
   *
   * <p>A family with no language restriction goes under the {@code null} key, meaning "all
   * languages". A family restricted to one language goes under that language's key.
   *
   * @param registry the catalogue
   * @return the source families by language
   */
  private static java.util.Map<SourceLanguage, java.util.List<SinkFamily>> computeSourceFamilies(
      SinkFamilyRegistry registry) {
    java.util.Map<SourceLanguage, java.util.List<SinkFamily>> out = new java.util.LinkedHashMap<>();
    for (SinkFamily f : registry.all()) {
      // A source family is one with no CWE. That is the only distinction the
      // catalogue makes: the YAML has no role marker, so the absence of the
      // `cwe:` key is what says "this is where data comes in".
      if (f.cweOrNull() != null) continue;
      if (f.languages().isEmpty()) {
        out.computeIfAbsent(null, k -> new java.util.ArrayList<>()).add(f);
      } else {
        for (SourceLanguage lang : f.languages()) {
          out.computeIfAbsent(lang, k -> new java.util.ArrayList<>()).add(f);
        }
      }
    }
    out.replaceAll((k, v) -> List.copyOf(v));
    return out;
  }

  /**
   * Whether the catalogue says this callee is a taint source.
   *
   * @param calleeSig the callee signature
   * @param language the language of the code being analysed
   * @return true when a source family matches
   */
  public boolean matches(String calleeSig, SourceLanguage language) {
    if (calleeSig == null || calleeSig.isEmpty()) return false;
    String key = language == null ? "\0" + calleeSig : language.name() + "\0" + calleeSig;
    Boolean cached = cache.get(key);
    if (cached != null) return cached;
    boolean matched = computeMatch(calleeSig, language);
    cache.put(key, matched);
    return matched;
  }

  /**
   * The match itself, without the cache.
   *
   * @param calleeSig the callee signature
   * @param language the language of the code being analysed
   * @return true when a source family matches
   */
  private boolean computeMatch(String calleeSig, SourceLanguage language) {
    // The families that apply to every language, plus the ones restricted to
    // this language. A family restricted to ANOTHER language does not apply,
    // which is the point of the restriction.
    java.util.List<SinkFamily> candidates = new java.util.ArrayList<>();
    java.util.List<SinkFamily> allLanguages = byLanguage.get(null);
    if (allLanguages != null) candidates.addAll(allLanguages);
    if (language != null) {
      java.util.List<SinkFamily> forLanguage = byLanguage.get(language);
      if (forLanguage != null) candidates.addAll(forLanguage);
    }
    for (SinkFamily f : candidates) {
      if (f.matches(calleeSig, language)) return true;
    }
    return false;
  }

  /**
   * The catalogue families this matcher considers sources.
   *
   * @return the source family names
   */
  public java.util.Set<String> sourceFamilyNames() {
    java.util.Set<String> out = new java.util.LinkedHashSet<>();
    for (java.util.List<SinkFamily> group : byLanguage.values()) {
      for (SinkFamily f : group) out.add(f.name());
    }
    return out;
  }
}
