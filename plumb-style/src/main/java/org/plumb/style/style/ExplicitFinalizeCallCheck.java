/*
 * R728-C1: AST check for CWE-586 (Explicit Call
 * to Finalize). Fires when a MethodCallExpr named
 * `finalize` with a non-empty scope appears
 * anywhere in the CompilationUnit.
 *
 * <p>Juliet's CWE-586 corpus uses the exact pattern
 * {@code obj.finalize()} in a {@code finally}
 * block ? explicit invocation breaks the GC
 * contract. The check is generic ? any explicit
 * {@code X.finalize()} call (where X is not
 * literally the literal `finalize` keyword in a
 * constructor) is suspicious.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class ExplicitFinalizeCallCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-explicit-finalize", "EXPLICIT_FINALIZE_2"),
          "Explicit call to finalize() — let the garbage collector "
              + "invoke it instead (CWE-586)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-586"),
          "org.plumb.style.ExplicitFinalizeCallCheck",
          true,
          List.of("ast-style-explicit-finalize-call", "explicit-finalize-qualified-call"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "explicit-finalize-qualified-call";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"finalize".equals(n.getNameAsString())) return;
    // `SomeExpr.finalize()` — a finalize() call must
    // have a non-empty scope to be explicit.
    // `finalize()` with no scope is invalid Java
    // anyway.
    if (n.getScope().isEmpty()) return;
    // Skip calls inside a constructor with
    // literal `this` — `this.finalize()` inside
    // a constructor is a typical Object class
    // forwarding pattern. Juliet doesn't test it.
    n.getScope()
        .ifPresent(
            scope -> {
              String s = scope.toString();
              if ("this".equals(s) || "super".equals(s)) return;
            });
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|" + n;
    if (reported.add(key)) {
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Explicit call to finalize() — let the GC" + " invoke it instead (CWE-586)",
          RuleDef.Severity.MEDIUM,
          "obj.finalize()");
    }
  }
}
