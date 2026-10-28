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
 * <p>The original checker was a no-op
 * ({@code // Heuristic: skip — would need annotation processing.}). It
 * existed only to occupy a RuleRegistry slot. Marking the rule
 * {@code enabled=false} causes
 * {@link org.plumb.style.AstCheckerRegistry#tryRegister} to drop it
 * from {@code builtins()} and {@code registeredNames()}.
 */
package org.plumb.style.style;

import com.github.javaparser.ast.CompilationUnit;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * @SuppressWarnings should be specific -- unimplemented.
 *
 * <p>Detection requires annotation processing / symbol resolution which is outside the AST pass.
 * Kept as a registry-skipped placeholder; an analyzer-level checker should replace this when
 * needed.
 */
@Deprecated
public final class SuppressWarningsCheck implements AstChecker {

  /** */
  private static final RuleDef DISABLED_RULE =
      new RuleDef(
          new RuleId(
              "ast-style-suppress-warnings-NOT-IMPLEMENTED", "SUPPRESS_WARNINGS_NOT_IMPLEMENTED"),
          "@SuppressWarnings not specific — STUB, not registered",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.SuppressWarningsCheck",
          /* enabled */ false);

  @Override
  public String name() {
    return "suppress-warnings-STUB";
  }

  @Override
  public RuleDef rule() {
    return DISABLED_RULE;
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(CompilationUnit n, StyleContext ctx) {}
}
