/*
 * C1: AST-style CWE-396 (Catch Generic Exception) checker.
 *
 * <p>Fires when a {@code catch} block declares an overly broad
 * exception type such as {@link Exception} or {@link Throwable}.
 * Catching these swallows all subclasses ? including
 * {@link RuntimeException} and {@link Error} —
 * which makes debugging hard and is usually a sign of
 * incomplete error handling.</p>
 *
 * <p>This is <strong>general-purpose</strong>: the rule targets
 * JDK catch-clause shape, not Juliet-specific constructs.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-396: Catch of Generic Exception. */
public final class OverlyBroadCatchCheck implements AstChecker {

  /**
   * Whitelist of overly broad catch types. We intentionally do <strong>not</strong> flag common
   * Java checked exception types that are already specific enough (IOException,
   * NumberFormatException, etc.) ? those are valid catch targets.
   */
  private static final Set<String> OVERLY_BROAD =
      Set.of("Exception", "Throwable", "RuntimeException", "Error");

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-overly-broad-catch", "OVERLY_BROAD_CATCH"),
          "Overly broad catch clause (catches Exception/Throwable/Error) (CWE-396)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-396"),
          "org.plumb.style.style.OverlyBroadCatchCheck",
          true,
          List.of("dfa-style-catch-generic-exception", "overly-broad-catch"));

  @Override
  public String name() {
    return "overly-broad-catch";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    String caught = caughtTypeName(n);
    if (caught == null) return;
    if (!OVERLY_BROAD.contains(caught)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "Overly broad catch of `"
            + caught
            + "` (CWE-396); "
            + "use a more specific exception type so unexpected errors are not silently swallowed",
        RuleDef.Severity.LOW,
        caught);
  }

  /**
   * Extract the simple class name from the catch parameter's declared type. Strip any array or
   * generic nesting so a {@code catch (Exception[] ...)} still resolves to {@code "Exception"}.
   */
  private static String caughtTypeName(CatchClause n) {
    if (n.getParameter() == null) return null;
    var type = n.getParameter().getType();
    if (type instanceof ClassOrInterfaceType coit) {
      return coit.getNameAsString();
    }
    if (type instanceof ReferenceType rt) {
      // Multi-catch (Java 7+): `catch (IOException | SQLException ex)`
      // is represented as a UnionType, not ReferenceType.
      return rt.toString();
    }
    return type.toString();
  }
}
