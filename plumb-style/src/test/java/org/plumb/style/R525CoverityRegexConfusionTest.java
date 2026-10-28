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
package org.plumb.style;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.quality.RegexConfusionCheck;

/**
 * Coverity <code>REGEX_CONFUSION</code> : covers replaceAll, replaceFirst and split with a string
 * literal that contains a regex metacharacter, plus the negative cases (literal-only,
 * already-escaped).
 */
class R525CoverityRegexConfusionTest {

  @BeforeAll
  static void setupParser() {
    StaticJavaParser.getParserConfiguration()
        .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
  }

  private List<Finding> run(String src) {
    ParsedSource ps = new JavaSourceParser().parseFromString(Path.of("N.java"), src);
    StyleContext ctx = new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
    new AstDispatcher().register(new RegexConfusionCheck()).run(ctx);
    return ctx.findings();
  }

  @Test
  void firesOnReplaceFirstWithDot() {
    // Coverity's canonical example: the period is a metachar.
    String src =
        "class N { String m(String s) {" + " return s.replaceFirst(\".xml\", \"\");" + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: \".xml\" treated as regex fires").isNotEmpty();
  }

  @Test
  void firesOnReplaceAllWithStar() {
    String src = "class N { String m(String s) {" + " return s.replaceAll(\"a*b\", \"x\");" + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: \"a*b\" with star fires").isNotEmpty();
  }

  @Test
  void firesOnSplitWithBracket() {
    String src = "class N { String[] m(String s) {" + " return s.split(\"[abc]\");" + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: \"[abc]\" char class fires").isNotEmpty();
  }

  @Test
  void noFireOnLiteralOnly() {
    // Plain string with no metachars is not a defect.
    String src =
        "class N { String m(String s) {" + " return s.replaceFirst(\"xml\", \"\");" + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: pure literal does not fire").isEmpty();
  }

  @Test
  void noFireOnEscapedMetachar() {
    // Build the regex arg with a Java string concatenation so the
    // Java source the parser sees is unambiguous: the first
    // string is a backslash, the second is ".xml"; concatenation
    // produces the 5-char regex arg "\.xml" where the dot is
    // escaped and matches a literal period.
    String src =
        "class N { String m(String s) {"
            + " String esc = \"\\\\\";"
            + " String pat = esc + \".xml\";"
            + " return s.replaceFirst(pat, \"\");"
            + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: escaped metachar is fine").isEmpty();
  }

  @Test
  void noFireOnUnrelatedMethod() {
    // replace on a non-String receiver: should not fire.
    String src =
        "class N { String m(StringBuilder sb) {"
            + " return sb.toString().replace('a', 'b');"
            + "} }";
    assertThat(run(src)).as("REGEX_CONFUSION: char replace does not fire").isEmpty();
  }
}
