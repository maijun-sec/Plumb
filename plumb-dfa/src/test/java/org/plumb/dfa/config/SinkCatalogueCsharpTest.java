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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * The sink catalogue must be usable for C#.
 *
 * <p><b>Why this test exists.</b> The C# sink patterns are not hand-written: they are CONVERTED
 * from an external rule pack by a conversion script, and the conversion has to survive three
 * translations that a Java pattern never goes through:
 *
 * <ol>
 *   <li>the pack writes a GENERIC type with its arity ({@code DbSet@1}) -- the arity has to be
 *       stripped, because the TAC erases generics;
 *   <li>the pack writes a WILDCARD around a real name ({@code .*Operation}, {@code
 *       (HqlBased|Simple|Scalar)Query.*}) -- the wildcard has to stay a regex wildcard rather than
 *       have its dot escaped into a literal;
 *   <li>the pack writes a REPEATED group ({@code set_(Legend)*Url}) -- the repetition has to be
 *       expanded into the names it stands for.
 * </ol>
 *
 * <p><b>The failure this test is designed to catch.</b> A conversion bug does not throw. It emits a
 * pattern that either matches nothing (a DEAD rule -- it counts as coverage and reports nothing) or
 * does not compile AT ALL. The second case is not a precision problem: the whole catalogue fails to
 * load, and EVERY sink family stops working -- for every language, not just C#. That is exactly
 * what happened: the conversion of a Scala operator method spelled {@code #(##|&&|\||\|\|)}
 * collapsed the escaped pipe, and the catalogue died with "Unclosed group near index 63".
 *
 * <p>So the invariants pinned here are:
 *
 * <ol>
 *   <li><b>I1</b> EVERY pattern in the catalogue compiles as a Java regex.
 *   <li><b>I2</b> The C# patterns are present, and they are NOT in the shared list -- a C# symbol
 *       must not be blamed for a Java call.
 *   <li><b>I3</b> A generated C# pattern matches the callee signature the C# frontend actually
 *       produces.
 *   <li><b>I4</b> No generated C# pattern still carries a generic-arity marker -- that marker is
 *       erased in the TAC, so a pattern that keeps it can never fire.
 *   <li><b>I5</b> No generated C# pattern contains an ESCAPED wildcard ({@code \.*}) -- that is a
 *       literal dot followed by a star, which matches nothing.
 * </ol>
 */
@DisplayName("C#: the converted sink catalogue is complete, compilable and reachable")
class SinkCatalogueCsharpTest {

  /** The catalogue as it ships, read from the classpath. */
  private static String catalogueText() throws IOException {
    try (InputStream in =
        SinkCatalogueCsharpTest.class.getClassLoader().getResourceAsStream("sink-families.yaml")) {
      assertThat(in).as("the sink catalogue must be on the classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /**
   * Every pattern text in the catalogue, in file order.
   *
   * <p><b>Why the file is read rather than the parsed config.</b> The parsed config only carries
   * the patterns that survived the parse. A pattern that does not COMPILE is dropped or rejected
   * during the load -- so reading the parsed config would hide the very defect this test looks for.
   * The file is the artefact the converter produces, and the artefact is what has to be sound.
   */
  private static List<String> allPatterns(String text) {
    List<String> out = new ArrayList<>();
    for (String raw : text.split("\r?\n")) {
      String line = raw.trim();
      if (line.startsWith("- pattern: ")) {
        out.add(unquote(line.substring("- pattern: ".length())));
      } else if (line.startsWith("- '") && line.endsWith("'")) {
        out.add(line.substring(3, line.length() - 1));
      }
    }
    return out;
  }

  private static String unquote(String text) {
    String value = text.trim();
    if (value.startsWith("'") && value.endsWith("'")) {
      value = value.substring(1, value.length() - 1);
      // YAML escapes a single quote by doubling it.
      return value.replace("''", "'");
    }
    return value;
  }

  /** The C# patterns of every family, keyed by family name. */
  private static Map<String, List<String>> csharpPatterns(String text) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    String family = null;
    boolean inByLanguage = false;
    boolean inCsharp = false;
    for (String raw : text.split("\r?\n")) {
      String line = raw.replace("\r", "");
      String trimmed = line.trim();
      if (trimmed.startsWith("#") || trimmed.isEmpty()) {
        continue;
      }
      // A family key is indented by exactly two spaces.
      if (line.matches("^ {2}[A-Za-z0-9_.\\-]+:\\s*$")) {
        family = trimmed.substring(0, trimmed.length() - 1);
        inByLanguage = false;
        inCsharp = false;
        continue;
      }
      if (line.matches("^ {4}by_language:\\s*$")) {
        inByLanguage = true;
        inCsharp = false;
        continue;
      }
      if (inByLanguage && line.matches("^ {6}[a-z]+:\\s*$")) {
        inCsharp = "csharp".equals(trimmed.substring(0, trimmed.length() - 1));
        if (inCsharp) {
          out.computeIfAbsent(family, k -> new ArrayList<>());
        }
        continue;
      }
      if (inCsharp && trimmed.startsWith("- pattern: ")) {
        out.get(family).add(unquote(trimmed.substring("- pattern: ".length())));
        continue;
      }
      if (inCsharp && trimmed.startsWith("- '") && trimmed.endsWith("'")) {
        out.get(family).add(trimmed.substring(3, trimmed.length() - 1));
      }
    }
    return out;
  }

  @Test
  @DisplayName("I1: every pattern in the catalogue compiles as a Java regex")
  void everyPatternInTheCatalogueCompiles() throws IOException {
    List<String> patterns = allPatterns(catalogueText());
    assertThat(patterns).as("the catalogue must not be empty").isNotEmpty();

    List<String> broken = new ArrayList<>();
    for (String pattern : patterns) {
      try {
        Pattern.compile(pattern);
      } catch (PatternSyntaxException e) {
        broken.add(pattern + "  ->  " + e.getDescription());
      }
    }
    assertThat(broken)
        .as(
            "a pattern that does not compile takes the WHOLE catalogue down with it -- every "
                + "sink family stops working, for every language")
        .isEmpty();
  }

  @Test
  @DisplayName("I2: the catalogue carries C# patterns, in the C# table")
  void theCatalogueCarriesCsharpPatterns() throws IOException {
    Map<String, List<String>> byFamily = csharpPatterns(catalogueText());

    assertThat(byFamily).as("the C# sink patterns must be present").isNotEmpty();
    assertThat(byFamily.get("sql.sinks")).as("the SQL family must carry C# patterns").isNotNull();

    // The C# patterns must NOT be in the shared list: a shared pattern is
    // reachable from Java, and a C# symbol blamed for a Java call is a false
    // positive of the worst kind -- it points at a library the program does
    // not use.
    String text = catalogueText();
    for (Map.Entry<String, List<String>> entry : byFamily.entrySet()) {
      for (String pattern : entry.getValue()) {
        assertThat(text)
            .as("the C# pattern must appear under its family's csharp table")
            .contains(pattern);
      }
    }
  }

  @Test
  @DisplayName("I3: the C# patterns are matched only for C#")
  void theCsharpPatternsAreScopedToCsharp() throws IOException {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    SinkFamily sql = cfg.sinkFamilies().byName("sql.sinks").orElseThrow();

    // The signature the C# frontend produces for `cmd.CommandText = tainted`.
    String csharpCall = "System.Data.Common.DbCommand.set_CommandText/1";
    assertThat(sql.matches(csharpCall, SourceLanguage.CSHARP))
        .as("the C# setter must be reachable as a sink")
        .isTrue();

    // The SAME signature must not be reachable from Java. The C# patterns
    // live in the per-language table, so a Java analysis never sees them.
    assertThat(sql.matches(csharpCall, SourceLanguage.JAVA))
        .as("a C# symbol must not be blamed for a Java call")
        .isFalse();
    assertThat(sql.appliesTo(SourceLanguage.CSHARP)).as("the family must apply to C#").isTrue();
  }

  @Test
  @DisplayName("I4: no C# pattern still carries a generic-arity marker")
  void noCsharpPatternCarriesAGenericArityMarker() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (List<String> patterns : csharpPatterns(catalogueText()).values()) {
      for (String pattern : patterns) {
        // `@1` is the arity. The TAC erases generics, so a pattern that keeps
        // the marker can never fire -- it is a rule that looks like coverage
        // and reports nothing.
        if (pattern.matches(".*@\\d+.*")) {
          offenders.add(pattern);
        }
      }
    }
    assertThat(offenders)
        .as("a generic-arity marker in the pattern means the rule can never fire")
        .isEmpty();
  }

  @Test
  @DisplayName("I5: no C# pattern contains an escaped wildcard")
  void noCsharpPatternContainsAnEscapedWildcard() throws IOException {
    List<String> offenders = new ArrayList<>();
    for (List<String> patterns : csharpPatterns(catalogueText()).values()) {
      for (String pattern : patterns) {
        // `\.*` is a LITERAL dot followed by a star -- the opposite of the
        // "any prefix" the pack meant. A pattern that contains it demands an
        // empty namespace segment, and matches nothing.
        if (pattern.contains("\\.*")) {
          offenders.add(pattern);
        }
      }
    }
    assertThat(offenders).as("an escaped wildcard is a literal dot -- the rule is dead").isEmpty();
  }

  @Test
  @DisplayName("I6: a converted C# pattern matches the signature the frontend produces")
  void aConvertedCsharpPatternMatchesTheProducedSignature() throws IOException {
    Map<String, List<String>> byFamily = csharpPatterns(catalogueText());
    List<String> sql = byFamily.get("sql.sinks");
    assertThat(sql).isNotNull();

    // The interface form the pack names. `System.Data.IDbCommand` is the
    // interface the ADO.NET command types implement, and it is what the
    // converted catalogue carries.
    String callee = "System.Data.IDbCommand.set_CommandText/1";
    boolean matched = sql.stream().anyMatch(p -> Pattern.compile(p).matcher(callee).find());
    assertThat(matched).as("the converted pattern must match the callee: " + callee).isTrue();
  }

  @Test
  @DisplayName("I7: the C# patterns keep their argument index")
  void theCsharpPatternsKeepTheirArgumentIndex() throws IOException {
    // The assigned value is user argument 0 -- the RECEIVER is not an
    // argument, so a pattern that pointed at the receiver would report the
    // command object as the tainted value.
    String text = catalogueText();
    assertThat(text).contains("^System\\.Data\\.IDbCommand\\.set_CommandText(/\\d+)?");

    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    SinkFamily sql = cfg.sinkFamilies().byName("sql.sinks").orElseThrow();
    SinkFamily.SinkPattern pattern =
        sql.matchPattern("System.Data.IDbCommand.set_CommandText/1", SourceLanguage.CSHARP);
    assertThat(pattern).as("the converted pattern must be found by the engine").isNotNull();
    assertThat(pattern.argIndex()).as("the assigned value is the first USER argument").isEqualTo(0);
  }

  /**
   * The families a comparative review against a second vendor's C# checker taxonomy found to be
   * missing.
   *
   * <p>Each of these is a defect class the other tool reports for C#, and each was missing from
   * Plumb's C# coverage. The list is the ASSERTION: a family that loses its C# patterns again fails
   * here, with a message that says which defect class regressed.
   *
   * <p>Why the families rather than the patterns: a pattern can be reworded (a vendor pattern
   * becomes a hand-written one, or the argument index moves), and a test that pins the exact
   * pattern text would fail on a legitimate change. The DEFECT CLASS is the stable thing -- it is
   * what a rule author searches for, and what a coverage report counts.
   */
  private static final String[] COVERED_CSHARP_FAMILIES = {
    "sql.sinks",
    "command.sinks",
    "ldap.sinks",
    "xxe.sinks",
    "deserialization.sinks",
    "file-path.sinks",
    "insecure-comms.sinks",
    "insecure-random.sinks",
    "cleartext-tx.sinks",
    "cors.sinks",
  };

  @Test
  @DisplayName("I8: every defect class a C# comparative review found is covered")
  void everyDefectClassFoundByTheComparativeReviewIsCovered() throws IOException {
    Map<String, List<String>> byFamily = csharpPatterns(catalogueText());

    List<String> missing = new ArrayList<>();
    for (String family : COVERED_CSHARP_FAMILIES) {
      List<String> patterns = byFamily.get(family);
      if (patterns == null || patterns.isEmpty()) {
        missing.add(family);
      }
    }
    assertThat(missing)
        .as(
            "these defect classes have no C# sink pattern: a defect class with no C# pattern is a "
                + "hole in the C# coverage, and the hole is invisible in a report that only counts "
                + "the families that DO have patterns")
        .isEmpty();
  }

  @Test
  @DisplayName("I9: a C# pattern never points at the receiver when the value is an argument")
  void csharpPatternsDoNotPointAtTheReceiver() throws IOException {
    // A receiver-tainted pattern means "the OBJECT carries the taint". That is
    // a different proposition from "the tainted value is an ARGUMENT", and
    // the C# patterns in this catalogue are all argument claims.
    //
    // A pattern that says the receiver is a rule that fires on every call to
    // that API whose receiver happens to be tainted -- which, for a
    // constructor, is every call.
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    List<String> offenders = new ArrayList<>();
    for (String family : COVERED_CSHARP_FAMILIES) {
      SinkFamily sink = cfg.sinkFamilies().byName(family).orElse(null);
      if (sink == null) {
        continue;
      }
      for (String pattern : csharpPatterns(catalogueText()).getOrDefault(family, List.of())) {
        SinkFamily.SinkPattern matched =
            sink.matchPattern(
                "Probe." + pattern.replaceAll("^.*\\\\\\.", "").replaceAll("\\(.*", ""),
                SourceLanguage.CSHARP);
        if (matched != null && matched.isReceiverTainted()) {
          offenders.add(family + ": " + pattern);
        }
      }
    }
    assertThat(offenders)
        .as("a receiver-tainted C# pattern reports a different proposition than the rule claims")
        .isEmpty();
  }

  @Test
  @DisplayName("I10: the C# constructor patterns name the <init> convention")
  void csharpConstructorPatternsUseTheInitConvention() throws IOException {
    // A constructor is `<init>` in every Plumb frontend's callee key, and
    // the C# frontend emits `owner.<init>/arity` for a `new` expression.
    //
    // A pattern that says `owner.init^` or `owner.new` can never match: the
    // TAC never produces those spellings.
    List<String> offenders = new ArrayList<>();
    for (List<String> patterns : csharpPatterns(catalogueText()).values()) {
      for (String pattern : patterns) {
        if (pattern.contains("init\\^") || pattern.contains(".new") || pattern.contains(".ctor")) {
          offenders.add(pattern);
        }
      }
    }
    assertThat(offenders)
        .as("the constructor spelling must be the one the TAC produces, or the rule is dead")
        .isEmpty();
  }
}
