package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * CWE-526 (Information Exposure Through Environment Variables).
 *
 * <p>Fires when a single statement contains both an env-var read ({@code System.getenv(...)}) and a
 * response / log writer call ({@code getWriter()}, {@code getOutputStream()}, {@code IO.writeLine},
 * etc.).
 */
public final class EnvVarInfoExposureCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-env-var-info-exposure", "ENV_VAR_INFO_EXPOSURE"),
          "System.getenv() result is written to a response / log writer (CWE-526).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BAD_PRACTICE,
          List.of("CWE-526"),
          "org.plumb.style.EnvVarInfoExposureCheck",
          true,
          List.of("ast-style-env-var-info-exposure-style", "env-var-info-exposure"));

  @Override
  public String name() {
    return "env-var-info-exposure";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr mc, StyleContext ctx) {
    if (!isWriterCall(mc)) return;
    if (!containsGetenv(mc)) return;
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "System.getenv() result is written to a"
            + " response / log writer at line "
            + line
            + "; environment variable is exposed to"
            + " remote callers (CWE-526).",
        RuleDef.Severity.MEDIUM,
        "env-var-info-exposure@writer-line " + line);
  }

  private static boolean isWriterCall(MethodCallExpr mc) {
    String name = mc.getNameAsString();
    if ("getWriter".equals(name) || "getOutputStream".equals(name)) {
      return true;
    }
    if ("println".equals(name) || "print".equals(name)) {
      return true;
    }
    if ("writeLine".equals(name) || "write".equals(name)) {
      if (mc.getScope().isPresent()
          && mc.getScope().get() instanceof NameExpr ne
          && "IO".equals(ne.getNameAsString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsGetenv(MethodCallExpr mc) {
    final boolean[] found = {false};
    mc.walk(
        MethodCallExpr.class,
        sub -> {
          if (found[0]) return;
          if ("getenv".equals(sub.getNameAsString())
              && sub.getScope().isPresent()
              && sub.getScope().get() instanceof NameExpr ne
              && "System".equals(ne.getNameAsString())) {
            found[0] = true;
          }
        });
    return found[0];
  }
}
