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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;
import org.yaml.snakeyaml.Yaml;

/**
 * A rule says the language it is for.
 *
 * <p><b>Why the two passthrough tables needed this when the sink families already had it.</b> The
 * sink families, the checkers and the library-semantics tables all say which language they are for.
 * The two tables that say "the ARGUMENT's taint reaches the RESULT" and "the RECEIVER's taint
 * reaches the RESULT" said nothing -- a rule written for one language's library was applied to
 * every language.
 *
 * <p><b>Why that was not a theoretical problem.</b> These tables match the callee signature, and
 * the front end emits it in short form when the symbol solver could not resolve the fully qualified
 * name -- so the patterns accept a package prefix of any length, INCLUDING NONE. A pattern for
 * {@code java.lang.String.substring} therefore matches a receiver merely CALLED {@code String}, and
 * a pattern for {@code java.util.Map.get} matches a receiver merely CALLED {@code Map} -- in any
 * language. Without a language to scope the rule to, the JDK's rules applied to code that has no
 * JDK at all, and a same-named symbol in another language inherited rules that were never written
 * for it.
 *
 * <p><b>What is pinned here.</b> The mechanism: the same callee signature resolves differently in
 * two languages. The data: every family in both tables declares the languages it is for, so the
 * convention that named a family after its language is now something the loader reads rather than
 * something a reader has to notice. And the chain: the engine passes the language of the code it is
 * analysing, so the rule reaches the finding and not only the configuration.
 *
 * <p><b>Why the engine case runs ONE translation twice.</b> The question is which language a rule
 * applies to, and the language of a function is the only variable in it. So the same TAC -- the one
 * a real front end produced, with its stores and its facts and everything the engine needs -- is
 * analysed twice, once as Java and once as Go. A hand-built TAC would not do: it lacks the stores a
 * front end emits, so the taint that crosses a call would not reach the sink and the case would
 * pass for the wrong reason.
 */
@DisplayName("a rule says the language it is for")
class LanguageScopedPassthroughTest {

  /** The rule the SQL checker reports under. */
  private static final String SQL_RULE = "dfa-taint-sql-injection";

  @TempDir Path tmp;

  /**
   * The result of {@code Path.resolve} is built from the argument, so the taint crosses it.
   *
   * <p>And {@code executeQuery} is a SQL sink, so a taint that crosses lands in a finding.
   */
  private static final String RESOLVE =
      """
      package mini;
      import java.nio.file.Path;
      import java.nio.file.Paths;
      import java.sql.SQLException;
      import java.sql.Statement;
      public class Caller {
        public void go(Statement st, String userInput) throws SQLException {
          Path base = Paths.get("/srv");
          Path resolved = base.resolve(userInput);
          st.executeQuery(resolved.toString());
        }
      }
      """;

  // ------------------------------------------------------------------ mechanism

  @Test
  @DisplayName("the JDK's rules do not apply to code written in another language")
  void theJdksRulesDoNotApplyToCodeWrittenInAnotherLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config.isReceiverPassthroughEligible(
                "java.lang.String.substring/1", SourceLanguage.JAVA))
        .as("the JDK's rules are the JVM's rules")
        .isTrue();
    assertThat(
            config.isReceiverPassthroughEligible("java.lang.String.substring/1", SourceLanguage.GO))
        .as("a Go program has no java.lang.String, so the JDK's rules have nothing to say about it")
        .isFalse();
    assertThat(
            config.isReceiverPassthroughEligible(
                "java.lang.String.substring/1", SourceLanguage.CSHARP))
        .as("and .NET spells the same operation Substring, in upper case")
        .isFalse();
  }

  @Test
  @DisplayName("a C# rule does not apply to Java code")
  void aCsharpRuleDoesNotApplyToJavaCode() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Text.StringBuilder.Append/1", SourceLanguage.CSHARP))
        .as("the C# table is the C# table")
        .isTrue();
    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Text.StringBuilder.Append/1", SourceLanguage.JAVA))
        .as("a Java program has no System.Text.StringBuilder")
        .isFalse();
  }

  @Test
  @DisplayName("the argument table is scoped the same way")
  void theArgumentTableIsScopedTheSameWay() {
    ArgumentPassthrough table = ConfigLoader.loadFromClasspath().argumentPassthrough();

    assertThat(table.argIndexesFor("java.nio.file.Path.resolve/1", SourceLanguage.JAVA))
        .as("the JDK's Path.resolve names its first user argument")
        .containsExactly(0);
    assertThat(table.argIndexesFor("java.nio.file.Path.resolve/1", SourceLanguage.KOTLIN))
        .as("Kotlin runs on the JVM, so it reaches the same class")
        .containsExactly(0);
    assertThat(table.argIndexesFor("java.nio.file.Path.resolve/1", SourceLanguage.GO))
        .as("a Go program has no java.nio.file.Path")
        .isEmpty();
    assertThat(table.argIndexesFor("java.nio.file.Path.resolve/1", SourceLanguage.CSHARP))
        .as("and .NET spells the same operation Path.Combine")
        .isEmpty();
  }

  @Test
  @DisplayName("the scope-free view still sees every rule")
  void theScopeFreeViewStillSeesEveryRule() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.isReceiverPassthroughEligible("java.lang.String.substring/1"))
        .as("the scope-free view says what the table CONTAINS")
        .isTrue();
    assertThat(config.argumentPassthrough().argIndexesFor("java.nio.file.Path.resolve/1"))
        .as("and it is still the table's content that it reports")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a rule that names languages does not apply to an unknown language")
  void aRuleThatNamesLanguagesDoesNotApplyToAnUnknownLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config.isReceiverPassthroughEligible(
                "java.lang.String.substring/1", SourceLanguage.UNKNOWN))
        .as("the engine cannot tell the languages apart when it does not know the language")
        .isFalse();
  }

  // ----------------------------------------------------------------------- data

  @Test
  @DisplayName("every family in the two tables declares the languages it is for")
  void everyFamilyInTheTwoTablesDeclaresTheLanguagesItIsFor() throws IOException {
    for (String file : List.of("receiver-passthrough.yaml", "argument-passthrough.yaml")) {
      Map<?, ?> families = (Map<?, ?>) yaml(file).get("families");
      assertThat(families).as("%s must carry its families", file).isNotNull();
      List<String> unscoped = new ArrayList<>();
      for (Map.Entry<?, ?> family : families.entrySet()) {
        Object languages = ((Map<?, ?>) family.getValue()).get("languages");
        if (!(languages instanceof List) || ((List<?>) languages).isEmpty()) {
          unscoped.add(String.valueOf(family.getKey()));
        }
      }
      assertThat(unscoped)
          .as(
              "a family that declares no language applies to every language -- and every entry in"
                  + " these tables names one language's library, so an undeclared family is a rule"
                  + " that has lost the only thing that kept it off another language's code")
          .isEmpty();
    }
  }

  @Test
  @DisplayName("a family named for a language declares that language")
  void aFamilyNamedForALanguageDeclaresThatLanguage() throws IOException {
    for (String file : List.of("receiver-passthrough.yaml", "argument-passthrough.yaml")) {
      Map<?, ?> families = (Map<?, ?>) yaml(file).get("families");
      for (Map.Entry<?, ?> family : families.entrySet()) {
        String name = String.valueOf(family.getKey());
        String suffix = name.substring(name.lastIndexOf('-') + 1);
        if (!List.of("java", "kotlin", "scala", "csharp", "go").contains(suffix)) continue;
        Object languages = ((Map<?, ?>) family.getValue()).get("languages");
        assertThat(languages)
            .as("%s is named for %s, so it must say it is for %s", name, suffix, suffix)
            .isInstanceOf(List.class);
        List<String> declared = new ArrayList<>();
        for (Object language : (List<?>) languages) {
          declared.add(String.valueOf(language));
        }
        assertThat(declared)
            .as("%s is named for %s, so it must say it is for %s", name, suffix, suffix)
            .contains(suffix);
      }
    }
  }

  // --------------------------------------------------------------------- buckets

  /**
   * A family whose symbols only ONE language can mention.
   *
   * <p>The shared pattern is the one every language of the family can reach; the bucket holds the
   * symbol that only C#'s code can mention.
   */
  private static final String RECEIVER_WITH_BUCKET =
      """
      families:
        collection-getters:
          enabled: true
          languages: [java, kotlin, scala, groovy, csharp]
          patterns:
            - '^((?:[\\w.]+\\.)*)Map\\.get(/\\d+)?'
          by_language:
            csharp:
              - '^((?:[\\w.]+\\.)*)Dictionary\\.get(/\\d+)?'
      """;

  /** A bucket for a language the family does not cover: the family never applies there. */
  private static final String RECEIVER_WITH_DEAD_BUCKET =
      """
      families:
        collection-getters:
          enabled: true
          languages: [java, kotlin, scala, groovy]
          patterns:
            - '^((?:[\\w.]+\\.)*)Map\\.get(/\\d+)?'
          by_language:
            csharp:
              - '^((?:[\\w.]+\\.)*)Dictionary\\.get(/\\d+)?'
      """;

  /** The argument table's shape: the pattern carries the position it names. */
  private static final String ARGUMENT_WITH_BUCKET =
      """
      families:
        path-argument-passthrough:
          enabled: true
          languages: [java, kotlin, scala, groovy, go]
          patterns:
            - pattern: '^java\\.nio\\.file\\.Path\\.resolve(/\\d+)?'
              arg_index: 0
          by_language:
            go:
              - pattern: '^path/filepath\\.Join(/\\d+)?'
                arg_index: 0
      """;

  @Test
  @DisplayName("a bucket's patterns apply to that language and to no other")
  void aBucketsPatternsApplyToThatLanguageAndToNoOther() throws IOException {
    EngineConfig config = loadTables("receiver-bucket", RECEIVER_WITH_BUCKET, null);

    assertThat(config.isReceiverPassthroughEligible("java.util.Map.get/1", SourceLanguage.JAVA))
        .as("the shared pattern is reachable from every language the family covers")
        .isTrue();
    assertThat(config.isReceiverPassthroughEligible("java.util.Map.get/1", SourceLanguage.CSHARP))
        .as("and the family covers that language too")
        .isTrue();
    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Collections.Generic.Dictionary.get/1", SourceLanguage.CSHARP))
        .as("the bucket's pattern applies in the language the bucket names")
        .isTrue();
    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Collections.Generic.Dictionary.get/1", SourceLanguage.JAVA))
        .as("and to no other language")
        .isFalse();
  }

  @Test
  @DisplayName("a bucket for a language the family does not cover can never apply")
  void aBucketForALanguageTheFamilyDoesNotCoverCanNeverApply() throws IOException {
    EngineConfig config = loadTables("dead-bucket", RECEIVER_WITH_DEAD_BUCKET, null);

    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Collections.Generic.Dictionary.get/1", SourceLanguage.CSHARP))
        .as("the family does not cover C#, so its bucket cannot fire there")
        .isFalse();
    assertThat(
            config.isReceiverPassthroughEligible(
                "System.Collections.Generic.Dictionary.get/1", SourceLanguage.JAVA))
        .as("nor anywhere else")
        .isFalse();
  }

  @Test
  @DisplayName("the argument table's buckets work the same way")
  void theArgumentTablesBucketsWorkTheSameWay() throws IOException {
    EngineConfig config = loadTables("argument-bucket", null, ARGUMENT_WITH_BUCKET);

    assertThat(
            config
                .argumentPassthrough()
                .argIndexesFor("java.nio.file.Path.resolve/1", SourceLanguage.JAVA))
        .as("the shared pattern names the first user argument")
        .containsExactly(0);
    assertThat(
            config.argumentPassthrough().argIndexesFor("path/filepath.Join/2", SourceLanguage.GO))
        .as("the bucket's pattern applies in the language the bucket names")
        .containsExactly(0);
    assertThat(
            config.argumentPassthrough().argIndexesFor("path/filepath.Join/2", SourceLanguage.JAVA))
        .as("and to no other language")
        .isEmpty();
  }

  @Test
  @DisplayName("no family in the two tables has a bucket outside its own languages")
  void noFamilyInTheTwoTablesHasABucketOutsideItsOwnLanguages() throws IOException {
    for (String file : List.of("receiver-passthrough.yaml", "argument-passthrough.yaml")) {
      Map<?, ?> families = (Map<?, ?>) yaml(file).get("families");
      for (Map.Entry<?, ?> family : families.entrySet()) {
        Map<?, ?> spec = (Map<?, ?>) family.getValue();
        Object buckets = spec.get("by_language");
        if (!(buckets instanceof Map)) continue;
        List<String> declared = new ArrayList<>();
        Object languages = spec.get("languages");
        if (languages instanceof List) {
          for (Object language : (List<?>) languages) {
            declared.add(String.valueOf(language));
          }
        }
        for (Object bucket : ((Map<?, ?>) buckets).keySet()) {
          assertThat(declared)
              .as(
                  "%s: the bucket '%s' has to be among the family's own languages, or the family"
                      + " never applies there and the bucket can never fire",
                  file, bucket)
              .contains(String.valueOf(bucket));
        }
      }
    }
  }

  /**
   * Loads the two passthrough tables from a directory of their own, so the test can state a shape
   * the shipped tables do not use yet.
   */
  private EngineConfig loadTables(String dirName, String receiverYaml, String argumentYaml)
      throws IOException {
    Path dir = Files.createDirectories(tmp.resolve(dirName));
    if (receiverYaml != null) {
      Files.writeString(dir.resolve("receiver-passthrough.yaml"), receiverYaml);
    }
    if (argumentYaml != null) {
      Files.writeString(dir.resolve("argument-passthrough.yaml"), argumentYaml);
    }
    return ConfigLoader.loadFromDirectory(dir);
  }

  // --------------------------------------------------------------------- engine

  @Test
  @DisplayName("the JDK's rule carries the taint in Java")
  void theJdksRuleCarriesTheTaintInJava() throws Exception {
    List<Finding> findings = findingsFor(SourceLanguage.JAVA);

    assertThat(findings)
        .as("the JDK's Path.resolve carries its argument's taint to the result")
        .isNotEmpty();
  }

  @Test
  @DisplayName("the JDK's rule does not carry the taint in another language")
  void theJdksRuleDoesNotCarryTheTaintInAnotherLanguage() throws Exception {
    List<Finding> findings = findingsFor(SourceLanguage.GO);

    assertThat(findings)
        .as(
            "the same call, in code the JDK's rules are not for, carries nothing -- and the pair"
                + " above is the control that says the shape does carry something")
        .isEmpty();
  }

  /**
   * Translates the source once and analyses it as the given language.
   *
   * @param language the language the function is taken to be written in
   * @return the SQL findings the checker reported
   */
  private List<Finding> findingsFor(SourceLanguage language) throws Exception {
    Path file = tmp.resolve("Caller.java");
    Files.writeString(file, RESOLVE);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram translated = new TacTranslator(parsed).translate();

    TacProgram program = new TacProgram(file, "mini.Caller");
    for (TacFunction fn : translated.functions()) {
      program.addFunction(inLanguage(fn, language));
    }

    FindingReporter reporter =
        new DfaEngine(
                ConfigLoader.loadFromClasspath(),
                List.of(new SqlInjectionChecker()),
                "global",
                null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (SQL_RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The same function, taken to be written in another language.
   *
   * <p>Everything else is carried over unchanged -- the instructions, the parameters, the
   * annotations -- so the only thing that differs between the two runs is the language the rule is
   * asked about.
   *
   * @param fn the function
   * @param language the language to take it to be written in
   * @return the function, in that language
   */
  private static TacFunction inLanguage(TacFunction fn, SourceLanguage language) {
    return new TacFunction(
        fn.name(),
        fn.signature(),
        fn.returnType(),
        fn.params(),
        fn.locals(),
        fn.instructions(),
        fn.classAnnotations(),
        fn.methodAnnotations(),
        fn.fieldAnnotations(),
        fn.paramAnnotations(),
        fn.enclosingClass(),
        fn.origin(),
        fn.tryRegions(),
        language,
        fn.irPrecision(),
        fn.isSynthetic(),
        fn.annotationValues());
  }

  /** One of the configuration files, read from the classpath. */
  private static Map<String, Object> yaml(String name) throws IOException {
    try (InputStream in =
        LanguageScopedPassthroughTest.class.getClassLoader().getResourceAsStream(name)) {
      assertThat(in).as("%s must be on the classpath", name).isNotNull();
      return new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8));
    }
  }
}
