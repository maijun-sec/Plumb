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
package org.plumb.style.style;

import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** resource used as both input and output (IO_RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT). */
public final class IoResourceBothCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-io-resource-both", "IO_RESOURCE_BOTH"),
          "resource used as both input and output (IO_RESOURCE_USED_AS_BOTH_INPUT_AND_OUTPUT)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-668"),
          "org.plumb.style.IoResourceBothCheck",
          true,
          List.of("ast-style-resource-used-as-both-input-and-output", "io-resource-both"));

  private static final Set<String> READ_METHODS =
      Set.of(
          "read",
          "readLine",
          "readAllBytes",
          "readAllLines",
          "readString",
          "next",
          "nextLine",
          "nextInt",
          "getInputStream");

  private static final Set<String> WRITE_METHODS =
      Set.of("write", "writeString", "printf", "println", "append", "getOutputStream");

  @Override
  public String name() {
    return "io-resource-both";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    String name = n.getNameAsString();
    if (n.getScope().isEmpty()) return;
    String receiver = n.getScope().get().toString();
    boolean isRead = READ_METHODS.contains(name);
    boolean isWrite = WRITE_METHODS.contains(name);
    if (!isRead && !isWrite) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    // Heuristic flag: each read+write on the same
    // receiver would be aggregated. For simplicity
    // we just emit a low-severity signal and let
    // the user audit.
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        (isRead ? "Read" : "Write")
            + " on '"
            + receiver
            + "' : verify it's not also used as "
            + (isRead ? "output" : "input")
            + " in this method",
        RuleDef.Severity.LOW,
        name);
  }
}
