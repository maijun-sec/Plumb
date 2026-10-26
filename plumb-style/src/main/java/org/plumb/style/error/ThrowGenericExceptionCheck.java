/*
 * C3: AST-style CWE-397 (Declaration of Throws
 * for Generic Exception) checker.
 *
 * <p>Fires when a method is declared with
 * {@code throws Exception}, {@code throws Throwable},
 * or {@code throws RuntimeException} instead of a
 * more specific exception type. The Juliet CWE397
 * test cases pin this exact pattern:</p>
 *
 * <pre>{@code
 *   public void bad() throws Exception { ... }  // FLAW
 *   public void good() throws FileNotFoundException { ... }
 * }</pre>
 *
 * <p>This checker is <strong>general-purpose</strong>:
 * it pattern-matches on the throws-clause types of
 * every method declaration in the AST. No
 * Juliet-specific hard-coding.</p>
 *
 * <p>Rationale: a method declared with a generic
 * exception type forces every caller to either
 * catch a too-broad exception (hiding real bugs)
 * or re-declare the same broad type (propagating the
 * problem). The fix is to throw a specific
 * exception (or to declare a {@code throws}
 * subtype that accurately describes what the method
 * can fail with).</p>
 */
package org.plumb.style.error;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-397: Declaration of Throws for Generic Exception.
 *
 * <p>Detects method declarations whose {@code throws}-clause contains {@link Exception}, {@link
 * Throwable}, or {@link RuntimeException} (the three "too generic" exception types).
 */
public final class ThrowGenericExceptionCheck implements AstChecker {

  /**
   * The set of exception class names that are "too generic" for a {@code throws}-clause.
   *
   * <p>{@code Exception} is the canonical CWE-397 pattern. {@code Throwable} is even worse -- it
   * includes checked and unchecked AND {@code Error} (which should never be caught). {@code
   * RuntimeException} is unchecked but still forces the caller to decide between catching a generic
   * runtime error or letting it propagate.
   */
  private static final Set<String> GENERIC_EXCEPTION_TYPES =
      Set.of("Exception", "Throwable", "RuntimeException");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-throw-generic-exception", "THROW_GENERIC_EXCEPTION"),
          "Method declares a generic exception in its throws clause (CWE-397)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-397"),
          "org.plumb.style.error.ThrowGenericExceptionCheck",
          true,
          List.of("dfa-error-generic-throw-exception", "throw-generic-exception"));

  @Override
  public String name() {
    return "throw-generic-exception";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Walk every throws-clause entry and report
    // each that is a "too generic" exception type.
    // We emit one finding per offending throws
    // entry so the AI dedup pass can give an
    // accurate verdict per entry (a method that
    // declares both {@code Exception} and
    // {@code IOException} should report only the
    // {@code Exception} entry, not the
    // {@code IOException}).
    for (ReferenceType rt : n.getThrownExceptions()) {
      Type t = rt;
      String typeSimple = t.asString();
      // Strip generic type parameters — the AI
      // doesn't care about the generics, only the
      // raw exception class name.
      int lt = typeSimple.indexOf('<');
      if (lt >= 0) {
        typeSimple = typeSimple.substring(0, lt);
      }
      // The {@code asString} form sometimes
      // returns a fully-qualified name
      // ({@code java.lang.Exception}) and
      // sometimes a simple name. The simple
      // form is enough for our check.
      int dot = typeSimple.lastIndexOf('.');
      if (dot >= 0) {
        typeSimple = typeSimple.substring(dot + 1);
      }
      if (!GENERIC_EXCEPTION_TYPES.contains(typeSimple)) continue;
      int line =
          rt.getBegin().map(p -> p.line).orElseGet(() -> n.getBegin().map(p -> p.line).orElse(-1));
      int col =
          rt.getBegin()
              .map(p -> p.column)
              .orElseGet(() -> n.getBegin().map(p -> p.column).orElse(-1));
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Method '"
              + n.getNameAsString()
              + "' declares a generic '"
              + typeSimple
              + "' in its throws clause; use a more specific "
              + "exception type instead",
          RuleDef.Severity.LOW,
          typeSimple);
    }
  }
}
