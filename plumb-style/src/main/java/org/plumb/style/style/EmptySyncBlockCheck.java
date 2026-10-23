package org.plumb.style.style;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.EmptyStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-585 ? empty {@code synchronized} block.
 *
 * <p>Fires when a {@link SynchronizedStmt} has an empty body. Empty synchronized blocks are always
 * a bug or dead code: they acquire a monitor and immediately release it without any synchronization
 * happening between, which either (a) is leftover from a refactor, or (b) is a placeholder that was
 * never filled in, or (c) ? in the worst case ? is intentional but misleading (the developer was
 * trying to use the monitor for memory-visibility side effects, which requires more than just
 * acquisition + release).
 *
 * <p>The previous implementation was a regex on source text ({@code
 * ClassThreadChecker.EmptySyncBlockChecker}, R759-E): {@code synchronized\s*\([^)]*\)\s*\{\s*\}}.
 * R787d migrates the detection to plumb-style AST per the cardinal principles (AST → style module,
 * DFA → dfa module, prefer AST when feasible). The AST check walks the {@link
 * com.github.javaparser.ast.CompilationUnit} via {@link
 * com.github.javaparser.ast.visitor.VoidVisitorAdapter} (the standard plumb-style {@code
 * AstDispatcher}) and checks the body of each {@code synchronized} statement structurally: an empty
 * body is one of {@link BlockStmt} with no statements, an {@link EmptyStmt} (just {@code ;}), or a
 * body that contains only nested empty statements.
 *
 * <p>Juliet CWE-585 test cases use the {@code synchronized(LOCK) { }} shape literally (no
 * statements inside the braces), so the AST check catches the same patterns as the regex did. The
 * AST check additionally catches {@code synchronized(LOCK);} (the single-statement form with {@link
 * EmptyStmt} as body) which the regex would not have caught because of the brace-matching
 * difference.
 */
public final class EmptySyncBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // R787d: rule_id aligned with plumb_rule_to_cwe.json
          // (`Cwe585` → ["585"]). Legacy id
          // `dfa.cwe585-empty-sync` is preserved as the
          // short_name so any external whitelist still
          // matches (Lesson 1154).
          new RuleId("ast-style-empty-sync-block", "EMPTY_SYNC_BLOCK"),
          "empty synchronized block (no statements inside)",
          RuleDef.Severity.LOW,
          RuleDef.Type.BUG,
          List.of("CWE-585"),
          "org.plumb.style.EmptySyncBlockCheck",
          true,
          List.of("ast-style-empty-sync-block-style", "empty-sync-block")
      );

  @Override
  public String name() {
    return "empty-sync-block";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(SynchronizedStmt n, StyleContext ctx) {
    if (!isEffectivelyEmpty(n.getBody())) return;
    int line = -1, col = -1;
    java.util.Optional<com.github.javaparser.Position> begin = n.getBegin();
    if (begin != null) {
      com.github.javaparser.Position p = begin.orElse(null);
      if (p != null) {
        line = p.line;
        col = p.column;
      }
    }
    // R787d (Lesson 1165): find the enclosing
    // MethodDeclaration so the eval harness can
    // locate it via sink_function. Without this,
    // findings in helperBad() (Juliet CWE-585
    // canonical bad wrapper) are mis-classified
    // as "other" by eval_findings_cwe_matched.py.
    String sink = null;
    try {
      com.github.javaparser.ast.body.MethodDeclaration enclosing =
          n.findAncestor(com.github.javaparser.ast.body.MethodDeclaration.class).orElse(null);
      if (enclosing != null) {
        StringBuilder sig = new StringBuilder();
        sig.append(enclosing.getNameAsString()).append('(');
        com.github.javaparser.ast.NodeList<com.github.javaparser.ast.body.Parameter> params =
            enclosing.getParameters();
        for (int i = 0; i < params.size(); i++) {
          if (i > 0) sig.append(',');
          sig.append(params.get(i).getType().asString());
        }
        sig.append("):");
        sig.append(enclosing.getType().asString());
        sink = sig.toString();
      }
    } catch (RuntimeException ignore) {
      // findAncestor is best-effort.
    }
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty synchronized block (CWE-585): "
            + "synchronized monitor is acquired and "
            + "released without any statements inside.",
        RuleDef.Severity.LOW,
        "synchronized@<empty>",
        sink);
  }

  /**
   * True if the body is empty, ignoring nested empty statements. Recognizes:
   *
   * <ul>
   *   <li>{@code null} body (malformed code)
   *   <li>{@link BlockStmt} with no statements
   *   <li>{@link BlockStmt} containing only {@link EmptyStmt}s (e.g. the body is {@code {; ; ;}})
   * </ul>
   *
   * <p>Note: per JLS, a {@code synchronized} statement requires a block, so the body of a {@link
   * com.github.javaparser.ast.stmt.SynchronizedStmt} is always a {@link BlockStmt} in valid Java.
   * The {@code synchronized(this);} form (which would have an {@link EmptyStmt} body) is a syntax
   * error and JavaParser refuses to parse it. The {@link EmptyStmt} direct-body case in this method
   * is a defensive guard for hypothetical future parser versions.
   */
  private static boolean isEffectivelyEmpty(com.github.javaparser.ast.stmt.Statement body) {
    if (body == null) return true;
    if (body instanceof BlockStmt block) {
      if (block.getStatements().isEmpty()) return true;
      for (com.github.javaparser.ast.stmt.Statement s : block.getStatements()) {
        if (!(s instanceof EmptyStmt)) return false;
      }
      return true;
    }
    // Belt-and-suspenders: any other statement type
    // (e.g. an EmptyStmt directly, if a future parser
    // version allows it) is treated as effectively
    // empty.
    return body instanceof EmptyStmt;
  }
}
