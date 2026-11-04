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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * How precisely the sink catalog matches a callee signature.
 *
 * <p><b>Why this exists.</b> Rule packs are converted into sink patterns mechanically, and a
 * mechanical conversion has two failure modes that are invisible in a smoke test:
 *
 * <ol>
 *   <li><b>A wildcard identifier.</b> Three rules in {@code extended_java.xml} name no symbol at
 *       all -- their whole {@code FunctionIdentifier} is {@code .*}, because they are scoped by a
 *       {@code FunctionLabel} (the MyBatis mapper rules, where the SQL lives in an annotation).
 *       Translated literally, one of them becomes {@code ^.*\..*\..*(/\d+)?}, which matches EVERY
 *       fully-qualified call site in the analysed program. The SQL family would then report every
 *       call in the codebase.
 *   <li><b>An unescaped dot in the owner.</b> The pack's namespace attribute is sometimes already a
 *       regex ({@code java\.util\.zip}) and sometimes a plain package name ({@code javax.script}).
 *       A plain package name carried into a regex unescaped turns every dot into "any character",
 *       so the pattern matches symbols it was never meant to name.
 * </ol>
 *
 * <p>Both defects are silent: the intended symbol still matches, so a test that only checks "does
 * the known sink match?" passes. So these tests assert the NEGATIVE space -- what the catalog must
 * NOT match -- which is the only thing that can catch them.
 *
 * <p>The assertions are on the shipped catalog, not on a fixture, because the defect lives in the
 * generated YAML.
 */
class SinkMatchPrecisionTest {

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
  @DisplayName("an unrelated fully-qualified call reaches no sink family")
  void unrelatedCallReachesNoFamily() {
    // This is the assertion the catch-all pattern breaks. A rule that matches
    // "any method of any class in any package" turns every call site in the
    // program into a finding, so the catalog has to be silent here.
    assertThat(familiesFor("com.example.Whatever.someMethod/3"))
        .as("an unrelated call must not reach any sink family")
        .isEmpty();
  }

  @Test
  @DisplayName("a plain application call reaches no sink family")
  void plainApplicationCallReachesNoFamily() {
    assertThat(familiesFor("com.example.service.OrderService.findById/1")).isEmpty();
    assertThat(familiesFor("com.example.web.OrderController.handle/1")).isEmpty();
  }

  @Test
  @DisplayName("no shipped pattern is a match-everything pattern")
  void noPatternMatchesEverything() {
    // A pattern whose owner is entirely wildcards would match every call.
    // This is the shape the MyBatis rules had.
    for (SinkFamily family : load().all()) {
      for (SinkFamily.SinkPattern pattern : family.patterns()) {
        assertThat(pattern.pattern())
            .as("%s carries a catch-all pattern", family.name())
            .doesNotMatch("^\\^\\.\\*\\\\\\..*");
      }
    }
  }

  @Test
  @DisplayName("a package name is matched literally, not as a wildcard")
  void packageDotsAreEscaped() {
    // `javax.script` must not match `javaxXscript` -- the unescaped-dot defect.
    // The pattern for `javax.script.ScriptEngine#eval` has to require the dot.
    SinkFamilyRegistry registry = load();
    List<SinkFamily> hits =
        registry.lookupAll("javaxXscriptXScriptEngine.eval/1", SourceLanguage.JAVA);
    assertThat(hits).as("a dot in the owner must match a literal dot").isEmpty();
  }

  @Test
  @DisplayName("a known sink still reaches exactly its own family")
  void knownSinkReachesItsOwnFamily() {
    assertThat(familiesFor("java.sql.Statement.execute/1"))
        .as("java.sql.Statement#execute is a SQL sink")
        .contains("sql.sinks");
    // It ALSO reaches database-access.sinks, and that is correct: the same call
    // is CWE-89 (the value changes the statement) and CWE-566 (the value
    // reaches the database at all). Two defect classes, one API -- which is
    // why they are two FAMILIES with two checkers, not one family with two
    // CWEs. What the assertion below rules out is a catch-all.
    assertThat(familiesFor("java.sql.Statement.execute/1"))
        .as("and no UNRELATED family -- the SQL family is not a catch-all")
        .containsOnly("sql.sinks", "database-access.sinks");
  }

  @Test
  @DisplayName("a JDBC sink does not reach the code-execution family")
  void jdbcSinkDoesNotReachCodeExecution() {
    // `java.beans.Statement#execute` is the code-execution sink; the JDBC one
    // is `java.sql.Statement#execute`. A bare-class pattern (`^Statement\.`)
    // would make them the same family.
    assertThat(familiesFor("java.sql.Statement.execute/1"))
        .as("the JDBC Statement is not a code-execution sink")
        .doesNotContain("code-execution.sinks");
    assertThat(familiesFor("java.beans.Statement.execute/1"))
        .as("the beans Statement is a code-execution sink")
        .contains("code-execution.sinks");
  }

  @Test
  @DisplayName("an LDAP search does not reach the code-execution family")
  void ldapSearchDoesNotReachCodeExecution() {
    // `javax.naming.directory.DirContext#search` is the LDAP sink. It is
    // claimed by two defect classes -- LDAP injection and resource injection
    // through JNDI -- because the rules say both things about it. That
    // is a multi-defect call site, not cross-family contamination: neither
    // claim is a coincidence.
    assertThat(familiesFor("javax.naming.directory.DirContext.search/2"))
        .as("a DirContext search is an LDAP sink")
        .contains("ldap.sinks");
    assertThat(familiesFor("javax.naming.directory.DirContext.search/2"))
        .as("and not a code-execution sink")
        .doesNotContain("code-execution.sinks");
    assertThat(familiesFor("javax.naming.directory.DirContext.search/2"))
        .as("and not a SQL sink")
        .doesNotContain("sql.sinks");
  }

  @Test
  @DisplayName("a JNDI lookup does not reach the code-execution family")
  void jndiLookupDoesNotReachCodeExecution() {
    // `javax.naming.Context#lookup` is a JNDI lookup. The catalogue names it in
    // more than one defect class -- a JNDI resource injection and an LDAP
    // access-control concern -- so it legitimately reaches two families.
    // What it must NOT reach is an unrelated one.
    assertThat(familiesFor("javax.naming.Context.lookup/1"))
        .as("a JNDI lookup is a resource injection")
        .contains("resource-injection.sinks");
    assertThat(familiesFor("javax.naming.Context.lookup/1"))
        .as("and not a code-execution sink")
        .doesNotContain("code-execution.sinks");
    assertThat(familiesFor("javax.naming.Context.lookup/1"))
        .as("and not a SQL sink")
        .doesNotContain("sql.sinks");
  }

  @Test
  @DisplayName("every shipped pattern compiles and is anchored")
  void everyPatternCompilesAndIsAnchored() {
    for (SinkFamily family : load().all()) {
      for (SinkFamily.SinkPattern pattern : family.patterns()) {
        // Compiles -- an unparseable pattern throws here rather than silently
        // never matching.
        Pattern.compile(pattern.pattern());
        assertThat(pattern.pattern())
            .as("%s has an unanchored pattern", family.name())
            .startsWith("^");
      }
    }
  }

  /**
   * Every argument position a matching pattern names must be one the family actually checks.
   *
   * <p><b>Why this exists.</b> A sink symbol can carry the taint at MORE THAN ONE argument
   * position, and the matcher has to check all of them.
   *
   * <p>The concrete damage was real. The conversion produced, for {@code sql.sinks}:
   *
   * <pre>
   *   '^org\.springframework\.jdbc\.core\.JdbcTemplate\.(query|queryForList|...)(/\d+)?'  arg_index: 1
   *   '^org\.springframework\.jdbc\.core\.JdbcTemplate\.(batchUpdate|execute|query|...)(/\d+)?'  arg_index: 0
   * </pre>
   *
   * <p>Both match {@code JdbcTemplate.queryForList}, and they disagree about the argument: the
   * first says the varargs (index 1), the second says the SQL (index 0). Both are correct -- Spring
   * has both overloads, and the pack has a rule for each.
   *
   * <p>The old model had ONE index per pattern, and the matcher took the FIRST matching pattern. So
   * one of the two positions was silently dropped, and WHICH one was decided by list order. The
   * finding still fired; it just pointed at the wrong argument. Worse, the framework corpus
   * measurement went from 34 findings to 24: the SQL-text rule had lost the race and the tainted
   * SQL was no longer the argument being inspected.
   *
   * <p><b>HOW THIS IS DETECTED.</b> Not by comparing pattern text -- the patterns carry alternation
   * groups ({@code (query|queryForList|...)}) and optional package prefixes, so text comparison
   * would confuse two patterns for DIFFERENT methods that share a class prefix.
   *
   * <p>Instead this asks the matcher itself: for a concrete callee signature, collect EVERY pattern
   * that matches it, and require that each one's index is among the positions the family resolves.
   * The callee signatures come from the patterns themselves, so the test covers the whole shipped
   * catalog without a hand-maintained list.
   */
  @Test
  @DisplayName("every position a matching pattern names is one the family checks")
  void everyPositionAMatchingPatternNamesIsChecked() {
    List<String> dropped = new ArrayList<>();
    for (SinkFamily family : load().all()) {
      // The callee signatures to probe: one per pattern, derived from the pattern's own
      // owner and method. A pattern that names an alternation contributes the first
      // alternative -- enough to reach the pattern and to reach any pattern that
      // overlaps it.
      for (SinkFamily.SinkPattern pattern : family.patterns()) {
        String callee = representativeCallee(pattern);
        if (callee == null) continue;

        int[] resolved = family.argIndexesFor(callee);
        if (resolved.length == 0) continue;

        for (SinkFamily.SinkPattern candidate : family.patterns()) {
          if (!candidate.matches(callee)) continue;
          if (!candidate.hasExplicitArgIndex()) continue;
          int claimed = candidate.argIndex();
          // The receiver sentinel is not in the argument list, so it is resolved on its
          // own and compared directly.
          if (claimed == SinkFamily.RECEIVER_ARG_INDEX) {
            if (!contains(resolved, claimed)) {
              dropped.add(
                  family.name()
                      + ": callee "
                      + callee
                      + " does not check the receiver, but '"
                      + candidate.pattern()
                      + "' claims it");
            }
            continue;
          }
          if (!contains(resolved, claimed)) {
            dropped.add(
                family.name()
                    + ": callee "
                    + callee
                    + " does not check arg_index "
                    + claimed
                    + ", but '"
                    + candidate.pattern()
                    + "' claims it");
          }
        }
      }
    }
    assertThat(dropped).as("a position no rule inspects is a finding that cannot fire").isEmpty();
  }

  private static boolean contains(int[] values, int target) {
    for (int v : values) {
      if (v == target) return true;
    }
    return false;
  }

  /**
   * A concrete callee signature that a pattern matches, or {@code null} when the pattern's shape
   * does not allow one to be derived.
   *
   * <p>The signature shape is {@code Type.method/arity}. The pattern's owner is the text before the
   * last escaped dot; the method is the identifier (or the first alternative of an alternation)
   * after it.
   */
  private static String representativeCallee(SinkFamily.SinkPattern pattern) {
    String text = pattern.pattern();
    // Unescape the dots so the owner segments can be found.
    String flat = text.replace("\\.", ".");
    // Drop the leading anchor and the arity group.
    if (flat.startsWith("^")) flat = flat.substring(1);
    int arity = flat.indexOf('(');
    if (arity < 0) return null;
    flat = flat.substring(0, arity);

    // The owner is everything up to the last dot; the method is the rest.
    int lastDot = flat.lastIndexOf('.');
    if (lastDot < 0) return null;
    String owner = flat.substring(0, lastDot);
    String method = flat.substring(lastDot + 1);

    // An alternation group in the method: take the first alternative.
    if (method.startsWith("(")) {
      int close = method.indexOf(')');
      if (close < 0) return null;
      method = method.substring(1, close);
      int alt = method.indexOf('|');
      if (alt >= 0) method = method.substring(0, alt);
    }
    // A group in the owner: the owner is then an alternation of packages, and the
    // representative callee takes the first alternative.
    if (owner.startsWith("(")) {
      int close = owner.indexOf(')');
      if (close < 0) return null;
      String inner = owner.substring(1, close);
      int alt = inner.indexOf('|');
      if (alt >= 0) inner = inner.substring(0, alt);
      owner = inner;
    }
    if (owner.isEmpty() || method.isEmpty()) return null;
    return owner + "." + method + "/1";
  }
}
