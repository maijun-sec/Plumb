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
package org.plumb.tac.ir;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;

/**
 * The class-hierarchy tables are populated but not read -- and the javadoc must say so.
 *
 * <p><b>Why this test exists.</b> The IR carries a class hierarchy ({@link
 * TacProgram#classToParent()} and {@link TacProgram#classToChildren()}), filled by the translator
 * and aggregated across compilation units by {@link MergedProgram}. For a long time the javadoc
 * described a "CHA-style {@code CallResolver}" that would "walk the class hierarchy" to resolve a
 * virtual call -- and that class does not exist anywhere in the project.
 *
 * <p>The javadoc was therefore describing an architecture that was never built, and a reader would
 * conclude that a virtual call through an abstract base is resolved by the hierarchy. It is not:
 * the CHA strategy resolves a call by matching the callee's SIMPLE NAME and arity against the
 * program's functions. A category-04 fixture says so in as many words ("This engine has NO
 * transitive class-hierarchy closure, so the hierarchy itself does no work here").
 *
 * <p>The tables are still worth keeping -- the hierarchy is real information the IR already
 * carries, and a hierarchy-aware resolver can consume it without touching the translator. But the
 * javadoc must describe what the code does, not what it might do.
 *
 * <p>This test pins the two halves of that:
 *
 * <ol>
 *   <li>the translator really does populate the tables (so the information is not lost);
 *   <li>the javadoc next to those tables says the tables are currently unread, and names the
 *       strategy that actually resolves a virtual call.
 * </ol>
 */
class ClassHierarchyTableDocumentationTest {

  @TempDir Path tmp;

  private static final String SOURCE =
      """
      package app;

      /** The base. */
      abstract static class Base {
        abstract void run(String input);
      }

      /** A concrete subclass. */
      static final class Impl extends Base {
        @Override
        void run(String input) {
          System.out.println(input);
        }
      }

      /** A second, unrelated hierarchy. */
      static class Other {
        void run(String input) {
          System.out.println(input);
        }
      }
      """;

  private TacProgram translate() throws Exception {
    Path file = tmp.resolve("Hierarchy.java");
    Files.writeString(file, SOURCE);
    ParsedSource ps = new JavaSourceParser().parse(file);
    return new org.plumb.tac.translate.TacTranslator(ps).translate();
  }

  /**
   * The translator populates the hierarchy table.
   *
   * <p>This is the information a future hierarchy-aware resolver would need. If it stopped being
   * recorded, that resolver could not be built from the IR alone.
   */
  @Test
  void theTranslatorRecordsTheClassHierarchy() throws Exception {
    TacProgram program = translate();
    assertThat(program.classToParent())
        .as("Impl extends Base must be recorded")
        .containsEntry("Impl", "Base");
  }

  /**
   * The reverse table is maintained in lock-step.
   *
   * <p>Both tables exist so a resolver can ask the question in either direction: "who is this
   * class's parent?" and "which classes extend this one?".
   */
  @Test
  void theReverseHierarchyTableIsMaintainedInLockStep() throws Exception {
    TacProgram program = translate();
    assertThat(program.classToChildren())
        .as("Base must list Impl as a child")
        .containsEntry("Base", List.of("Impl"));
  }

  /**
   * The javadoc next to the tables must not claim a resolver that does not exist.
   *
   * <p>This is the assertion that would have caught the original defect. The javadoc named {@code
   * org.plumb.dfa.cg.CallResolver} -- a class that does not exist -- and described it as the
   * consumer of these tables. It now states that no analysis code reads them, and names the
   * strategy that really resolves a virtual call.
   */
  @Test
  void theHierarchyTablesDoNotClaimANonExistentConsumer() throws Exception {
    String javadoc = readJavadocOf(TacProgram.class);
    assertThat(javadoc)
        .as("the javadoc must not name the phantom CallResolver")
        .doesNotContain("org.plumb.dfa.cg.CallResolver");
    assertThat(javadoc)
        .as("the javadoc must say the tables are not read today")
        .contains("not read by the current analysis");
    assertThat(javadoc)
        .as("the javadoc must name the strategy that actually resolves a virtual call")
        .contains("ChaCallGraphStrategy");
  }

  /** The same discipline applies to the function's declaring-type field and to the merge. */
  @Test
  void theOtherHierarchyClaimsDoNotNameThePhantomResolver() throws Exception {
    assertThat(readJavadocOf(TacFunction.class))
        .as("TacFunction must not name the phantom CallResolver")
        .doesNotContain("org.plumb.dfa.cg.CallResolver");
    assertThat(readJavadocOf(MergedProgram.class))
        .as("MergedProgram must not name the phantom CallResolver")
        .doesNotContain("org.plumb.dfa.cg.CallResolver");
  }

  /**
   * The class javadoc of a source file, as text.
   *
   * <p>Read from the source file rather than from the compiled class, because the point is what a
   * reader of the SOURCE sees.
   */
  private static String readJavadocOf(Class<?> type) throws Exception {
    // The code source is a URL like "file:/D:/path/target/classes/". Path.of(URI) is the
    // reliable way to turn it into a path -- building it from the string form leaves the
    // leading "/D:" that Path.of rejects on Windows.
    java.net.URI location = type.getProtectionDomain().getCodeSource().getLocation().toURI();
    Path classesDir = Path.of(location);
    Path source =
        classesDir
            .resolve("..")
            .resolve("..")
            .resolve("src/main/java")
            .resolve(type.getName().replace('.', '/') + ".java")
            .normalize();
    assertThat(Files.exists(source))
        .as("the source of %s must be next to the compiled classes", type.getSimpleName())
        .isTrue();
    return Files.readString(source, java.nio.charset.StandardCharsets.UTF_8);
  }
}
