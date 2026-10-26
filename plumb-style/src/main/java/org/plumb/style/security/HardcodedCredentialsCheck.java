package org.plumb.style.security;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * String literal assigned to a credential-named variable / field (use config / secrets manager).
 *
 * <p>S2 audit (2026-10-17) fixed the type filter -- the original {@code if (!primitiveType &&
 * !"String".equals(...)) if(!"char".equals(...))} chain was confusing and the {@code char} branch
 * was dead code (the {@code char} case had already been let through by the first check). The new
 * filter is a clean predicate: accept {@code String} and {@code char[]} (the two practical
 * representations of credential material) and reject everything else.
 */
public final class HardcodedCredentialsCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-security-hardcoded-credentials", "HARDCODED_CREDENTIALS"),
          "String literal assigned to a credential-named variable (use config / secrets manager)",
          RuleDef.Severity.CRITICAL,
          RuleDef.Type.SECURITY,
          List.of("CWE-798", "CWE-259"),
          "org.plumb.style.HardcodedCredentialsCheck",
          true,
          List.of("ast-security-hardcoded-credentials", "hardcoded-credentials"));

  @Override
  public String name() {
    return "hardcoded-credentials";
  }

  /**
   * The rule prompt the LLM sees when judging one of our hardcoded-credential findings. Describes
   * when the rule is a true positive vs a false positive (test fixtures, example docs, framework
   * defaults).
   */
  private static final String AI_PROMPT =
      """
      RULE: Hardcoded credentials in source (CWE-798 / CWE-259).
      This rule fires when a string literal is assigned to a
      variable / field whose name suggests it carries a
      credential (password, apiKey, secret, token, etc.).

      A finding is a TRUE POSITIVE (TP) when the literal is
      a real-looking credential that ships with the
      application: API keys with sk_live_..., bearer tokens,
      AWS keys, real-looking passwords, OAuth client secrets.

      A finding is a FALSE POSITIVE (FP) when:
        * The literal is a clearly-not-a-secret placeholder
          ("P@ssw0rd", "your-password-here", "<set-me>",
          "REPLACE_ME")
        * The literal is in a test fixture (class named *Test*,
          *Spec*, or under src/test/) AND it looks synthetic
        * The literal is a JCA / BouncyCastle algorithm name
          or constant string used as a key
        * The literal is documentation / a constant in a config
          loader that throws a clear "must be overridden" error

      A finding is UNKNOWN when you cannot determine from
      the source context alone (e.g. an env-var value that
      could be a real key in production). Prefer UNKNOWN
      over guessing.
      """;

  @Override
  public String getAiPrompt(Finding finding) {
    return AI_PROMPT;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    if (!isCredentialEligibleType(n.getElementType().asString(), ctx, n)) {
      return;
    }
    for (VariableDeclarator v : n.getVariables()) {
      checkAssignment(
          v.getNameAsString(),
          v.getInitializer().orElse(null),
          v.getBegin().map(p -> p.line).orElse(-1),
          ctx);
    }
  }

  @Override
  public void visit(VariableDeclarationExpr n, StyleContext ctx) {
    // Local variable declarations inside a method.
    // VariableDeclarationExpr holds the element type on the
    // parent node (the declaration itself), not on each
    // variable declarator.
    com.github.javaparser.ast.type.Type elType = n.getElementType();
    if (!isCredentialEligibleType(elType.asString(), ctx, n)) {
      return;
    }
    for (VariableDeclarator v : n.getVariables()) {
      checkAssignment(
          v.getNameAsString(),
          v.getInitializer().orElse(null),
          v.getBegin().map(p -> p.line).orElse(-1),
          ctx);
    }
  }

  /**
   * Returns true when {@code astTypeName} is one of the types we consider for credential storage:
   * {@code String} or {@code char[]} (the JCA-recommended type for password fields). Falls back to
   * JavaParser type resolution when the AST text isn't a clean match (e.g. fully-qualified type
   * names like {@code java.lang.String}).
   */
  private static boolean isCredentialEligibleType(
      String astTypeName, StyleContext ctx, com.github.javaparser.ast.Node anchor) {
    if ("String".equals(astTypeName) || "char[]".equals(astTypeName)) return true;
    if (ctx != null) {
      ResolvedType rt = ctx.resolvedTypeOf(anchor);
      if (rt != null) {
        String desc = rt.describe();
        if ("java.lang.String".equals(desc) || "char[]".equals(desc)) return true;
      }
    }
    return false;
  }

  private void checkAssignment(String name, Expression init, int line, StyleContext ctx) {
    if (init == null) return;
    if (!(init instanceof StringLiteralExpr sl)) return;
    // Skip trivial empty/short strings.
    final String value = sl.getValue();
    if (value == null || value.length() < 4) return;
    if (!looksLikeCredentialName(name)) return;
    ctx.report(
        line,
        -1,
        DEFAULT_RULE,
        "Hardcoded credential assigned to '" + name + "'",
        RuleDef.Severity.CRITICAL,
        name);
  }

  /** Heuristic: a name that contains a credential keyword. Catches the common naming shapes. */
  private static boolean looksLikeCredentialName(String name) {
    // M7: the keyword list lives in CheckerConfig so
    // operators can extend it in one place rather than
    // patching this checker. The helper
    // CheckerConfig.nameLooksLikeCredential does the
    // case-insensitive substring match.
    return org.plumb.style.config.CheckerConfig.nameLooksLikeCredential(name);
  }
}
