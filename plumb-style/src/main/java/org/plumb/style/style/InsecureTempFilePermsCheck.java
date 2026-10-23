/*
 * R-AST-FIX-2026-10-18 (deduplication).
 *
 * <p>Previously this class independently implemented ruleId
 * {@code "ast-style-insecure-temp-file-perms"} (R729-A1). The
 * canonical owner is
 * {@code org.plumb.style.security.InsecureTempFilePermsCheck} (the
 * original R-812 migration target). Keeping both produced a
 * ruleId collision in {@code RuleRegistry} that intermittently
 * shadowed findings.
 *
 * <p>This class is preserved as a {@code @Deprecated} no-op so that
 * any code path which keyed against this exact FQN still resolves
 * (config validation, FQN lookups), but {@code enabled=false} on its
 * RuleDef causes
 * {@link org.plumb.style.AstCheckerRegistry#tryRegister} to skip
 * registering it. Net effect: stub removed from registry surface.
 */
package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * @deprecated superseded by {@code security/InsecureTempFilePermsCheck}; retained as a no-op for
 *     FQN compatibility only.
 */
@Deprecated
public final class InsecureTempFilePermsCheck implements AstChecker {

  /**
   * R-AST-FIX-2026-10-18: enabled=false. {@link
   * org.plumb.style.AstCheckerRegistry} calls {@link #rule()} on the
   * freshly-instantiated checker and skips registration when {@code
   * rule().enabled()} is false.
   */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-insecure-temp-file-perms-DEDUPED",
              "INSECURE_TEMP_FILE_PERMS_DEDUPED"),
          "DEPRECATED stub — canonical owner is security/InsecureTempFilePermsCheck",
          RuleDef.Severity.LOW,
          RuleDef.Type.SECURITY,
          List.of("CWE-378"),
          "org.plumb.style.InsecureTempFilePermsCheck",
          /* enabled */ false);

  @Override
  public String name() {
    return "insecure-temp-file-perms-DEPRECATED";
  }

  @Override
  public RuleDef rule() {
    return DISABLED_RULE;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {
    // no-op: real implementation lives in security/InsecureTempFilePermsCheck.
  }
}
