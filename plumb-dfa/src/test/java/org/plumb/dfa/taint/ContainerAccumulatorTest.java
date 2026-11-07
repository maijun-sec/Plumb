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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A container's taint belongs to the container, not to the call that wrote into it.
 *
 * <p><b>What was broken.</b> The catalogue recognised a builder by looking for the fragment {@code
 * ".stringbuilder."} in the callee signature, and a container by looking for {@code
 * ".java.util.list."} or {@code ".java.util.map."}. The first fragment occurs in {@code
 * java.lang.StringBuilder.append/1}, because a dot precedes {@code java} inside {@code ".lang."}.
 * The second does not occur in {@code java.util.List.add/1}, because nothing precedes {@code java}
 * there: the type sits directly under a root package. So the builder's rule fired and the
 * container's rule never did, and the difference was invisible because the builder is the shape
 * people test and the container is not.
 *
 * <p>The consequence was a silent false negative with a very specific shape: a list that collects
 * attacker input and reads it back looked clean.
 *
 * <pre>
 *   List&lt;String&gt; parts = new ArrayList&lt;&gt;();
 *   parts.add(userInput);                    // the taint enters the container
 *   String sql = "... '" + parts.get(0) + "'"; // and comes back out, clean
 * </pre>
 *
 * <p><b>Why the rule now extracts the owner.</b> A substring test whose answer depends on how deep
 * the type's package happens to be is not a rule. The owner is extracted from the signature and its
 * last segment is compared, so the answer depends on the type's NAME and not on its package's
 * depth.
 */
@DisplayName("Taint: a container carries its elements' taint in both directions")
class ContainerAccumulatorTest {

  @TempDir Path tmp;

  /**
   * The catalogue's answer for a container's two directions.
   *
   * @param name the file's name
   * @return the file
   */
  private Path write(String name, String body) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, body);
    return file;
  }

  private List<Finding> scan(Path file) throws Exception {
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  @Test
  @DisplayName("the mutator direction: a type directly under a root package is matched")
  void theMutatorDirectionATypeDirectlyUnderARootPackageIsMatched() {
    // The signature of java.util.List.add/1 has no dot before "java", so the
    // old ".java.util.list." fragment did not occur in it.
    assertThat(AccumulatorCatalog.isMutator("java.util.List.add/1"))
        .as("a container's add writes into the container, so it is a mutator")
        .isTrue();
    assertThat(AccumulatorCatalog.isMutator("java.util.Map.put/2"))
        .as("a map's put writes into the map, so it is a mutator")
        .isTrue();
    assertThat(AccumulatorCatalog.isMutator("java.util.Set.add/1"))
        .as("a set's add writes into the set, so it is a mutator")
        .isTrue();
  }

  @Test
  @DisplayName("the read direction: a container's get reads the container's state")
  void theReadDirectionAContainersGetReadsTheContainersState() {
    assertThat(AccumulatorCatalog.isAccumulatorRead("java.util.List.get/1"))
        .as("a container's get hands back what the container holds")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("java.util.Map.get/1"))
        .as("a map's get hands back what the map holds")
        .isTrue();
  }

  @Test
  @DisplayName("the collection family is matched across languages")
  void theCollectionFamilyIsMatchedAcrossLanguages() {
    // The owner is compared by its last segment, so one rule covers the
    // container types of every language: the Java ones, the Kotlin ones
    // (kotlin.collections.MutableList), and the C# ones (System.Collections
    // .Generic.List). A rule that named each package separately would need
    // an entry per spelling, and an entry per spelling is an entry per
    // spelling someone forgets to add.
    assertThat(AccumulatorCatalog.isMutator("java.util.ArrayList.add/1"))
        .as("a list's implementation is a list")
        .isTrue();
    assertThat(AccumulatorCatalog.isMutator("kotlin.collections.MutableList.add/1"))
        .as("the Kotlin collection family is matched too")
        .isTrue();
    assertThat(AccumulatorCatalog.isMutator("System.Collections.Generic.List.Add/1"))
        .as("the C# collection family is matched too")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("kotlin.collections.MutableList.get/1"))
        .as("and so is the read direction")
        .isTrue();
  }

  @Test
  @DisplayName("the builder's rule still holds after the owner's extraction")
  void theBuildersRuleStillHoldsAfterTheOwnersExtraction() {
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.StringBuilder.append/1"))
        .isTrue();
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.StringBuffer.append/1"))
        .isTrue();
    assertThat(AccumulatorCatalog.isMutator("java.lang.StringBuilder.append/1"))
        .as("the builder was the shape that worked before, and it must keep working")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("java.lang.StringBuilder.toString/0"))
        .as("the builder's read was working before, and it must keep working")
        .isTrue();
  }

  @Test
  @DisplayName("an immutable String's concat is not a builder")
  void anImmutableStringsConcatIsNotABuilder() {
    assertThat(AccumulatorCatalog.isStringBuilderAppend("java.lang.String.concat/1"))
        .as("a String's concat returns a new value; it does not mutate a builder")
        .isFalse();
  }

  @Test
  @DisplayName("end to end: a list that collects attacker input and reads it back reaches the sink")
  void endToEndAListThatCollectsAttackerInputAndReadsItBackReachesTheSink() throws Exception {
    Path file =
        write(
            "ListAcc.java",
            """
            class T {
              void go(String userInput) throws Exception {
                java.util.List<String> parts = new java.util.ArrayList<>();
                parts.add(userInput);
                String sql = "SELECT * FROM t WHERE c = '" + parts.get(0) + "'";
                java.sql.Statement st = null;
                st.executeQuery(sql);
              }
            }
            """);

    assertThat(scan(file))
        .as("the taint enters the list at the add and comes back out at the get; the sink sees it")
        .isNotEmpty();
  }

  @Test
  @DisplayName("end to end: a map that collects attacker input and reads it back reaches the sink")
  void endToEndAMapThatCollectsAttackerInputAndReadsItBackReachesTheSink() throws Exception {
    Path file =
        write(
            "MapAcc.java",
            """
            class T {
              void go(String userInput) throws Exception {
                java.util.Map<String, String> m = new java.util.HashMap<>();
                m.put("k", userInput);
                String sql = "SELECT * FROM t WHERE c = '" + m.get("k") + "'";
                java.sql.Statement st = null;
                st.executeQuery(sql);
              }
            }
            """);

    assertThat(scan(file))
        .as("the taint enters the map at the put and comes back out at the get")
        .isNotEmpty();
  }

  @Test
  @DisplayName("end to end: a clean list stays clean")
  void endToEndACleanListStaysClean() throws Exception {
    // The directions only ever ADD taint. A container that never receives
    // anything tainted must not manufacture a finding.
    Path file =
        write(
            "CleanList.java",
            """
            class T {
              void go() throws Exception {
                java.util.List<String> parts = new java.util.ArrayList<>();
                parts.add("constant");
                String sql = "SELECT * FROM t WHERE c = '" + parts.get(0) + "'";
                java.sql.Statement st = null;
                st.executeQuery(sql);
              }
            }
            """);

    assertThat(scan(file)).as("a container with no tainted element produces no finding").isEmpty();
  }
}
