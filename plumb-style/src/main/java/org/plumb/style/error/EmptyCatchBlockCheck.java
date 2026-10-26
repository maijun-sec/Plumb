package org.plumb.style.error;

import com.github.javaparser.ast.stmt.CatchClause;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Empty catch block ? strict. */
public final class EmptyCatchBlockCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-empty-catch-block", "EMPTY_CATCH_BLOCK"),
          "empty catch block — strict",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.EmptyCatchBlockCheck",
          true,
          List.of("ast-error-catch-block-strict", "empty-catch-block"));

  @Override
  public String name() {
    return "empty-catch-block";
  }

  /**
   * The rule prompt the LLM sees when judging an empty-catch finding. Empty catches are common in
   * test cleanup / Optional.orElse(null)-style code paths where the intent is "I really mean to
   * ignore this".
   */
  private static final String AI_PROMPT =
      """
      RULE: Empty catch block swallows exceptions.
      This rule fires when a catch clause body is empty
      (no statements) or contains only a comment.

      A finding is a FALSE POSITIVE (FP) when:
        * The catch is a deliberate "ignore this" pattern
          with at least a comment explaining why (e.g.
          "swallow interrupt", "ignore close failure",
          "Optional.ifPresent on a value we know is null")
        * The catch is in a finally / close() / cleanup
          path where throwing would mask the real exception
        * The catch is in a test method that asserts the
          exception is NOT thrown
        * The catch is an explicit
          "Thread.currentThread().interrupt();" + comment
          for the InterruptedException idiom

      A finding is a TRUE POSITIVE (TP) when the empty
      catch hides a real exception (e.g. in a service
      method, in a try/catch around an external call,
      in a run() override).

      A finding is UNKNOWN when the context is ambiguous
      (caller could be a test, could be production).
      Prefer UNKNOWN over guessing.
      """;

  @Override
  public String getAiPrompt(Finding finding) {
    return AI_PROMPT;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause cc, StyleContext ctx) {
    if (!cc.getBody().getStatements().isEmpty()) return;
    int line = cc.getBegin().map(p -> p.line).orElse(-1);
    int col = cc.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Empty catch block; use logging or rethrow",
        RuleDef.Severity.MEDIUM,
        "catch");
  }
}
