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
import org.plumb.dfa.checkers.taint.LdapInjectionChecker;
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
import org.plumb.tac.frontend.php.PhpSidecarClient;
import org.plumb.tac.frontend.php.PhpTacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * PHP reaches its own sinks.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>Measured against BenchProctor's PHP suites (1440 cases), the engine reported six of the
 * eighteen categories and a recall of 16.9%. The six it reported were the ones a Java-shaped
 * catalogue already knew; the six the PHP corpora are built around -- SQL injection, cross-site
 * scripting, open redirect, XXE, XPath injection and LDAP injection -- were at zero, and so were
 * the two value-level ones the catalogue cannot express.
 *
 * <h2>Why the catalogue was not the whole story</h2>
 *
 * <p>Three things had to be true at once, and each of them was broken on its own:
 *
 * <ul>
 *   <li><b>The framework's request getters are sources.</b> A PHP controller reads the request
 *       through a getter -- {@code $request->header('X', '')} -- and the getter is the call that
 *       carries the untrusted value in. The catalogue named the getter, but the engine only seeded
 *       a source on a CALL, and every PHP library call is a black box: the engine cannot see inside
 *       {@code Illuminate\Http\Request}, so the call was UNINTERPRETED and the source was never
 *       seeded. A language whose third-party calls are all black boxes had no sources at all.
 *   <li><b>{@code echo} is a sink.</b> {@code echo '<div>' . $data . '</div>'} is how PHP writes to
 *       the page, and the front end lowered the expression and threw the result away: the value was
 *       computed and then lost, so it reached no sink.
 *   <li><b>An arrow function returns its expression.</b> {@code fn ($v) => trim($v)} returns the
 *       expression's value, and the front end computed it without returning it: the function's own
 *       body carried no RETURN, so the summary had nothing to say about what comes back out of it,
 *       and a tainted value that went through the arrow function stopped there.
 * </ul>
 *
 * <p>And one more, in the engine: a container that HOLDS a tainted value is itself tainted. The
 * engine tracked the taint per element, so a read of the element saw it -- but a sink that receives
 * the whole container (a template engine handed the values to render) never reads an element, and
 * saw a clean container.
 *
 * <h2>Why the keys below are written out</h2>
 *
 * <p>They are the engine's own callee keys, read off the TAC the PHP side car produces. A pattern
 * that does not match them verbatim never fires. PHP writes an instance method's owner with {@code
 * ::} and a constructor's with {@code .}, so the two shapes are not interchangeable.
 *
 * <h2>Why the negative control matters</h2>
 *
 * <p>The buckets are language-scoped on purpose. A Java program that calls {@code prepare} is not a
 * PHP database handle, and a Java program that reads a row is not reading a PHP connection.
 */
@DisplayName("PHP reaches its own sinks")
class PhpSinkFamilyTest {

  private static final SinkFamilyRegistry CATALOGUE =
      ConfigLoader.loadFromClasspath().sinkFamilies();

  private static final String SQL_RULE = "dfa-taint-sql-injection";

  private static final String XSS_RULE = "dfa-taint-xss-injection";

  private static final String LDAP_RULE = "dfa-taint-ldap-injection";

  /**
   * The PHP callee keys and the family each one belongs to.
   *
   * <p>Read off the TAC the PHP side car builds for the BenchProctor PHP suites.
   */
  private static final String[][] PHP_SINKS = {
    {"Illuminate\\Support\\Facades\\DB::select/1", "sql.sinks"},
    {"Illuminate\\Support\\Facades\\DB::selectOne/1", "sql.sinks"},
    {"Illuminate\\Support\\Facades\\DB::statement/2", "sql.sinks"},
    {"Doctrine\\DBAL\\Connection::executeQuery/1", "sql.sinks"},
    {"Doctrine\\DBAL\\Connection::prepare/1", "sql.sinks"},
    {"App\\Http\\Controllers\\XController::where/2", "sql.sinks"},
    {"<php>.echo/1", "xss.sinks"},
    {"\\response/1", "xss.sinks"},
    {"Symfony\\Component\\HttpFoundation\\Response.<init>/3", "xss.sinks"},
    {"Illuminate\\Support\\Facades\\Blade::render/2", "xss.sinks"},
    {"Twig\\Environment::render/2", "xss.sinks"},
    {"Illuminate\\Support\\Facades\\Redirect::away/1", "open-redirect.sinks"},
    {"\\redirect/1", "open-redirect.sinks"},
    {"Symfony\\Component\\HttpFoundation\\RedirectResponse.<init>/1", "open-redirect.sinks"},
    {"Symfony\\Component\\HttpFoundation\\RedirectResponse::setTargetUrl/1", "open-redirect.sinks"},
    {"\\simplexml_load_string/3", "xxe.sinks"},
    {"\\DOMDocument::loadXML/2", "xxe.sinks"},
    {"\\DOMDocument::load/1", "xxe.sinks"},
    {"\\XMLReader::XML/3", "xxe.sinks"},
    {"\\DOMXPath::query/1", "xpath.sinks"},
    {"\\DOMXPath::evaluate/1", "xpath.sinks"},
    {"\\SimpleXMLElement::xpath/1", "xpath.sinks"},
    {"\\ldap_search/3", "ldap.sinks"},
    {"\\ldap_read/4", "ldap.sinks"},
    {"\\ldap_list/4", "ldap.sinks"},
    {"\\fsockopen/5", "ssrf.sinks"},
    {"\\proc_open/3", "code-injection.sinks"},
    {"\\yaml_parse/1", "deserialization.sinks"},
    {"Symfony\\Component\\Yaml\\Yaml::parse/1", "deserialization.sinks"},
  };

  /**
   * The PHP callee keys that carry untrusted data IN.
   *
   * <p>They are the framework's request getters and the reads that bring a stored value back into
   * the program.
   */
  private static final String[] PHP_SOURCES = {
    "Illuminate\\Http\\Request::header/2",
    "Illuminate\\Http\\Request::input/2",
    "Illuminate\\Http\\Request::json/2",
    "Symfony\\Component\\HttpFoundation\\Request::getContent/0",
    "Doctrine\\DBAL\\Connection::fetchOne/1",
    "Illuminate\\Support\\Facades\\DB::selectOne/1",
    "Illuminate\\Support\\Facades\\Redis::get/1",
  };

  @Test
  @DisplayName("the PHP entry points are sinks")
  void thePhpEntryPointsAreSinks() {
    for (String[] entry : PHP_SINKS) {
      assertThat(CATALOGUE.lookupAll(entry[0], SourceLanguage.PHP))
          .as(entry[0] + " is a " + entry[1] + " for php")
          .anyMatch(family -> entry[1].equals(family.name()));
    }
  }

  @Test
  @DisplayName("the framework's getters and the database's rows are sources")
  void theFrameworkGettersAndTheDatabaseRowsAreSources() {
    for (String callee : PHP_SOURCES) {
      assertThat(CATALOGUE.sourceFamilyMatcher().matches(callee, SourceLanguage.PHP))
          .as(callee + " carries untrusted data in, for php")
          .isTrue();
    }
  }

  @Test
  @DisplayName("the PHP bucket does not leak into another language")
  void thePhpBucketDoesNotLeakIntoAnotherLanguage() {
    // The control. The buckets are language-scoped on purpose: a Java program
    // that calls a method named prepare is not a PHP database handle, and a
    // Java program that reads a row is not reading a PHP connection.
    assertThat(
            CATALOGUE.lookupAll("Illuminate\\Support\\Facades\\DB::select/1", SourceLanguage.JAVA))
        .as("the php bucket does not leak into java")
        .noneMatch(family -> "sql.sinks".equals(family.name()));
    assertThat(
            CATALOGUE
                .sourceFamilyMatcher()
                .matches("Illuminate\\Http\\Request::header/2", SourceLanguage.JAVA))
        .as("a Java program has no PHP request object")
        .isFalse();
    assertThat(CATALOGUE.lookupAll("<php>.echo/1", SourceLanguage.JAVA))
        .as("and the php echo sink is not a java one")
        .noneMatch(family -> "xss.sinks".equals(family.name()));
  }

  /**
   * A controller that puts a value it was given into a SQL statement.
   *
   * <p>The request getter is the source and the facade's select is the sink.
   */
  private static final String PHP_SQL =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Http\\Request;
      use Illuminate\\Support\\Facades\\DB;

      class ProbeController
      {
          public function __invoke(Request $request)
          {
              $data = $request->header('X-Probe', '');
              DB::select('SELECT * FROM users WHERE id = \\'' . $data . '\\'');
              return response()->json(['ok' => 1]);
          }
      }
      """;

  /**
   * A controller that writes a value it was given into the page.
   *
   * <p>{@code echo} is how PHP writes to the response.
   */
  private static final String PHP_ECHO =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Http\\Request;

      class ProbeController
      {
          public function __invoke(Request $request)
          {
              $data = $request->header('X-Probe', '');
              echo '<div class="comment">' . $data . '</div>';
          }
      }
      """;

  /**
   * The same program, with the value routed through an arrow function.
   *
   * <p>An arrow function returns its expression, and the front end has to say so: without the
   * RETURN the function's summary carries nothing and the taint stops inside it.
   */
  private static final String PHP_ARROW =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Http\\Request;
      use Illuminate\\Support\\Facades\\DB;

      class ProbeController
      {
          public function __invoke(Request $request)
          {
              $transform = fn (string $v): string => trim($v);
              $data = $transform($request->header('X-Probe', ''));
              DB::select('SELECT * FROM users WHERE id = \\'' . $data . '\\'');
              return response()->json(['ok' => 1]);
          }
      }
      """;

  /**
   * The same program, with the value handed to a template engine inside an array.
   *
   * <p>A container that holds a tainted value is itself tainted, or a sink that receives the whole
   * container sees a clean one.
   */
  private static final String PHP_ARRAY =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Http\\Request;

      class ProbeController
      {
          public function __invoke(Request $request)
          {
              $data = $request->header('X-Probe', '');
              $html = \\Illuminate\\Support\\Facades\\Blade::render(
                  '<div class="comment">{!! $comment !!}</div>', ['comment' => $data]);
              echo $html;
          }
      }
      """;

  /**
   * A controller that reads a row back and puts it into a statement.
   *
   * <p>The row is the untrusted value: it carries whatever an earlier request stored in it. The
   * read is a call the engine cannot see inside, so it is the catalogue that has to say the read is
   * a source -- and the catalogue only reaches a call the engine has already decided to be a call.
   */
  private static final String PHP_STORED_ROW =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Support\\Facades\\DB;

      class ProbeController
      {
          public function __invoke()
          {
              $row = DB::selectOne('SELECT name FROM users LIMIT 1');
              $data = $row->name;
              DB::select('SELECT * FROM users WHERE name = \\'' . $data . '\\'');
              return response()->json(['ok' => 1]);
          }
      }
      """;

  /**
   * A controller that puts the request's text into the base of an LDAP search.
   *
   * <p>The base is the second argument, not the third: the filter is the third, and a program that
   * only names the third position never sees this shape.
   */
  private static final String PHP_LDAP_BASE =
      """
      <?php
      namespace App\\Http\\Controllers;

      use Illuminate\\Http\\Request;

      class ProbeController
      {
          public function __invoke(Request $request)
          {
              $data = $request->header('X-Probe', '');
              ldap_read(
                  ldap_connect('ldap://directory:1389'),
                  'uid=' . $data . ',ou=people,dc=example,dc=com',
                  '(objectClass=inetOrgPerson)',
                  ['cn']);
              return response()->json(['ok' => 1]);
          }
      }
      """;

  @Test
  @DisplayName("a tainted value reaches the PHP SQL sink")
  void aTaintedValueReachesThePhpSqlSink(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_SQL);

    List<Finding> findings = scan(new PhpTacFrontend(), PHP_SQL, file, new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "DB::select"))
        .as("the request header is the request, and the request reached the database")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value reaches the echo sink")
  void aTaintedValueReachesTheEchoSink(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_ECHO);

    List<Finding> findings = scan(new PhpTacFrontend(), PHP_ECHO, file, new XssInjectionChecker());

    assertThat(hasFinding(findings, XSS_RULE, "<php>.echo"))
        .as("echo writes the value into the page, so the XSS rule fires")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value survives an arrow function")
  void aTaintedValueSurvivesAnArrowFunction(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_ARROW);

    List<Finding> findings = scan(new PhpTacFrontend(), PHP_ARROW, file, new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "DB::select"))
        .as("the arrow function returns the value it was given, so the taint comes back out")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted value in an array reaches the template sink")
  void aTaintedValueInAnArrayReachesTheTemplateSink(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_ARRAY);

    List<Finding> findings = scan(new PhpTacFrontend(), PHP_ARRAY, file, new XssInjectionChecker());

    assertThat(hasFinding(findings, XSS_RULE, "Blade::render"))
        .as("the container holds a tainted value, so the template engine renders one")
        .isTrue();
  }

  @Test
  @DisplayName("a stored row reaches the sink")
  void aStoredRowReachesTheSink(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_STORED_ROW);

    List<Finding> findings =
        scan(new PhpTacFrontend(), PHP_STORED_ROW, file, new SqlInjectionChecker());

    assertThat(hasFinding(findings, SQL_RULE, "DB::select"))
        .as("the row read back from the database is the request the earlier one made")
        .isTrue();
  }

  @Test
  @DisplayName("a tainted base reaches the LDAP sink")
  void aTaintedBaseReachesTheLdapSink(@TempDir Path directory) throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_LDAP_BASE);

    List<Finding> findings =
        scan(new PhpTacFrontend(), PHP_LDAP_BASE, file, new LdapInjectionChecker());

    assertThat(hasFinding(findings, LDAP_RULE, "ldap_read"))
        .as("the base of the search is part of the filter the server evaluates")
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
   * Whether the PHP front end can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the package the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean phpAvailable() {
    Path node = PhpSidecarClient.locateNode();
    Path script = PhpSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("php-parser"));
  }
}
