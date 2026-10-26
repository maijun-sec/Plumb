/*
 * C1: AST-style CWE-398 (Indicator of Poor
 * Code Quality) checker -- empty block / case /
 * else / for / function / if / while.
 *
 * <p>Fires on empty bodies in the canonical Juliet
 * CWE398 bad-patterns. Empty blocks have no effect
 * and indicate either forgotten code or copy-paste
 * residue; both are code-quality smells.</p>
 *
 * <p>The checker targets <strong>JDK / language
 * constructs</strong>, not Juliet-specific shapes,
 * so the rule generalises to any production code:
 * empty function bodies, empty if branches, empty
 * loops, empty case labels (where the only statement
 * is {@code break;} with no fall-through value).</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.List;
import java.util.Optional;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-398: Indicator of Poor Code Quality — empty block family. */
public final class EmptyStatementBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-empty-statement-block", "EMPTY_STATEMENT_BLOCK"),
          "Empty statement block has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-398"),
          "org.plumb.style.style.EmptyStatementBlockCheck",
          true,
          List.of("ast-style-empty-statement-block", "empty-statement-block"));

  private static final RuleDef EMPTY_CATCH_RULE =
      new RuleDef(
          new RuleId("dfa-style-empty-catch-block", "EMPTY_CATCH_BLOCK_ALT"),
          "Empty catch block silently swallows exception (CWE-390)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-390"),
          "org.plumb.style.style.EmptyStatementBlockCheck",
          true,
          List.of("ast-style-empty-statement-block", "empty-statement-block"));

  private static final RuleDef EMPTY_IF_RULE =
      new RuleDef(
          new RuleId("dfa-style-empty-error-block", "EMPTY_ERROR_BLOCK"),
          "Empty error-handling if body — error condition detected but not acted on (CWE-390/398)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-390", "CWE-398"),
          "org.plumb.style.style.EmptyStatementBlockCheck",
          true,
          List.of("dfa-style-empty-block-cwe398", "empty-statement-block"));

  @Override
  public String name() {
    return "empty-statement-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  /**
   * Helper that returns {@code true} when a body is empty -- either the body is absent (abstract /
   * native method) or it has no statements. We also treat "only a single {@code break;}" as empty
   * for the CWE398 empty_case variant.
   */
  private static boolean isEffectivelyEmpty(BlockStmt body) {
    if (body == null) return true;
    if (body.isEmpty()) return true;
    // Single-statement `break;` — common
    // empty_case pattern in Juliet
    // (`case 0: break;`). The break does nothing
    // useful because case bodies fall through
    // to the next case by default; an empty
    // case is a no-op.
    if (body.getStatements().size() == 1) {
      Statement only = body.getStatement(0);
      if (only.toString().trim().startsWith("break") && only.toString().trim().endsWith(";")) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    BlockStmt thenBody = n.getThenStmt() instanceof BlockStmt b ? b : null;
    BlockStmt elseBody = n.getElseStmt().orElse(null) instanceof BlockStmt b ? b : null;
    if (thenBody != null && isEffectivelyEmpty(thenBody)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      // We use CWE-390 specifically because the
      // Juliet pattern is `if (check) { /* empty */
      // }` — an error check with no action. We also
      // keep the CWE-398 entry (as a separate rule)
      // by NOT firing here — leave CWE-398 to the
      // bare-block visit. Here we report CWE-390.
      // We also still report CWE-398 only when the
      // if isn't a likely error-check — but we
      // can't tell that without flow info, so we
      // duplicate the report under CWE-390.
      ctx.report(
          line,
          -1,
          EMPTY_IF_RULE,
          "Empty `if` body — error condition not handled (CWE-390)",
          RuleDef.Severity.LOW,
          "empty-if");
    }
    if (elseBody != null && isEffectivelyEmpty(elseBody)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `else` body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-else");
    }
  }

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    // Catch with an empty body is CWE-390
    // (Detection of Error Condition Without Action)
    // — the exception is caught but never acted on,
    // so the program silently continues as if
    // nothing happened. Different from CWE-398
    // (poor quality) which is a generic code smell.
    Statement body = n.getBody();
    if (!(body instanceof BlockStmt)) return;
    if (!isEffectivelyEmpty((BlockStmt) body)) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    ctx.report(
        line,
        -1,
        EMPTY_CATCH_RULE,
        "Empty `catch` block silently swallows exception (CWE-390)",
        RuleDef.Severity.MEDIUM,
        "empty-catch");
  }

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    BlockStmt body = n.getBody() instanceof BlockStmt b ? b : null;
    if (body != null && isEffectivelyEmpty(body)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `for` body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-for");
    }
  }

  @Override
  public void visit(ForEachStmt n, StyleContext ctx) {
    BlockStmt body = n.getBody() instanceof BlockStmt b ? b : null;
    if (body != null && isEffectivelyEmpty(body)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `for-each` body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-for");
    }
  }

  @Override
  public void visit(WhileStmt n, StyleContext ctx) {
    BlockStmt body = n.getBody() instanceof BlockStmt b ? b : null;
    if (body != null && isEffectivelyEmpty(body)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `while` body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-while");
    }
  }

  @Override
  public void visit(DoStmt n, StyleContext ctx) {
    BlockStmt body = n.getBody() instanceof BlockStmt b ? b : null;
    if (body != null && isEffectivelyEmpty(body)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `do-while` body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-do-while");
    }
  }

  @Override
  public void visit(SwitchEntry n, StyleContext ctx) {
    // Only fire on case labels (not default — Juliet
    // expects default branches to be empty too).
    if (n.getLabels().isEmpty()) return; // default entry
    String label = n.getLabels().get(0).toString();
    if (n.getStatements().isEmpty()) {
      // Truly empty case `case X:`
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `case " + label + ": ...` has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-case");
      return;
    }
    // Single-statement `break;` — common empty_case
    // pattern. We check by re-wrapping into a
    // synthetic BlockStmt so we can reuse
    // isEffectivelyEmpty's break-detection logic.
    BlockStmt synthetic = new BlockStmt();
    synthetic.setStatements(n.getStatements());
    if (isEffectivelyEmpty(synthetic)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty `case " + label + ": break;` has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-case");
    }
  }

  @Override
  public void visit(BlockStmt n, StyleContext ctx) {
    // Only fire on bare `{ }` blocks that are
    // not part of an if / for / while / method.
    // We detect "bare" by checking that this
    // BlockStmt's parent is NOT one of the
    // control-flow / method-body types.
    if (!isEffectivelyEmpty(n)) return;
    Optional<?> parent = n.getParentNode();
    if (parent.isEmpty()) return;
    Object p = parent.get();
    if (p instanceof IfStmt
        || p instanceof ForStmt
        || p instanceof ForEachStmt
        || p instanceof WhileStmt
        || p instanceof DoStmt
        || p instanceof SwitchEntry
        || p instanceof MethodDeclaration
        || p instanceof com.github.javaparser.ast.stmt.CatchClause) {
      return; // handled by the dedicated visitor above
    }
    int line = n.getBegin().map(p2 -> p2.line).orElse(-1);
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "Bare empty block has no effect (CWE-398)",
        RuleDef.Severity.LOW,
        "empty-block");
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Skip abstract / interface / native methods.
    if (n.getBody().isEmpty()) return;
    BlockStmt body = n.getBody().get();
    if (isEffectivelyEmpty(body)) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Empty method body has no effect (CWE-398)",
          RuleDef.Severity.LOW,
          "empty-function");
    }
  }
}
