package org.plumb.style.style;

import com.github.javaparser.ast.stmt.*;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Code after return/throw/break/continue is unreachable. */
public final class DeadcodeCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-deadcode", "DEADCODE"),
          "Code after return/throw/break/continue is unreachable",
          RuleDef.Severity.LOW,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-561"),
          "org.plumb.style.DeadcodeCheck",
          true,
          List.of("ast-style-unreachable-code", "deadcode"));

  @Override
  public String name() {
    return "deadcode";
  }

  @Override
  public void init(StyleContext ctx) {
    // no per-file state needed
  }

  @Override
  public void visit(BlockStmt n, StyleContext ctx) {
    // Walk the statements in order. Once we see a
    // return or throw, every later statement in this
    // block is unreachable. A break/continue only
    // affects the enclosing loop/switch, so they are
    // not block-level terminators in the strict
    // sense : but to keep things simple and to match
    // what the canonical DEADCODE check would say in a flat
    // method body, we do treat a bare `break;` (not
    // inside a loop/switch, which would be a compile
    // error anyway) and a bare `continue;` as
    // block-level terminators.
    boolean dead = false;
    for (Statement s : n.getStatements()) {
      if (dead) {
        // Skip empty statements : they are
        // semantically no-ops and may be
        // intentional (e.g. the final `;` of a
        // for-loop's body in a synthesized AST).
        if (s.isEmptyStmt()) continue;
        // Skip BlockStmt with no content (e.g. an
        // empty `{}` block placed after return).
        if (s.isBlockStmt() && s.asBlockStmt().getStatements().isEmpty()) {
          continue;
        }
        // Skip comments-only positions: there's
        // no good way to detect those without a
        // comment-collection pass; we accept the
        // tiny FP risk in this corner case.
        ctx.report(
            s.getBegin().map(p -> p.line).orElse(-1),
            s.getBegin().map(p -> p.column).orElse(-1),
            DEFAULT_RULE,
            "Unreachable code after a return/throw/break/continue",
            RuleDef.Severity.LOW,
            s.toString());
        // Continue firing on every later statement
        // so a single `return; println(); foo();`
        // emits two findings.
        continue;
      }
      if (isBlockTerminator(s)) {
        dead = true;
      }
      // For try/catch/finally, we don't try to be
      // clever : a try block's statements are
      // separately analyzed, but the try's finally
      // block may be reached. We just walk the
      // immediate children.
    }
    // Recurse into nested blocks (e.g. if/else
    // bodies) so the rule fires across the whole
    // method, not just the top-level block.
    for (Statement s : n.getStatements()) {
      s.walk(
          BlockStmt.class,
          b -> {
            if (b == n) return; // already visited
            visit(b, ctx);
          });
    }
  }

  /**
   * Returns true if exits the enclosing block. We are conservative and count bare which are only
   * legal inside a loop/switch, so a "bare" break in a flat block would actually be a compile error
   * in Java).
   */
  private static boolean isBlockTerminator(Statement s) {
    if (s instanceof ReturnStmt) return true;
    if (s instanceof ThrowStmt) return true;
    if (s instanceof BreakStmt) return true;
    if (s instanceof ContinueStmt) return true;
    return false;
  }
}
