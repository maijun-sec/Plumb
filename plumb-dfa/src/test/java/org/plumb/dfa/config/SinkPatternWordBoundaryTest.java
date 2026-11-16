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
 * A catalogue pattern that names one method, and the ones that used to name several.
 *
 * <p><b>The defect this pins.</b> The catalogue's patterns are matched as substrings, so a pattern
 * for {@code Exec} also claims {@code ExecContext}. The engine takes the UNION of the {@code
 * arg_index} of every pattern of the family that matches a callee, so the shorter pattern's
 * position lands on the longer method as well -- and the longer method's own entry says something
 * different about where its text is. A word boundary says "this method and no other", which is what
 * the entry meant.
 *
 * <p><b>What each test proves.</b> The positions are the ones the catalogue names now: the text of
 * a {@code Context} call is its second argument, not its first, and the address of a {@code
 * WithContext} call is its third, not its second. The last two say that the API the broad pattern
 * used to swallow is claimed by the family that owns it, and by no other.
 *
 * <p><b>Why the families are named here and not in a gate.</b> The position gate asks whether a
 * position can be supplied; it cannot ask whether a pattern claims a method it does not name,
 * because that question needs the API's own method list, which for a third-party class is not on
 * this machine.
 */
@DisplayName("Catalogue: a word boundary names one method")
class SinkPatternWordBoundaryTest {

  @Test
  @DisplayName("the text of a context call is its second argument")
  void theTextOfAContextCallIsItsSecondArgument() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .argIndexesFor("database/sql.DB.ExecContext/2", SourceLanguage.GO))
        .as("ExecContext's first argument is the context, its second is the text")
        .containsExactly(1);

    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .argIndexesFor("database/sql.DB.Exec/2", SourceLanguage.GO))
        .as("and Exec's first argument is the text")
        .containsExactly(0);
  }

  @Test
  @DisplayName("the url of a request is its second argument, and of a context request its third")
  void theUrlOfARequestIsItsSecondArgument() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .argIndexesFor("net/http.NewRequestWithContext/4", SourceLanguage.GO))
        .as("NewRequestWithContext's url is its third argument")
        .containsExactly(2);

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .argIndexesFor("net/http.NewRequest/3", SourceLanguage.GO))
        .as("and NewRequest's url is its second")
        .containsExactly(1);
  }

  @Test
  @DisplayName("the address of a dialer call is its second argument")
  void theAddressOfADialerCallIsItsSecondArgument() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .argIndexesFor("crypto/tls.DialWithDialer/4", SourceLanguage.GO))
        .as("DialWithDialer's address is its third argument, not its second")
        .containsExactly(2);

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .argIndexesFor("crypto/tls.Dial/3", SourceLanguage.GO))
        .as("and Dial's address is its second")
        .containsExactly(1);
  }

  @Test
  @DisplayName("the format of a formatted call is the receiver")
  void theFormatOfAFormattedCallIsTheReceiver() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("dos.sinks")
                .orElseThrow()
                .argIndexesFor("java.lang.String.formatted/1"))
        .as("a formatted call takes its format from the string it is called on")
        .containsExactly(SinkFamily.RECEIVER_ARG_INDEX);

    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .argIndexesFor("java.lang.String.formatted/1"))
        .as("and the family that owns the format string still claims it")
        .containsExactly(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  @DisplayName("a grouped entry names its group and not the longer methods")
  void aGroupedEntryNamesItsGroupAndNotTheLongerMethods() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    // The entry names a GROUP of methods -- Exec, Prepare, Query, QueryRow --
    // so without a boundary it also claimed ExecContext and the like, whose
    // first argument is the context. The context is not the text of a query.
    assertThat(
            config
                .sinkFamilies()
                .byName("database-access.sinks")
                .orElseThrow()
                .argIndexesFor("database/sql.DB.ExecContext/2", SourceLanguage.GO))
        .as("the context is not the query, and the query is not the context")
        .doesNotContain(0);

    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .argIndexesFor("database/sql.DB.ExecContext/2", SourceLanguage.GO))
        .as("and the same holds for the family that owns the sql text")
        .doesNotContain(0);
  }

  @Test
  @DisplayName("a draw that takes no argument names the generator alone")
  void aDrawThatTakesNoArgumentNamesTheGeneratorAlone() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    // nextLong() and nextGaussian() take no argument at all, so position 0
    // was a position no call could supply -- and for nextGaussian it was the
    // only position the entry had, so the rule could never fire.
    assertThat(
            config
                .sinkFamilies()
                .byName("random.sinks")
                .orElseThrow()
                .argIndexesFor("java.util.Random.nextLong/0"))
        .as("what is weak is the generator, not the number it produced")
        .containsExactly(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  @DisplayName("the gaussian draws from the generator, so it names the receiver")
  void theGaussianDrawsFromTheGenerator() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("random.sinks")
                .orElseThrow()
                .argIndexesFor("java.util.Random.nextGaussian/0"))
        .as("what is weak is the generator, not the number it produced")
        .containsExactly(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  @DisplayName("a url that only sets a stream handler is not a privacy leak")
  void aUrlThatOnlySetsAStreamHandlerIsNotAPrivacyLeak() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("privacy.sinks")
                .orElseThrow()
                .argIndexesFor("java.net.URL.setURLStreamHandlerFactory/1"))
        .as("java.net.URL has no public method called \"set\"")
        .isEmpty();

    assertThat(
            config
                .sinkFamilies()
                .byName("external.config.sinks")
                .orElseThrow()
                .argIndexesFor("java.net.URL.setURLStreamHandlerFactory/1"))
        .as("and the call that does exist is claimed by the family that owns it")
        .isNotEmpty();
  }
}
