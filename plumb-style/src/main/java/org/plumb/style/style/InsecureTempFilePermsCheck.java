/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
/*
 * <p>Previously this class independently implemented ruleId
 * {@code "ast-style-insecure-temp-file-perms"} (-A1). The
 * canonical owner is
 * {@code org.plumb.style.security.InsecureTempFilePermsCheck} (the
 * original migration target). Keeping both produced a
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

  /** */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-insecure-temp-file-perms-DEDUPED", "INSECURE_TEMP_FILE_PERMS_DEDUPED"),
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
