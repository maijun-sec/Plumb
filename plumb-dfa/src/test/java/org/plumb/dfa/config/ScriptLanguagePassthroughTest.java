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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;
import org.yaml.snakeyaml.Yaml;

/**
 * The five script languages and the shape their front ends speak.
 *
 * <p><b>Why this class exists.</b> The Java, Kotlin, Scala, C# and Go tables were written against a
 * front end that spells a callee signature {@code package.Class.method/arity}. The four side cars
 * that serve JavaScript, TypeScript, ArkTS, Python and PHP do not spell it that way, and the
 * difference is not cosmetic: a rule written for the Java shape never matches the script shape, so
 * the rule is not "less accurate" -- it is dead.
 *
 * <p><b>What the shapes are.</b> Every signature named below was read out of a side car's own
 * output, not out of a library's documentation:
 *
 * <ul>
 *   <li>the library's OWN NAME is the owner -- {@code String.String/1}, {@code
 *       parseInt.parseInt/2}, {@code escape/1};
 *   <li>the MODULE the call reached is the owner -- {@code JSON.parse/2}, {@code path.join/2},
 *       {@code json.dumps/1};
 *   <li>the owner is a PLACEHOLDER for "the type could not be resolved" -- {@code
 *       <unknown>.strip/0} in Python, {@code \::getMessage/0} in PHP;
 *   <li>and sometimes there is NO owner at all -- {@code trim/0} in JavaScript, {@code
 *       readFileSync/1}.
 * </ul>
 *
 * <p><b>What is pinned here.</b> That each shape reaches the rule that was written for it; that a
 * rule written for one language does not fire on another; and that the prefix a rule accepts is
 * spelled in the form that cannot blow up the matcher.
 */
@DisplayName("the script languages and the shape their front ends speak")
class ScriptLanguagePassthroughTest {

  // ---------------------------------------------------------------- the shapes

  /** The JavaScript front end names no owner at all for a method on a value. */
  private static final String JS_BARE_METHOD = "trim/0";

  /** The TypeScript front end names the class the method belongs to. */
  private static final String TS_CLASS_METHOD = "String.trim/0";

  /** A global's owner is the global's own name. */
  private static final String JS_GLOBAL_CONSTRUCTOR = "String.String/1";

  /** A module-level function's owner is the module it was imported from. */
  private static final String TS_MODULE_FUNCTION = "path.join/2";

  /** The Python front end says "unresolved" rather than guessing. */
  private static final String PY_PLACEHOLDER_METHOD = "<unknown>.strip/0";

  /** A PHP global function carries a leading backslash and no owner. */
  private static final String PHP_GLOBAL_FUNCTION = "\\trim/1";

  /** A PHP method whose class could not be resolved has an EMPTY class. */
  private static final String PHP_EMPTY_CLASS_METHOD = "\\::getMessage/0";

  // ------------------------------------------------------------ the mechanisms

  @Test
  @DisplayName("a JavaScript method with no owner still reaches the receiver rule")
  void aJavaScriptMethodWithNoOwnerStillReachesTheReceiverRule() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.isReceiverPassthroughEligible(JS_BARE_METHOD, SourceLanguage.JAVASCRIPT))
        .as("`u.trim()` is `trim/0` in JavaScript, and the receiver's text is the result")
        .isTrue();
  }

  @Test
  @DisplayName("a TypeScript method with a class owner still reaches the receiver rule")
  void aTypeScriptMethodWithAClassOwnerStillReachesTheReceiverRule() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.isReceiverPassthroughEligible(TS_CLASS_METHOD, SourceLanguage.TYPESCRIPT))
        .as("`u.trim()` is `String.trim/0` in TypeScript, and it is the same call")
        .isTrue();
  }

  @Test
  @DisplayName("the script languages' receiver rules do not fire on Java code")
  void theScriptLanguagesReceiverRulesDoNotFireOnJavaCode() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    // The BARE shape is the script languages' own: the JavaScript front end
    // names no owner for a method on a value, and the JDK's rules all name
    // the class they belong to.
    assertThat(config.isReceiverPassthroughEligible("trim/0", SourceLanguage.JAVA))
        .as("a Java program has no java.lang.String.trim, and the JDK's rule names the class")
        .isFalse();
    assertThat(config.isReceiverPassthroughEligible("<unknown>.strip/0", SourceLanguage.JAVA))
        .as("and no `<unknown>` placeholder")
        .isFalse();
    // The CLASS-QUALIFIED shape is shared, and that is right: `String.trim`
    // is `java.lang.String.trim` in Java, so the JDK's own rule reaches it.
    assertThat(config.isReceiverPassthroughEligible(TS_CLASS_METHOD, SourceLanguage.JAVA))
        .as("the JDK's own rule reaches the JDK's own method")
        .isTrue();
    assertThat(config.isReceiverPassthroughEligible(TS_CLASS_METHOD, SourceLanguage.TYPESCRIPT))
        .as("and the script languages' rule reaches the script languages' method")
        .isTrue();
  }

  @Test
  @DisplayName("a global's own name is the owner, and the rule reads the argument")
  void aGlobalsOwnNameIsTheOwnerAndTheRuleReadsTheArgument() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .argumentPassthrough()
                .argIndexesFor(JS_GLOBAL_CONSTRUCTOR, SourceLanguage.JAVASCRIPT))
        .as("`String(x)` is `String.String/1`, and the result is the argument")
        .containsExactly(0);
    assertThat(
            config
                .argumentPassthrough()
                .argIndexesFor(JS_GLOBAL_CONSTRUCTOR, SourceLanguage.TYPESCRIPT))
        .as("the same call in TypeScript")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a module-level function's owner is the module")
  void aModuleLevelFunctionsOwnerIsTheModule() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .argumentPassthrough()
                .argIndexesFor(TS_MODULE_FUNCTION, SourceLanguage.TYPESCRIPT))
        .as("`path.join(a, b)` is `path.join/2`, and the result is built from both parts")
        .containsExactly(0, 1);
  }

  @Test
  @DisplayName("the Python placeholder is the owner, and the rule reads the receiver")
  void thePythonPlaceholderIsTheOwnerAndTheRuleReadsTheReceiver() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.isReceiverPassthroughEligible(PY_PLACEHOLDER_METHOD, SourceLanguage.PYTHON3))
        .as("`u.strip()` is `<unknown>.strip/0`, and the receiver's text is the result")
        .isTrue();
    assertThat(
            config
                .argumentPassthrough()
                .argIndexesFor("<unknown>.format/1", SourceLanguage.PYTHON3))
        .as("`\"{}\".format(x)` is `<unknown>.format/1`, and the VALUE is the argument")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a PHP global function carries a leading backslash")
  void aPhpGlobalFunctionCarriesALeadingBackslash() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.argumentPassthrough().argIndexesFor(PHP_GLOBAL_FUNCTION, SourceLanguage.PHP))
        .as("`trim($u)` is `\\trim/1`, and the result is the argument's text")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a PHP method whose class is unknown has an empty class")
  void aPhpMethodWhoseClassIsUnknownHasAnEmptyClass() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.isReceiverPassthroughEligible(PHP_EMPTY_CLASS_METHOD, SourceLanguage.PHP))
        .as("`$e->getMessage()` is `\\::getMessage/0`, and the message is the exception's")
        .isTrue();
  }

  // ---------------------------------------------------------- the source rules

  @Test
  @DisplayName("the socket read is a source under the name the front end gives it")
  void theSocketReadIsASourceUnderTheNameTheFrontEndGivesIt() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    SinkFamily network = registry.byName("taint.sources.network").orElseThrow();
    assertThat(network.matches("<unknown>.recv/1", SourceLanguage.PYTHON3))
        .as(
            "the Python front end cannot resolve the socket's type, so the owner is the"
                + " placeholder")
        .isTrue();
    assertThat(network.matches("socket.recv/1", SourceLanguage.PYTHON3))
        .as("and the entry that named the class still works when a front end does resolve it")
        .isTrue();
  }

  @Test
  @DisplayName("both spellings of the environment read are sources")
  void bothSpellingsOfTheEnvironmentReadAreSources() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    SinkFamily env = registry.byName("taint.sources.env").orElseThrow();
    assertThat(env.matches("os.environ.get/1", SourceLanguage.PYTHON3)).isTrue();
    assertThat(env.matches("os.getenv/1", SourceLanguage.PYTHON3))
        .as("`os.getenv` is the other spelling of the same read")
        .isTrue();
  }

  @Test
  @DisplayName("the file read is a source under the names the front ends give it")
  void theFileReadIsASourceUnderTheNamesTheFrontEndsGiveIt() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    SinkFamily io = registry.byName("taint.sources.io").orElseThrow();
    assertThat(io.matches("<unknown>.read/0", SourceLanguage.PYTHON3))
        .as("it is the READ that is the source, not the open")
        .isTrue();
    assertThat(io.matches("<unknown>.read_text/0", SourceLanguage.PYTHON3)).isTrue();
    assertThat(io.matches("fs.readFileSync/2", SourceLanguage.TYPESCRIPT)).isTrue();
    assertThat(io.matches("fs.createReadStream/1", SourceLanguage.TYPESCRIPT)).isTrue();
    assertThat(io.matches("\\file_get_contents/1", SourceLanguage.PHP)).isTrue();
    assertThat(io.matches("\\stream_get_contents/1", SourceLanguage.PHP)).isTrue();
  }

  @Test
  @DisplayName("Python's format string is the receiver of the format call")
  void pythonsFormatStringIsTheReceiverOfTheFormatCall() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    SinkFamily formatString = registry.byName("format-string.sinks").orElseThrow();
    assertThat(formatString.matches("<unknown>.format/1", SourceLanguage.PYTHON3))
        .as("`\"...\".format(x)` is the Python spelling of a format string")
        .isTrue();
    assertThat(formatString.argIndexFor("<unknown>.format/1", SourceLanguage.PYTHON3))
        .as("and the format is the RECEIVER, not the value that is substituted into it")
        .isEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  @DisplayName("the browser's copy of the request is a source")
  void theBrowsersCopyOfTheRequestIsASource() {
    SinkFamilyRegistry registry = ConfigLoader.loadFromClasspath().sinkFamilies();

    SinkFamily web = registry.byName("taint.sources.web").orElseThrow();
    List<String> scriptVariables = web.sourceVariablesFor(SourceLanguage.TYPESCRIPT);
    List<String> pythonVariables = web.sourceVariablesFor(SourceLanguage.PYTHON3);
    assertThat(scriptVariables)
        .as("a URL's query string is written by whoever sent the link")
        .contains("location.search");
    assertThat(pythonVariables)
        .as("a framework's request object carries the query string and the form")
        .contains("request.args", "request.form");
  }

  // -------------------------------------------------------------- the tables

  @Test
  @DisplayName("the three script families declare the languages they are for")
  void theThreeScriptFamiliesDeclareTheLanguagesTheyAreFor() throws IOException {
    Map<String, Object> arguments =
        (Map<String, Object>) yaml("argument-passthrough.yaml").get("families");
    Map<String, Object> receivers =
        (Map<String, Object>) yaml("receiver-passthrough.yaml").get("families");

    assertThat(arguments).containsKey("library-argument-passthrough-javascript");
    assertThat(arguments).containsKey("library-argument-passthrough-python");
    assertThat(arguments).containsKey("library-argument-passthrough-php");
    assertThat(receivers).containsKey("library-receiver-passthrough-javascript");
    assertThat(receivers).containsKey("library-receiver-passthrough-python");
    assertThat(receivers).containsKey("library-receiver-passthrough-php");

    assertThat(languagesOf(arguments.get("library-argument-passthrough-javascript")))
        .containsExactlyInAnyOrder("javascript", "typescript", "arkts");
    assertThat(languagesOf(arguments.get("library-argument-passthrough-python")))
        .containsExactly("python3");
    assertThat(languagesOf(arguments.get("library-argument-passthrough-php")))
        .containsExactly("php");
    assertThat(languagesOf(receivers.get("library-receiver-passthrough-javascript")))
        .containsExactlyInAnyOrder("javascript", "typescript", "arkts");
  }

  @Test
  @DisplayName("no rule in the two tables accepts a prefix that can blow up the matcher")
  void noRuleInTheTwoTablesAcceptsAPrefixThatCanBlowUpTheMatcher() throws IOException {
    // A prefix written as `((?:[\w.]+\.)*)` lets the inner class swallow the
    // dots, so a run of k dots can be partitioned 2^(k-1) ways: 24 dots cost
    // 2.9 seconds and 26 dots cost 10.2 seconds, per pattern, per call site.
    // The form that accepts the same prefixes and stays linear is
    // `(?:[\w.]+\.)?` -- the inner class still swallows the dots, so one
    // repetition still covers any number of package segments.
    Pattern nested = Pattern.compile("\\^\\(\\(\\?:\\[\\\\w\\.\\]\\+\\\\\\.\\)\\*\\)");
    List<String> offenders = new ArrayList<>();
    for (String file : List.of("receiver-passthrough.yaml", "argument-passthrough.yaml")) {
      for (String pattern : patternTexts(yaml(file))) {
        if (nested.matcher(pattern).find()) {
          offenders.add(file + ": " + pattern);
        }
      }
    }
    assertThat(offenders)
        .as("a prefix written with a `*` over a class that contains a dot is exponential")
        .isEmpty();
  }

  // ------------------------------------------------------------------ helpers

  private static Map<String, Object> yaml(String file) throws IOException {
    try (InputStream in =
        ScriptLanguagePassthroughTest.class.getClassLoader().getResourceAsStream(file)) {
      assertThat(in).as("%s must be on the classpath", file).isNotNull();
      Map<String, Object> parsed =
          (Map<String, Object>) new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
      return parsed;
    }
  }

  private static List<String> languagesOf(Object family) {
    List<String> out = new ArrayList<>();
    Object languages = ((Map<?, ?>) family).get("languages");
    if (languages instanceof List) {
      for (Object language : (List<?>) languages) {
        out.add(String.valueOf(language));
      }
    }
    return out;
  }

  private static List<String> patternTexts(Map<?, ?> yaml) {
    List<String> out = new ArrayList<>();
    Map<?, ?> families = (Map<?, ?>) yaml.get("families");
    for (Map.Entry<?, ?> family : families.entrySet()) {
      Map<?, ?> spec = (Map<?, ?>) family.getValue();
      collect(spec.get("patterns"), out);
      Object buckets = spec.get("by_language");
      if (buckets instanceof Map) {
        for (Object items : ((Map<?, ?>) buckets).values()) {
          collect(items, out);
        }
      }
    }
    return out;
  }

  private static void collect(Object items, List<String> out) {
    if (!(items instanceof List)) return;
    for (Object item : (List<?>) items) {
      if (item instanceof String) {
        out.add((String) item);
      } else if (item instanceof Map && ((Map<?, ?>) item).get("pattern") instanceof String) {
        out.add((String) ((Map<?, ?>) item).get("pattern"));
      }
    }
  }
}
