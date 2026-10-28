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

import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.List;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.rules.RuleParameter;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Index-counter loop should be a for-each loop.
 *
 * <p>**Parameters**
 *
 * <p>| Name | Type | Default | Description | | ---- | ---- | ------- | ----------- | |
 * `ignoreLoopsWithIndexUsedOutside` | BOOLEAN | `true` | Skip loops where i is read in non-indexing
 * ways | | `arrayLengthTolerance` | BOOLEAN | `true` | Accept arr.length as a compare operand |
 */
public final class IteratorIdiomaticCheck implements AstChecker {

  private static final RuleParameter[] PARAMS = {
    new RuleParameter(
        "ignoreLoopsWithIndexUsedOutside",
        "Skip loops where i is read in non-indexing ways",
        RuleParameter.Type.BOOLEAN,
        "true"),
    new RuleParameter(
        "arrayLengthTolerance",
        "Accept arr.length as a compare operand",
        RuleParameter.Type.BOOLEAN,
        "true"),
  };

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-iterator-idiomatic", "ITERATOR_IDIOMATIC"),
          "Index-counter loop should be a for-each loop",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.IteratorIdiomaticCheck",
          true,
          List.of("ast-style-iterator-idiomatic", "iterator-idiomatic"),
          List.of(PARAMS));

  @Override
  public String name() {
    return "iterator-idiomatic";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ForStmt n, StyleContext ctx) {
    if (!paramBool(ctx, "ignoreLoopsWithIndexUsedOutside", true)) {
      // Still fires (the param is a misnamed enable; we keep
      // its name for back-compat but interpret it as the
      // conservative default).
    }
    // 1) init: a single int var, value 0
    if (n.getInitialization().size() != 1) return;
    if (!(n.getInitialization().get(0) instanceof VariableDeclarationExpr init)) return;
    if (init.getVariables().size() != 1) return;
    var varDecl = init.getVariables().get(0);
    String idxName = varDecl.getNameAsString();
    String initVal = varDecl.getInitializer().map(e -> e.toString()).orElse("");
    if (!initVal.equals("0")) return;
    // 2) compare: BinaryExpr with i on the left and (something) on the right
    if (!(n.getCompare().isPresent())) return;
    if (!(n.getCompare().get() instanceof BinaryExpr cmp)) return;
    if (cmp.getOperator() != BinaryExpr.Operator.LESS) return;
    if (!(cmp.getLeft() instanceof NameExpr leftName)) return;
    if (!leftName.getNameAsString().equals(idxName)) return;
    // The right side must be a method call like foo.size() OR (with
    // arrayLengthTolerance) a field access on an array.
    Expression right = cmp.getRight();
    String iterableName = null;
    boolean sizeIsMethod = false;
    if (right instanceof MethodCallExpr mc
        && mc.getArguments().isEmpty()
        && mc.getNameAsString().equals("size")) {
      // expect mc.getScope() to be a NameExpr like `list`
      if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr sc) {
        iterableName = sc.getNameAsString();
        sizeIsMethod = true;
      } else {
        return;
      }
    } else if (paramBool(ctx, "arrayLengthTolerance", true)
        && right instanceof FieldAccessExpr fa
        && fa.getNameAsString().equals("length")) {
      // arr.length : extract `arr` name
      if (fa.getScope() instanceof NameExpr sc) {
        iterableName = sc.getNameAsString();
      } else {
        return;
      }
    } else {
      return;
    }
    // 3) update: i++ or ++i
    if (n.getUpdate().size() != 1) return;
    if (!(n.getUpdate().get(0) instanceof UnaryExpr u)) return;
    if (u.getOperator() != UnaryExpr.Operator.POSTFIX_INCREMENT
        && u.getOperator() != UnaryExpr.Operator.PREFIX_INCREMENT) return;
    if (!(u.getExpression() instanceof NameExpr un) || !un.getNameAsString().equals(idxName))
      return;
    // 4) body must contain iterableName.get(i) or iterableName[i]
    // AND must NOT contain other uses of `idxName` outside
    // such index positions.
    Statement body = n.getBody();
    boolean sawIndexing = false;
    boolean sawOtherIdxUse = false;
    for (NameExpr ni : body.findAll(NameExpr.class)) {
      if (!ni.getNameAsString().equals(idxName)) continue;
      // The parent of this NameExpr in the body should be an
      // ArrayAccessExpr or the argument of a MethodCallExpr.get(...).
      var parent = ni.getParentNode().orElse(null);
      if (parent instanceof ArrayAccessExpr) {
        sawIndexing = true;
        // The LHS of the array access should be iterableName.
        ArrayAccessExpr ae = (ArrayAccessExpr) parent;
        if (ae.getName() instanceof NameExpr arrName
            && arrName.getNameAsString().equals(iterableName)) {
          // still good
        } else {
          sawOtherIdxUse = true;
        }
      } else if (parent instanceof MethodCallExpr getmc
          && getmc.getNameAsString().equals("get")
          && getmc.getArguments().size() == 1
          && getmc.getArguments().get(0) == ni) {
        sawIndexing = true;
        if (getmc.getScope().isPresent()
            && getmc.getScope().get() instanceof NameExpr sc
            && sc.getNameAsString().equals(iterableName)) {
          // still good
        } else {
          sawOtherIdxUse = true;
        }
      } else if (parent instanceof UnaryExpr ue
          && (ue.getOperator() == UnaryExpr.Operator.POSTFIX_INCREMENT
              || ue.getOperator() == UnaryExpr.Operator.PREFIX_INCREMENT)) {
        // Another ++i or i++ inside the body : still an index
        // advancement, not a real "other use".
        sawOtherIdxUse = true; // conservative
      } else {
        sawOtherIdxUse = true;
      }
    }
    if (sawIndexing && !sawOtherIdxUse) {
      int line = n.getBegin().map(p -> p.line).orElse(-1);
      int col = n.getBegin().map(p -> p.column).orElse(-1);
      String msg =
          sizeIsMethod
              ? "for-loop over '" + iterableName + "' should be enhanced for-loop"
              : "for-loop over array '" + iterableName + "' should be enhanced for-loop";
      ctx.report(line, col, DEFAULT_RULE, msg, RuleDef.Severity.LOW, iterableName);
    }
  }

  // Local helper for parameter lookup.
  private static boolean paramBool(StyleContext ctx, String name, boolean def) {
    Boolean override = ctx.paramOverride(name, Boolean.class);
    if (override != null) return override;
    for (RuleParameter p : PARAMS) {
      if (p.name().equals(name)) return p.boolDefault();
    }
    return def;
  }
}
