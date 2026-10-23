package org.plumb.style.style;

import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Provably-infinite loop detector (CWE-835). Two strategies, both pure AST:
 *
 * <ol>
 *   <li><b>Constant-true condition</b>: {@code while (true)}, {@code for (; ; )}, {@code do { ... }
 *       while (true)}. Fires when the body has no {@code break} / {@code return} / {@code throw}.
 *   <li><b>Value-bounded modulo proof</b>: the loop condition is {@code var >= 0} (permanently true
 *       when {@code var} stays in {@code [0, N-1]}) AND the body (or for-step clause) contains
 *       {@code var = (var + k) % N;} (a modulo that textually keeps the induction variable in
 *       {@code [0, N-1]}).
 * </ol>
 *
 * <p>The original implementation lived in {@code plumb-dfa} as {@code
 * ClassThreadChecker$InfiniteLoopChecker} (R774, AST-on-CompilationUnit via re-parsed source).
 * R787b migrates it here so the rule lives where AST rules belong per the cardinal principles (AST
 * → style module, DFA → dfa module, prefer AST when feasible). The dfa-side is replaced with a
 * deprecated stub.
 */
public final class InfiniteLoopCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-infinite-loop", "INFINITE_LOOP"),
          "loop provably never terminates",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-835"),
          "org.plumb.style.InfiniteLoopCheck",
          true,
          List.of("ast-style-infinite-loop-style", "infinite-loop")
      );

  @Override
  public String name() {
    return "infinite-loop";
  }

  @Override
  public void init(StyleContext ctx) {
    // Per-file dedup: clear the instance set so
    // each file's walk starts fresh. Same instance
    // is re-used across files in a single scan, so
    // we reset here.
    // R-pre-P1#6 hardening: was `static final Set<String> SEEN` shared
    // across all instances of this checker class, which would silently
    // drop findings if the dispatcher ever parallelises (next-file's
    // dedup sees prior-file's keys) or if two files are scanned in the
    // same JVM. Per-instance state removes both foot-guns.
    seen().clear();
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    // Strategy 1: constant-true
    if (isConstantTrue(n.getCondition()) && !bodyHasExit(n.getBody())) {
      fireCwe835(ctx, lineOf(n), "while", "while loop with statically-true condition");
      return;
    }
    // Strategy 2: value-bounded with modulo
    checkValueBoundedWhile(n, ctx);
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    // Strategy 1: for (;;)
    boolean emptyInit = n.getInitialization().isEmpty();
    boolean noCompare = n.getCompare().isEmpty();
    boolean emptyUpdate = n.getUpdate().isEmpty();
    if (emptyInit && noCompare && emptyUpdate) {
      if (!bodyHasExit(n.getBody())) {
        fireCwe835(ctx, lineOf(n), "for", "for(;;) loop with no break/return/throw");
      }
      return;
    }
    // Strategy 1: for (init; cond; step) with constant-true cond
    if (n.getCompare().isPresent()
        && isConstantTrue(n.getCompare().get())
        && !bodyHasExit(n.getBody())) {
      fireCwe835(ctx, lineOf(n), "for", "for loop with statically-true condition");
      return;
    }
    // Strategy 2: for (init; var >= 0; step)
    checkValueBoundedFor(n, ctx);
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    // Strategy 1: do {} while (true);
    if (isConstantTrue(n.getCondition()) && !bodyHasExit(n.getBody())) {
      fireCwe835(ctx, lineOf(n), "do-while", "do-while loop with statically-true condition");
      return;
    }
    // Strategy 2: do { body; } while (var >= 0);
    checkValueBoundedDo(n, ctx);
  }

  // ────── Value-bounded (Strategy 2) helpers ──────

  private void checkValueBoundedWhile(WhileStmt n, StyleContext ctx) {
    String var = inductionVarForGeZero(n.getCondition());
    if (var == null) return;
    if (!bodyContainsModulo(n.getBody(), var)) return;
    if (bodyHasExit(n.getBody())) return;
    fireCwe835(
        ctx,
        lineOf(n),
        "while",
        "while ("
            + var
            + " >= 0) is permanently true because "
            + "`"
            + var
            + " = ("
            + var
            + " + k) % N` keeps "
            + var
            + " in [0, N-1].");
  }

  private void checkValueBoundedDo(DoStmt n, StyleContext ctx) {
    String var = inductionVarForGeZero(n.getCondition());
    if (var == null) return;
    if (!bodyContainsModulo(n.getBody(), var)) return;
    if (bodyHasExit(n.getBody())) return;
    fireCwe835(
        ctx,
        lineOf(n),
        "do-while",
        "do { ... } while ("
            + var
            + " >= 0) is permanently "
            + "true because `"
            + var
            + " = ("
            + var
            + " + k) % N` keeps "
            + var
            + " in [0, N-1].");
  }

  private void checkValueBoundedFor(ForStmt n, StyleContext ctx) {
    if (n.getCompare().isEmpty()) return;
    String var = inductionVarForGeZero(n.getCompare().get());
    if (var == null) return;
    // The modulo update can live either in the body OR
    // in the for-step clause (Juliet CWE835__for_01 puts
    // it in the step). Search both.
    boolean inStep = n.getUpdate().stream().anyMatch(expr -> containsModuloUpdate(expr, var));
    boolean inBody = bodyContainsModulo(n.getBody(), var);
    if (!inStep && !inBody) return;
    if (bodyHasExit(n.getBody())) return;
    fireCwe835(
        ctx,
        lineOf(n),
        "for",
        "for (init; "
            + var
            + " >= 0; step) is permanently "
            + "true because `"
            + var
            + " = ("
            + var
            + " + k) % N` keeps "
            + var
            + " in [0, N-1].");
  }

  // ────── Expression-shape predicates ──────

  /**
   * Recognize a constant-true predicate. Handles:
   *
   * <ul>
   *   <li>{@code true} (boolean literal)
   *   <li>{@code x == x} (tautology)
   * </ul>
   */
  private static boolean isConstantTrue(Expression e) {
    if (e == null) return false;
    if (e instanceof BooleanLiteralExpr) {
      return "true".equals(e.toString());
    }
    if (e instanceof BinaryExpr be) {
      if (be.getOperator() == BinaryExpr.Operator.EQUALS) {
        return be.getLeft().toString().equals(be.getRight().toString());
      }
    }
    return false;
  }

  /**
   * Recognize a {@code var >= 0} condition and return the induction variable name. The var name is
   * taken from the LHS as printed; we don't need symbol resolution ? the body's modulo check uses
   * the same textual comparison.
   */
  private static String inductionVarForGeZero(Expression e) {
    if (!(e instanceof BinaryExpr be)) return null;
    if (be.getOperator() != BinaryExpr.Operator.GREATER_EQUALS) {
      return null;
    }
    String rhs = be.getRight().toString().trim();
    if (!"0".equals(rhs)) return null;
    if (!(be.getLeft() instanceof NameExpr ne)) return null;
    return ne.getNameAsString();
  }

  /**
   * Walk the body recursively and return true if any sub-expression is {@code var = (var + K) % N}
   * for the same {@code var}.
   */
  private static boolean bodyContainsModulo(Statement body, String var) {
    if (body == null) return false;
    final boolean[] found = {false};
    VoidVisitorAdapter<Void> v =
        new VoidVisitorAdapter<Void>() {
          @Override
          public void visit(AssignExpr ae, Void arg) {
            if (found[0]) return;
            super.visit(ae, arg);
            if (containsModuloUpdate(ae, var)) {
              found[0] = true;
            }
          }
        };
    body.accept(v, null);
    return found[0];
  }

  /**
   * Return true if {@code e} has the shape {@code var = (var + K) % N} for the given induction var.
   */
  private static boolean containsModuloUpdate(Expression e, String var) {
    if (!(e instanceof AssignExpr ae)) return false;
    if (!(ae.getTarget() instanceof NameExpr ne)) return false;
    if (!var.equals(ne.getNameAsString())) return false;
    Expression rhs = ae.getValue();
    if (!(rhs instanceof BinaryExpr outer)
        || outer.getOperator() != BinaryExpr.Operator.REMAINDER) {
      return false;
    }
    if (!isPositiveIntegerLiteral(outer.getRight())) return false;
    if (!(outer.getLeft() instanceof EnclosedExpr ee)) return false;
    Expression inner = ee.getInner();
    if (!(inner instanceof BinaryExpr add) || add.getOperator() != BinaryExpr.Operator.PLUS) {
      return false;
    }
    if (!(add.getLeft() instanceof NameExpr addLhs)) return false;
    if (!var.equals(addLhs.getNameAsString())) return false;
    return isPositiveIntegerLiteral(add.getRight());
  }

  private static boolean isPositiveIntegerLiteral(Expression e) {
    if (!(e instanceof IntegerLiteralExpr lit)) return false;
    String s = lit.getValue();
    try {
      return Long.parseLong(s) > 0;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  /**
   * Return true if the body (transitively) contains a {@code break}, {@code return}, or {@code
   * throw} statement. Used to suppress false positives when the loop has an explicit exit point.
   */
  private static boolean bodyHasExit(Statement body) {
    if (body == null) return false;
    final boolean[] hasExit = {false};
    VoidVisitorAdapter<Void> v =
        new VoidVisitorAdapter<Void>() {
          @Override
          public void visit(BreakStmt b, Void a) {
            if (hasExit[0]) return;
            super.visit(b, a);
            hasExit[0] = true;
          }

          @Override
          public void visit(ReturnStmt r, Void a) {
            if (hasExit[0]) return;
            super.visit(r, a);
            hasExit[0] = true;
          }

          @Override
          public void visit(ThrowStmt t, Void a) {
            if (hasExit[0]) return;
            super.visit(t, a);
            hasExit[0] = true;
          }
        };
    body.accept(v, null);
    return hasExit[0];
  }

  private static int lineOf(com.github.javaparser.ast.nodeTypes.NodeWithRange n) {
    java.util.Optional<com.github.javaparser.Position> begin = n.getBegin();
    if (begin == null) return -1;
    com.github.javaparser.Position p = begin.orElse(null);
    return p == null ? -1 : p.line;
  }

  private void fireCwe835(StyleContext ctx, int line, String shape, String reason) {
    String dedupKey = shape + "@" + line;
    if (!seen().add(dedupKey)) return;
    ctx.report(
        line, -1, DEFAULT_RULE, reason + " (CWE-835).", RuleDef.Severity.MEDIUM, "loop@" + shape);
  }

  /**
   * R-AST-FIX-2026-10-18: was previously a {@code static ThreadLocal<Set<String>>} for "per-file
   * dedup". The {@link org.plumb.style.AstDispatcher} walks one file at a time on the calling
   * thread, so a thread-local is just a static with extra indirection. We replaced it with a
   * plain instance {@code HashSet} cleared in {@link #init(StyleContext)} ? every (file, scan)
   * gets a fresh dedup set.
   *
   * <p>Was {@code static final Set<String> SEEN} before P1#6 hardening; now per-instance so the
   * dispatcher can safely parallelise the file scan (each thread gets its own checker
   * instance) without cross-contaminating the dedup set. Each scan also constructs a fresh
   * checker via {@code AstCheckerRegistry.tryRegister} which calls
   * {@code Class.getDeclaredConstructor().newInstance()}, so the per-instance lifetime is
   * one scan.
   */
  private final Set<String> seen = new HashSet<>();

  private Set<String> seen() {
    return seen;
  }
}
