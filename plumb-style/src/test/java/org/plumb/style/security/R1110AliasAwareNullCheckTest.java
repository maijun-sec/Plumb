package org.plumb.style.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleRegistry;
import org.plumb.style.AstDispatcher;
import org.plumb.style.StyleContext;

/**
 * R1110: a null check must follow the VALUE, not the variable name.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code NullDerefFromReturnAstCheck} decides a dereference is guarded by scanning the statements
 * between the assignment and the use for an {@code if (x == null)} that names <em>the same
 * variable</em>. That is a name check wearing a value check's clothes, and it produces a false
 * positive on the single most common Java shape for carrying a value around:
 *
 * <pre>
 *   StringBuilder a = makePayload(input);
 *   StringBuilder b = a;            // or: b = store.get("k")
 *   if (a == null) return 0;
 *   return b.length();              // safe: b and a are one object
 * </pre>
 *
 * <p>The guard is on {@code a}; the dereference is on {@code b}. They are the same object, so the
 * dereference cannot fault. The rule reports anyway, because it never asks whether the two names
 * denote the same reference.
 *
 * <h2>Why this is a false positive and not a style preference</h2>
 *
 * <p>The DFA layer already reaches the opposite conclusion — {@code NullDerefChecker} consults the
 * alias/copy-equivalence analysis and stays silent on exactly this code. Two layers of the same
 * product disagreeing about one construct is what makes such a report worthless to a user: there is
 * no action to take other than deleting a correct guard.
 *
 * <h2>The fix's scope</h2>
 *
 * <p>Only syntactically explicit reference copies are followed: {@code b = a}. The rule reads that
 * straight off the AST, so the change can therefore only ever <em>remove</em> reports.
 *
 * <p>A container round trip ({@code b = store.get("k")}) is deliberately NOT followed, because
 * whether the retrieved value is the guarded one depends on what the map holds — that is a heap
 * question, not a syntactic one, and answering it by pattern-matching the expression would suppress
 * findings whenever the map happened to hold something else. The corresponding test is written to
 * assert that this case still reports, with the gap named, so the limitation stays visible instead
 * of being quietly asserted into existence.
 */
class R1110AliasAwareNullCheckTest {

  private static StyleContext ctxFor(String src) {
    JavaParser parser = new JavaParser();
    ParseResult<CompilationUnit> result = parser.parse(src);
    assertThat(result.isSuccessful()).as("parse").isTrue();
    ParsedSource ps =
        new ParsedSource(
            java.nio.file.Paths.get("Test.java"),
            "Test.java",
            result.getResult().get(),
            new ArrayList<>());
    return new StyleContext(ps, new RuleRegistry().withBuiltinDefaults(true));
  }

  private static List<Finding> run(String src) {
    StyleContext ctx = ctxFor(src);
    try {
      new AstDispatcher()
          .register(NullDerefFromReturnAstCheck.class.getDeclaredConstructor().newInstance())
          .run(ctx);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return ctx.findings();
  }

  /**
   * A member file whose helpers mirror the shape the rule actually fires on: a reference-typed
   * result assigned from a call, then dereferenced through a scoped method call.
   *
   * <p>Written against {@code StringBuilder} rather than {@code Object} because that is what the
   * rule reacts to — an earlier draft used {@code Object} with a {@code len(Object)} helper and every
   * case scored zero findings, which looked like the defect was absent when in fact the fixture had
   * simply never triggered the rule.
   */
  private static String wrap(String body) {
    return "import java.util.*;\n"
        + "class T {\n"
        + "  StringBuilder make(String s) { return new StringBuilder(s); }\n"
        + body
        + "}\n";
  }

  // ── the transfer case ─────────────────────────────────────────────────

  @Test
  void guardOnOneNameCoversItsCopy() {
    String src =
        wrap(
            "  void ok(String input) {\n"
                + "    StringBuilder a = make(input);\n"
                + "    StringBuilder b = a;\n"
                + "    if (a == null) { return; }\n"
                + "    b.length();\n"
                + "  }\n");
    assertThat(run(src))
        .as("b is a copy of the guarded a, so b cannot be null here")
        .isEmpty();
  }

  /**
   * The container round trip is <em>not</em> covered, and the test says so rather than pretending.
   *
   * <p>Asserted as still-reporting on purpose. The syntactic copy-chain fix can only follow a bare
   * reference copy, because that is what the AST states outright; {@code b = store.get("k")} is a
   * container lookup whose result depends on what was put in the map, which needs the heap model
   * rather than a name walk.
   *
   * <p>Flipping this to {@code isEmpty()} would document a capability the rule does not have, and the
   * assertion would then keep passing after someone removed the reporting path entirely — a test
 * that cannot tell "handled correctly" from "stopped working" is worse than no test. Keeping it
 * red-named is what forces the gap to stay visible.
   */
  @Test
  void aContainerRoundTripIsStillReportedAndThatGapIsDeliberate() {
    String src =
        wrap(
            "  void gap(String input) {\n"
                + "    Map<String, StringBuilder> store = new HashMap<>();\n"
                + "    StringBuilder a = make(input);\n"
                + "    store.put(\"k\", a);\n"
                + "    StringBuilder b = store.get(\"k\");\n"
                + "    if (a == null) { return; }\n"
                + "    b.length();\n"
                + "  }\n");
    assertThat(run(src))
        .as(
            "KNOWN GAP: a container round trip needs a heap model, not a copy-chain walk. Recorded as"
                + " still-reporting so the gap stays visible instead of being asserted away.")
        .hasSize(1);
  }

  // ── controls: the transfer must not over-reach ───────────────────────

  /**
   * Two independent results are not the same object, so guarding one says nothing about the other.
   *
   * <p>This is the control that keeps the fix from degenerating into "any guarded name suppresses
   * everything": if the implementation followed names rather than values, this would be silently
   * suppressed and a true positive would be lost.
   */
  @Test
  void guardingADifferentObjectDoesNotSuppress() {
    String src =
        wrap(
            "  void bad(String input) {\n"
                + "    StringBuilder a = make(input);\n"
                + "    StringBuilder b = make(input);\n"
                + "    if (a == null) { return; }\n"
                + "    b.length();\n"
                + "  }\n");
    assertThat(run(src))
        .as("b is a different allocation; the guard on a protects nothing here")
        .hasSize(1);
  }

  /** An unguarded value is still reported. */
  @Test
  void unguardedCopyIsStillReported() {
    String src =
        wrap(
            "  void bad(String input) {\n"
                + "    StringBuilder a = make(input);\n"
                + "    StringBuilder b = a;\n"
                + "    b.length();\n"
                + "  }\n");
    assertThat(run(src)).as("no guard anywhere on this path").hasSize(1);
  }

  /**
   * The guard must still be located strictly between the assignment and the use.
   *
   * <p>Pins the window: a check <em>before</em> the assignment says nothing about the value that
   * assignment introduces, and treating it as a guard would suppress a genuine finding.
   */
  @Test
  void aGuardBeforeTheAssignmentDoesNotCount() {
    String src =
        wrap(
            "  void bad(String input) {\n"
                + "    StringBuilder other = make(input);\n"
                + "    if (other == null) { return; }\n"
                + "    StringBuilder a = make(input);\n"
                + "    a.length();\n"
                + "  }\n");
    assertThat(run(src))
        .as("the check precedes the assignment, so it cannot constrain the new value")
        .hasSize(1);
  }
}