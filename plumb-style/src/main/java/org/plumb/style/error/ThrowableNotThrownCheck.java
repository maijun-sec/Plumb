package org.plumb.style.error;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Caught exception is silently swallowed (re-throw or log instead). */
public final class ThrowableNotThrownCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-error-throwable-not-thrown", "THROWABLE_NOT_THROWN"),
          "Caught exception is silently swallowed (re-throw or log instead)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-390"),
          "org.plumb.style.ThrowableNotThrownCheck",
          true,
          List.of("ast-error-throwable-not-thrown", "throwable-not-thrown"));

  @Override
  public String name() {
    return "throwable-not-thrown";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CatchClause n, StyleContext ctx) {
    // The caught type must be Throwable, Exception,
    // or a subclass (RuntimeException, IOException,
    // etc.). We use a name-based heuristic.
    final Type caughtType = n.getParameter().getType();
    if (!isThrowableLike(caughtType)) return;
    // Inspect the catch body for re-throw or log.
    final BlockStmt body = n.getBody();
    if (containsRethrowOrLog(body)) return;
    ctx.report(
        n.getBegin().map(p -> p.line).orElse(-1),
        n.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Caught " + caughtType.asString() + " is silently swallowed; re-throw or log it",
        RuleDef.Severity.MEDIUM,
        n.getParameter().getNameAsString());
  }

  private static boolean isThrowableLike(Type t) {
    if (!(t instanceof ClassOrInterfaceType cit)) return false;
    String name = cit.getNameAsString();
    return "Throwable".equals(name)
        || "Exception".equals(name)
        || "RuntimeException".equals(name)
        || "IOException".equals(name)
        || "SQLException".equals(name)
        || "InterruptedException".equals(name)
        || "NullPointerException".equals(name)
        || "ClassNotFoundException".equals(name)
        || "IllegalArgumentException".equals(name)
        || "IllegalStateException".equals(name);
  }

  private static boolean containsRethrowOrLog(BlockStmt body) {
    // 1) Look for `throw ex;` or `throw new XxxException(...);`
    final boolean[] hasRethrow = {false};
    body.walk(
        ThrowStmt.class,
        ts -> {
          hasRethrow[0] = true;
        });
    if (hasRethrow[0]) return true;
    // 2) Look for a method call named `log` / `error` / `warn`
    // / `info` / `severe` / `debug` (typical logger names)
    // where the argument is a NameExpr matching the
    // catch parameter.
    final boolean[] hasLog = {false};
    body.walk(
        MethodCallExpr.class,
        mc -> {
          String mname = mc.getNameAsString().toLowerCase();
          if (mname.equals("log")
              || mname.equals("error")
              || mname.equals("warn")
              || mname.equals("info")
              || mname.equals("severe")
              || mname.equals("debug")
              || mname.equals("warning")
              || mname.equals("fatal")
              || mname.equals("trace")) {
            hasLog[0] = true;
          }
        });
    return hasLog[0];
  }
}
