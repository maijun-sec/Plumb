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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.SourceLanguage;

/**
 * A C# run does not LOAD the Java rules -- and "load" means the checker is never built.
 *
 * <h2>Why the construction is the thing that matters</h2>
 *
 * <p>The obvious implementation of "do not load the Java rules" is to narrow the CONFIGURATION: the
 * registry of the checkers stops holding them. That is necessary and it is not sufficient, because
 * most checkers are not registered through the configuration at all -- they are wired by a boolean
 * flag and constructed directly. A checker that is constructed walks every instruction to decide it
 * does not apply, and that is exactly the cost the request asked not to pay.
 *
 * <p>So this class asserts the CONSTRUCTION, not the registry: the JVM-only checkers must be absent
 * from the list the engine actually runs.
 */
class CheckerLanguageScopeTest {

  /** A runner that analyses C# and nothing else. */
  private static ScanRunner csharpRunner() {
    ScanConfig cfg = new ScanConfig();
    cfg.languages = new java.util.LinkedHashSet<>(Set.of(SourceLanguage.CSHARP));
    return new ScanRunner(cfg);
  }

  /** A runner that analyses everything. */
  private static ScanRunner unfilteredRunner() {
    return new ScanRunner(new ScanConfig());
  }

  /** The classes of the checkers a runner would build. */
  private static Set<String> classesOf(ScanRunner runner) {
    return runner.buildDfaCheckers().stream()
        .map(c -> c.getClass().getName())
        .collect(java.util.stream.Collectors.toSet());
  }

  @Test
  @DisplayName("a C# run does not build the checkers that are JVM-shaped")
  void csharpRunBuildsNoJvmChecker() {
    Set<String> built = classesOf(csharpRunner());

    assertThat(built)
        .as(
            "a checker that hardcodes a JVM symbol, parses a Java AST, or is tied to a JVM"
                + " framework")
        .doesNotContain(
            // a JavaParser AST checker: it needs a Java AST to walk
            "org.plumb.style.security.UncheckedReturnCheck",
            // hardcodes System.getenv / System.getProperty
            "org.plumb.dfa.checkers.taint.SensitiveDataLeakChecker",
            // its family's patterns are java.io.File and nothing else
            "org.plumb.dfa.checkers.taint.FilePermissionChecker",
            // its family is Velocity-only
            "org.plumb.dfa.checkers.taint.ServerSideTemplateInjectionChecker",
            // JPA / Hibernate
            "org.plumb.dfa.checkers.controlflow.OrmLoadNullCheckDfaChecker",
            "org.plumb.dfa.checkers.controlflow.OrmUnnecessaryGetDfaChecker",
            "org.plumb.dfa.checkers.controlflow.OrmLostUpdateDfaChecker");
  }

  @Test
  @DisplayName("a C# run builds the language-neutral checkers")
  void csharpRunBuildsTheNeutralCheckers() {
    Set<String> built = classesOf(csharpRunner());

    assertThat(built)
        .as("the language-neutral checkers are the C# rules")
        .contains(
            "org.plumb.dfa.checkers.taint.DivByZeroChecker",
            "org.plumb.dfa.checkers.taint.IntOverflowChecker",
            "org.plumb.dfa.checkers.taint.NullDerefChecker",
            "org.plumb.dfa.checkers.taint.ResourceLeakChecker",
            "org.plumb.dfa.checkers.boundary.OverflowConstraintChecker",
            "org.plumb.dfa.checkers.boundary.UnderflowConstraintChecker",
            "org.plumb.dfa.checkers.boundary.DivZeroConstraintChecker",
            "org.plumb.dfa.checkers.boundary.ArrayBoundsConstraintChecker");
  }

  @Test
  @DisplayName("a Java run keeps every checker")
  void javaRunKeepsEveryChecker() {
    ScanConfig cfg = new ScanConfig();
    cfg.languages = new java.util.LinkedHashSet<>(Set.of(SourceLanguage.JAVA));
    Set<String> built = classesOf(new ScanRunner(cfg));

    assertThat(built)
        .as("a Java run must not lose the Java rules")
        .contains(
            "org.plumb.dfa.checkers.controlflow.OrmLoadNullCheckDfaChecker",
            "org.plumb.dfa.checkers.controlflow.OrmUnnecessaryGetDfaChecker",
            "org.plumb.dfa.checkers.taint.SensitiveDataLeakChecker",
            "org.plumb.dfa.checkers.taint.FilePermissionChecker",
            "org.plumb.dfa.checkers.taint.DivByZeroChecker");
  }

  @Test
  @DisplayName("a run with no language selected builds every checker")
  void anUnselectedRunBuildsEverything() {
    List<org.plumb.dfa.engine.DfaChecker> all = unfilteredRunner().buildDfaCheckers();

    assertThat(all).isNotEmpty();
    assertThat(
            all.stream()
                .map(c -> c.getClass().getName())
                .collect(java.util.stream.Collectors.toSet()))
        .as("the unfiltered run must not be narrower than the filtered one")
        .hasSizeGreaterThan(classesOf(csharpRunner()).size());
  }

  @Test
  @DisplayName("a C# run builds FEWER checkers than an unfiltered run")
  void theFilterActuallyRemovesSomething() {
    int all = unfilteredRunner().buildDfaCheckers().size();
    int csharp = csharpRunner().buildDfaCheckers().size();

    assertThat(csharp)
        .as("if the filter removes nothing, the JVM rules are still loaded in a C# run")
        .isLessThan(all);
  }
}
