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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.checkers.taint.XssInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamilyRegistry;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.ts.TsSidecarClient;
import org.plumb.tac.frontend.ts.TsTacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * JavaScript and TypeScript reach their own sinks.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>Seven families -- SQL, LDAP, XPath, XXE, deserialization, open redirect and XSS -- had no
 * JavaScript entry at all, so a tainted value that reached a JavaScript sink reached no sink.
 * Measured against BenchProctor's JavaScript and TypeScript suites (2880 cases), that was seven
 * categories at zero coverage and a recall of 17.7%.
 *
 * <p>The names below are the engine's own callee keys, read off the TAC the script side car
 * produces. A pattern that does not match them verbatim never fires, which is why they are written
 * out rather than derived.
 *
 * <h2>Why the two languages need two shapes</h2>
 *
 * <p>A JavaScript response object is a local variable, so the key carries the method name alone:
 * {@code send/1}. TypeScript resolves an imported class to the module it came from, so the same
 * call arrives as {@code "express".Response.send/1}. One bucket therefore names both shapes,
 * because the bucket serves the three script languages at once and the catalogue cannot know which
 * of them a given file is.
 *
 * <h2>Why a row read back from the database is a source</h2>
 *
 * <p>Data that comes out of the database carries whatever an earlier request put there. A program
 * that reads a name from a table and writes it into a page is the stored cross-site scripting
 * pattern, and the engine was blind to it: the value entered through a call the catalogue did not
 * name, so it was clean from the start.
 *
 * <h2>Why the negative control matters</h2>
 *
 * <p>The bucket is language-scoped on purpose. A Java program that calls {@code send} is not an
 * Express response, and a Java program that reads a row is not reading a JavaScript database
 * client.
 */
@DisplayName("JavaScript and TypeScript reach their own sinks")
class JavaScriptSinkFamilyTest {

  private static final SinkFamilyRegistry CATALOGUE =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  /** The rule that reports a tainted value reaching a sink. */
  private static final String XSS_RULE = "dfa-taint-xss-injection";

  private static final String SQL_RULE = "dfa-taint-sql-injection";

  /**
   * The JavaScript callee keys and the family each one belongs to.
   *
   * <p>Read off the TAC the script side car builds for the BenchProctor JavaScript suites.
   */
  private static final String[][] JAVASCRIPT_SINKS = {
    {"prepare/1", "sql.sinks"},
    {"literal/1", "sql.sinks"},
    {"compare/4", "ldap.sinks"},
    {"search/3", "ldap.sinks"},
    {"xpath.evaluate/5", "xpath.sinks"},
    {"libxmljs.parseXml/2", "xxe.sinks"},
    {"node-serialize.unserialize/1", "deserialization.sinks"},
    {"js-yaml.load/2", "deserialization.sinks"},
    {"location/1", "open-redirect.sinks"},
    {"redirect/1", "open-redirect.sinks"},
    {"send/1", "xss.sinks"},
    {"end/1", "xss.sinks"},
    {"nunjucks.renderString/2", "xss.sinks"},
  };

  /**
   * The TypeScript callee keys, which carry the module the class was imported from.
   *
   * <p>Read off the TAC the script side car builds for the BenchProctor TypeScript suites.
   */
  private static final String[][] TYPESCRIPT_SINKS = {
    {"\"express\".Response.send/1", "xss.sinks"},
    {"\"express\".Response.end/1", "xss.sinks"},
    {"\"express\".Response.location/1", "open-redirect.sinks"},
    {"\"ldapjs\".Client.compare/4", "ldap.sinks"},
    {"\"ldapjs\".Client.search/3", "ldap.sinks"},
    {"prepare/1", "sql.sinks"},
    {"js-yaml.load/2", "deserialization.sinks"},
    {"xpath.evaluate/5", "xpath.sinks"},
    {"libxmljs.parseXml/2", "xxe.sinks"},
  };

  /** Whether the engine recognises the callee as a source. */
  private static boolean engineSaysSource(String callee, SourceLanguage language) {
    return InputSourcePatterns.defaults().matches(callee, CATALOGUE, language);
  }

  @Test
  @DisplayName("the JavaScript entry points are sinks")
  void theJavaScriptEntryPointsAreSinks() {
    for (String[] entry : JAVASCRIPT_SINKS) {
      assertThat(CATALOGUE.lookupAll(entry[0], SourceLanguage.JAVASCRIPT))
          .as(entry[0] + " is a " + entry[1] + " for javascript")
          .anyMatch(family -> entry[1].equals(family.name()));
    }
  }

  @Test
  @DisplayName("the TypeScript entry points are sinks")
  void theTypeScriptEntryPointsAreSinks() {
    for (String[] entry : TYPESCRIPT_SINKS) {
      assertThat(CATALOGUE.lookupAll(entry[0], SourceLanguage.TYPESCRIPT))
          .as(entry[0] + " is a " + entry[1] + " for typescript")
          .anyMatch(family -> entry[1].equals(family.name()));
    }
  }

  @Test
  @DisplayName("the script bucket does not leak into another language")
  void theScriptBucketDoesNotLeakIntoAnotherLanguage() {
    // The control. The bucket serves the script languages, so a Java program
    // that calls a method named send is not an Express response, and a Java
    // program that reads a row is not a JavaScript database client.
    assertThat(CATALOGUE.lookupAll("send/1", SourceLanguage.JAVA))
        .as("the script bucket does not leak into java")
        .noneMatch(family -> "xss.sinks".equals(family.name()));
    assertThat(engineSaysSource("query/2", SourceLanguage.JAVA))
        .as("a Java row read is not the script bucket's source")
        .isFalse();
  }

  @Test
  @DisplayName("a row read back from the database is a source")
  void aRowReadBackFromTheDatabaseIsASource() {
    assertThat(engineSaysSource("query/2", SourceLanguage.JAVASCRIPT))
        .as("the database read is untrusted input for javascript")
        .isTrue();
    assertThat(engineSaysSource("query/1", SourceLanguage.TYPESCRIPT))
        .as("the database read is untrusted input for typescript")
        .isTrue();
  }

  /**
   * A program that writes a value it was given into the page.
   *
   * <p>The response object is untyped, so the key is the method name alone -- the JavaScript shape.
   */
  private static final String TS_XSS =
      """
      export function handler(req: any, res: any): void {
        const input = req.query.q;
        res.send("<div>" + input + "</div>");
      }
      """;

  /**
   * A program that puts a value it was given into a SQL statement.
   *
   * <p>The prepared statement's owner is a local variable, so the key is the method name alone.
   */
  private static final String TS_SQL =
      """
      export function handler(req: any, db: any): void {
        const input = req.query.q;
        db.prepare("SELECT id FROM users WHERE name = '" + input + "'");
      }
      """;

  @Test
  @DisplayName("a tainted value reaches the JavaScript sink")
  void aTaintedValueReachesTheJavaScriptSink(@TempDir Path directory) throws IOException {
    assumeTrue(tsAvailable(), "the script front end needs Node and the typescript package");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_XSS);

    List<Finding> findings =
        scan(new TsTacFrontend(SourceLanguage.TYPESCRIPT), TS_XSS, file, new XssInjectionChecker());

    assertThat(hasFinding(findings, XSS_RULE, "send/1"))
        .as("the tainted value reaches res.send, so the XSS rule fires")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value reaches the JavaScript SQL sink")
  void aTaintedValueReachesTheJavaScriptSqlSink(@TempDir Path directory) throws IOException {
    assumeTrue(tsAvailable(), "the script front end needs Node and the typescript package");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_SQL);

    List<Finding> findings =
        scan(new TsTacFrontend(SourceLanguage.TYPESCRIPT), TS_SQL, file, new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "prepare/1"))
        .as("the tainted value reaches db.prepare, so the SQL rule fires")
        .isTrue();
  }

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param frontend the front end that lowers the source
   * @param source the source text
   * @param file the file the source came from
   * @param checker the rule under test
   * @return the findings the engine reported
   */
  private static List<Finding> scan(
      TacFrontend frontend, String source, Path file, DfaChecker checker) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  /** Whether a finding of the given rule names the given callee. */
  private static boolean hasFinding(List<Finding> findings, String rule, String callee) {
    for (Finding finding : findings) {
      if (!rule.equals(finding.ruleId())) {
        continue;
      }
      if (finding.message().contains(callee)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the script front end can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the package the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean tsAvailable() {
    Path node = TsSidecarClient.locateNode();
    Path script = TsSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }
}
