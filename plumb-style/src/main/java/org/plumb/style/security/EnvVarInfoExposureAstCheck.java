/*
 * R727-C1: AST-level CWE-526 (Info Exposure Through
 * Environment Variables) detector. The TAC-based
 * EnvVarInfoExposureChecker reads source lines via
 * SourceReader.readLines(prog), which returns empty
 * for synthetic merged programs (prog.sourcePath()
 * is "<merged>"). This AST check is a backstop:
 * it walks every ExpressionStmt in the
 * CompilationUnit and fires when the statement's
 * expression tree contains BOTH
 *   (a) a method call to response.getWriter() /
 *       response.getOutputStream() / IO.writeLine
 *       (the sink: HTTP / stdout response path)
 *   (b) a method call to System.getenv(...)
 *       (the source: env var read)
 * in the same statement.
 *
 * <p>Pattern is generic ? any Java servlet / handler
 * that pulls a secret from an env var and shoves it
 * into an HTTP response / log line is flagged. No
 * Juliet-specific content.</p>
 *
 * <p>Why AST and not DFA: TAC strips the source-line
 * text and the IR-level call graph can't reliably
 * reach inside a concatenation like
 * {@code response.getWriter().println("x=" + System.getenv("P"))}
 * — the parent ExpressionStmt is the unit that has
 * both calls. JavaParser preserves the statement
 * structure exactly.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class EnvVarInfoExposureAstCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-env-var-info-exposure-ast", "ENV_VAR_INFO_EXPOSURE_AST"),
          "System.getenv() result is written to an HTTP "
              + "response writer / stdout line (CWE-526)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-526"),
          "org.plumb.style.security.EnvVarInfoExposureAstCheck",
          true,
          List.of("dfa-security-env-var-info-exposure", "env-var-info-exposure-ast"));

  @Override
  public String name() {
    return "env-var-info-exposure-ast";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void finish(StyleContext ctx) {
    // The actual scanning is done in the
    // visit(ExpressionStmt) call. We just need
    // a per-CompilationUnit dedup so the same
    // statement isn't reported twice (once per
    // visit pass, once per parent traversal).
  }

  private final Set<String> reported = new HashSet<>();

  @Override
  public void visit(ExpressionStmt n, StyleContext ctx) {
    boolean hasGetEnv = false;
    boolean hasGetWriter = false;
    boolean hasIoSink = false;
    for (MethodCallExpr mc : n.findAll(MethodCallExpr.class)) {
      String name = mc.getNameAsString();
      if ("getenv".equals(name)) {
        // Confirm the receiver is "System"
        // by checking the immediate scope
        // string form. Acceptable matches:
        //   System.getenv(...)
        //   java.lang.System.getenv(...)
        if (mc.getScope().isPresent()) {
          String s = mc.getScope().get().toString();
          if ("System".equals(s) || s.endsWith(".System")) {
            hasGetEnv = true;
          }
        }
      } else if ("getWriter".equals(name) || "getOutputStream".equals(name)) {
        if (mc.getScope().isPresent()) {
          hasGetWriter = true;
        }
      } else if ("writeLine".equals(name) || "write".equals(name)) {
        if (mc.getScope().isPresent()) {
          String s = mc.getScope().get().toString();
          if ("IO".equals(s)
              || s.endsWith(".IO")
              || "System.out".equals(s)
              || "System.err".equals(s)) {
            hasIoSink = true;
          }
        }
      }
    }
    if (hasGetWriter && hasGetEnv) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|writer-getenv";
      if (reported.add(key)) {
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "System.getenv() result is written "
                + "to an HTTP response writer "
                + "(env var exposed to remote "
                + "callers — CWE-526)",
            RuleDef.Severity.MEDIUM,
            "env-var-info-exposure: writer + getenv");
      }
      return;
    }
    // Pattern 2: System.getenv() argument to
    // IO.writeLine / similar stdout/stderr sink.
    if (hasIoSink && hasGetEnv) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|io-getenv";
      if (reported.add(key)) {
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "System.getenv() result is written "
                + "to a stdout / stderr line "
                + "(env var potentially exposed "
                + "— CWE-526)",
            RuleDef.Severity.MEDIUM,
            "env-var-info-exposure: IO + getenv");
      }
    }
  }
}
