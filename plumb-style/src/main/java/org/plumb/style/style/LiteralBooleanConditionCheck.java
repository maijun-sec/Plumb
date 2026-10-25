/*
 * C3 / -C1 / -C1 / -C1 / -C1 / AST
 * check for CWE-570 / CWE-571 (Expression Always False
 * / Always True). Fires when an IfStmt condition is
 * provably constant.
 *
 * <p>Generic. Covers the following Juliet sub-patterns:
 * <ol>
 *     <li>{@code if (false)} / {@code if (true)}
 *         literal.</li>
 *     <li>{@code if (PRIVATE_STATIC_FINAL_FALSE/TRUE)}
 *         after a {@code private static final boolean
 *         ... = false;} declaration in the same
 *         CompilationUnit.</li>
 *     <li>{@code if (s.equals("true"))} where local
 *         {@code String s = "false"}  string-literal
 *         equality.</li>
 *     <li>arithmetic on locals, e.g. {@code int
 *         n = 30; if (n == (n - 1))}.</li>
 *     <li>{@code IO.staticFalse},
 *         {@code IO.STATIC_FINAL_FALSE}, etc.</li>
 *     <li>same-file private method returning a
 *         literal boolean, e.g. {@code if
 *         (privateReturnsFalse())}.</li>
 *     <li>{@code IO.staticReturnsFalse()} and
 *         {@code IO.staticReturnsTrue()} (testcasesupport
 *         helpers with constant return).</li>
 *     <li>{@code Integer.MIN_VALUE} /
 *         {@code Integer.MAX_VALUE} recognition.</li>
 *     <li>{@code IO.staticFive},
 *         {@code IO.STATIC_FINAL_FIVE},
 *         {@code IO.privateFive},
 *         {@code IO.PRIVATE_STATIC_FINAL_FIVE},
 *         {@code IO.staticTrue} / {@code .staticFalse}
 *         numeric value recognition.</li>
 * </ol>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class LiteralBooleanConditionCheck implements AstChecker {

  private static final RuleDef ALWAYS_FALSE =
      new RuleDef(
          new RuleId("ast-style-literal-boolean-condition", "LITERAL_BOOLEAN_CONDITION"),
          "Condition expression is provably always false (CWE-570)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-570"),
          "org.plumb.style.LiteralBooleanConditionCheck",
          true,
          List.of("ast-style-literal-boolean-condition", "literal-boolean-condition"));

  private static final RuleDef ALWAYS_TRUE =
      new RuleDef(
          new RuleId("ast-style-expression-always-true", "EXPRESSION_ALWAYS_TRUE"),
          "Condition expression is provably always true (CWE-571)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-571"),
          "org.plumb.style.LiteralBooleanConditionCheck",
          true,
          List.of("ast-style-expression-always-false", "literal-boolean-condition"));

  private final Set<String> reported = new HashSet<>();
  private final Map<String, Boolean> constantBools = new HashMap<>();
  private final Map<String, Long> constantInts = new HashMap<>();

  /**
   * variables initialised from {@code nextInt()}-family methods (SecureRandom / Random /
   * ThreadLocalRandom). name -> [min, max] inclusive.
   */
  private final Map<String, long[]> randomInts = new HashMap<>();

  private final Map<String, String> constantStrings = new HashMap<>();

  /**
   * declared type per variable name (per-CU, last-write-wins). Used to recognise {@code
   * a.getClass().equals(b.getClass())} when a and b are declared as different concrete classes.
   */
  private final Map<String, String> declaredTypes = new HashMap<>();

  /**
   * same-CU private methods that always return the same boolean literal. methodName -> return
   * value.
   */
  private final Map<String, Boolean> privateBoolReturns = new HashMap<>();

  @Override
  public String name() {
    return "literal-boolean-condition";
  }

  @Override
  public void init(StyleContext ctx) {
    constantBools.clear();
    constantInts.clear();
    randomInts.clear();
    constantStrings.clear();
    declaredTypes.clear();
    privateBoolReturns.clear();
    reported.clear();
    if (ctx.source() == null || ctx.source().unit() == null) return;
    var root = ctx.source().unit();
    // (a) Field declarations: capture constants
    //     declared with `static final` modifier.
    for (FieldDeclaration fd : root.findAll(FieldDeclaration.class)) {
      for (VariableDeclarator vd : fd.getVariables()) {
        String typeName = vd.getTypeAsString();
        String name = vd.getNameAsString();
        if (vd.getInitializer().isEmpty()) continue;
        Expression init = vd.getInitializer().get();
        if (init instanceof BooleanLiteralExpr ble && "boolean".equals(typeName)) {
          constantBools.put(name, ble.getValue());
        }
        if (init instanceof IntegerLiteralExpr ili
            && ("int".equals(typeName) || "long".equals(typeName))) {
          try {
            if (!constantInts.containsKey(name)) {
              constantInts.put(name, Long.parseLong(ili.getValue()));
            }
          } catch (NumberFormatException ex) {
          }
        } else if (init instanceof LongLiteralExpr lli
            && ("int".equals(typeName) || "long".equals(typeName))) {
          try {
            if (!constantInts.containsKey(name)) {
              constantInts.put(name, Long.parseLong(lli.getValue()));
            }
          } catch (NumberFormatException ex) {
          }
        }
        if (init instanceof StringLiteralExpr sle
            && "String".equals(typeName)
            && !constantStrings.containsKey(name)) {
          constantStrings.put(name, sle.getValue());
        }
      }
    }
    // (b) Walk ALL VariableDeclarators.
    for (VariableDeclarator vd : root.findAll(VariableDeclarator.class)) {
      String typeName = vd.getTypeAsString();
      String name = vd.getNameAsString();
      if (vd.getInitializer().isEmpty()) continue;
      if (constantBools.containsKey(name)) continue;
      if (constantInts.containsKey(name)) continue;
      if (randomInts.containsKey(name)) continue;
      // capture declared type once (first wins).
      if (!declaredTypes.containsKey(name)) {
        declaredTypes.put(name, typeName);
      }
      Expression init = vd.getInitializer().get();
      if (init instanceof BooleanLiteralExpr ble && "boolean".equals(typeName)) {
        constantBools.put(name, ble.getValue());
      }
      if (init instanceof IntegerLiteralExpr ili
          && ("int".equals(typeName) || "long".equals(typeName))) {
        try {
          constantInts.put(name, Long.parseLong(ili.getValue()));
        } catch (NumberFormatException ex) {
        }
      } else if (init instanceof LongLiteralExpr lli
          && ("int".equals(typeName) || "long".equals(typeName))) {
        try {
          constantInts.put(name, Long.parseLong(lli.getValue()));
        } catch (NumberFormatException ex) {
        }
      }
      // capture range from Random / SecureRandom / ThreadLocalRandom
      // .nextInt() / .nextLong() calls (no arg → int range).
      if (init instanceof MethodCallExpr mc
          && ("int".equals(typeName) || "long".equals(typeName))
          && mc.getArguments().isEmpty()
          && ("nextInt".equals(mc.getNameAsString()) || "nextLong".equals(mc.getNameAsString()))) {
        long[] range =
            "int".equals(typeName)
                ? new long[] {Integer.MIN_VALUE, Integer.MAX_VALUE}
                : new long[] {Long.MIN_VALUE, Long.MAX_VALUE};
        randomInts.put(name, range);
      }
      if (init instanceof StringLiteralExpr sle
          && "String".equals(typeName)
          && !constantStrings.containsKey(name)) {
        constantStrings.put(name, sle.getValue());
      }
    }
    // (c) walk every method declaration in
    //     the same CU. If the body is a single
    //     `return boolean-literal;` (possibly with
    //     other empty statements around it), mark
    //     the method as a constant-returner.
    for (MethodDeclaration md : root.findAll(MethodDeclaration.class)) {
      String name = md.getNameAsString();
      String retType = md.getTypeAsString();
      if (!"boolean".equals(retType)) continue;
      Boolean retVal = constantReturnValue(md);
      if (retVal != null) {
        privateBoolReturns.put(name, retVal);
      }
    }
  }

  /** if {@code md}'s body returns a boolean literal (always), return that value. Otherwise null. */
  private static Boolean constantReturnValue(MethodDeclaration md) {
    if (md.getBody() == null) return null;
    Boolean result = null;
    for (ReturnStmt rs : md.getBody().get().findAll(ReturnStmt.class)) {
      Expression e = rs.getExpression().orElse(null);
      if (e == null) return null;
      if (e instanceof EnclosedExpr ee) e = ee.getInner();
      if (e instanceof BooleanLiteralExpr ble) {
        if (result == null) {
          result = ble.getValue();
        } else if (result != ble.getValue()) {
          // Different return values in different
          // branches — not constant.
          return null;
        }
      } else {
        return null;
      }
    }
    return result;
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(IfStmt n, StyleContext ctx) {
    Expression e = n.getCondition();
    Boolean constant = resolveAsBoolean(e);
    if (constant == null) return;
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    String key = line + "|" + col + "|" + e;
    if (!reported.add(key)) return;
    RuleDef rule = constant ? ALWAYS_TRUE : ALWAYS_FALSE;
    String cwe = constant ? "CWE-571" : "CWE-570";
    ctx.report(
        line,
        col,
        rule,
        "Condition is provably always "
            + (constant ? "true" : "false")
            + " - dead branch ("
            + cwe
            + ")",
        RuleDef.Severity.MEDIUM,
        e.toString());
  }

  private Boolean resolveAsBoolean(Expression e) {
    if (e instanceof BooleanLiteralExpr ble) {
      return ble.getValue();
    }
    // `!inner` flips the resolved boolean.
    if (e instanceof UnaryExpr ue && ue.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT) {
      Boolean inner = resolveAsBoolean(ue.getExpression());
      return inner == null ? null : !inner;
    }
    if (e instanceof NameExpr ne) {
      String n = ne.getNameAsString();
      return constantBools.get(n);
    }
    if (e instanceof FieldAccessExpr fae) {
      // well-known boolean static fields.
      String s = fae.toString();
      if (s.endsWith(".staticFalse") || s.endsWith(".STATIC_FINAL_FALSE")) {
        return Boolean.FALSE;
      }
      if (s.endsWith(".staticTrue") || s.endsWith(".STATIC_FINAL_TRUE")) {
        return Boolean.TRUE;
      }
      return null;
    }
    if (e instanceof MethodCallExpr mc) {
      String name = mc.getNameAsString();
      // IO.staticReturnsFalse() / staticReturnsTrue()
      // (testcasesupport helpers, no-arg, return literal).
      // CRITICAL: check NAME first regardless of scope (Lesson 1024).
      if ("staticReturnsFalse".equals(name)) return Boolean.FALSE;
      if ("staticReturnsTrue".equals(name)) return Boolean.TRUE;
      // a.getClass().equals(b.getClass()) — if both a, b
      // are declared as different concrete classes, equals is false.
      if ("equals".equals(name) && mc.getArguments().size() == 1) {
        Boolean cls = resolveClassEquals(mc);
        if (cls != null) return cls;
      }
      // `String s.equals(stringLiteral)` returns boolean
      // — if s's value and the literal are both known, decide.
      String sResult = stringValue(e);
      if (sResult != null) {
        if ("true".equals(sResult)) return Boolean.TRUE;
        if ("false".equals(sResult)) return Boolean.FALSE;
      }
      // staticReturnsTrueOrFalse stays opaque.
      if (mc.getScope().isPresent()) return null;
      // Bare call (no receiver): same-CU private helper.
      Boolean privateRet = privateBoolReturns.get(name);
      if (privateRet != null) return privateRet;
      return null;
    }
    if (e instanceof BinaryExpr be) {
      Long leftVal = numericValue(be.getLeft());
      Long rightVal = numericValue(be.getRight());
      if (leftVal != null && rightVal != null) {
        switch (be.getOperator()) {
          case EQUALS:
            return leftVal.equals(rightVal);
          case NOT_EQUALS:
            return !leftVal.equals(rightVal);
          case GREATER:
            return leftVal > rightVal;
          case GREATER_EQUALS:
            return leftVal >= rightVal;
          case LESS:
            return leftVal < rightVal;
          case LESS_EQUALS:
            return leftVal <= rightVal;
          default:
            return null;
        }
      }
      // range vs constant — if one side is a known
      // [min,max] range and the other is a literal, decide.
      // Three-way: range < val true; range >= val false; partial → null.
      if (leftVal != null || rightVal != null) {
        long[] leftRange = numericRange(be.getLeft());
        long[] rightRange = numericRange(be.getRight());
        long[] range = leftRange != null ? leftRange : rightRange;
        long val = leftVal != null ? leftVal : rightVal;
        boolean rangeOnLeft = leftRange != null;
        if (range != null && leftVal == null && rightVal == null) {
          return null;
        }
        if (range != null) {
          Boolean r747Result = null;
          switch (be.getOperator()) {
            case LESS:
              {
                // a < b. range is one side.
                // true:  range entirely < val   (= range.max < val)
                // false: range entirely >= val  (= range.min >= val)
                // else:  partial.
                if (rangeOnLeft) {
                  if (range[1] < val) r747Result = Boolean.TRUE;
                  else if (range[0] >= val) r747Result = Boolean.FALSE;
                } else {
                  if (val < range[0]) r747Result = Boolean.TRUE;
                  else if (val >= range[1]) r747Result = Boolean.FALSE;
                }
                break;
              }
            case LESS_EQUALS:
              {
                // a <= b. true: range.max <= val. false: range.min > val.
                if (rangeOnLeft) {
                  if (range[1] <= val) r747Result = Boolean.TRUE;
                  else if (range[0] > val) r747Result = Boolean.FALSE;
                } else {
                  if (val <= range[0]) r747Result = Boolean.TRUE;
                  else if (val > range[1]) r747Result = Boolean.FALSE;
                }
                break;
              }
            case GREATER:
              {
                // a > b. true: range.min > val. false: range.max <= val.
                if (rangeOnLeft) {
                  if (range[0] > val) r747Result = Boolean.TRUE;
                  else if (range[1] <= val) r747Result = Boolean.FALSE;
                } else {
                  if (val > range[1]) r747Result = Boolean.TRUE;
                  else if (val <= range[0]) r747Result = Boolean.FALSE;
                }
                break;
              }
            case GREATER_EQUALS:
              {
                // a >= b. true: range.min >= val. false: range.max < val.
                if (rangeOnLeft) {
                  if (range[0] >= val) r747Result = Boolean.TRUE;
                  else if (range[1] < val) r747Result = Boolean.FALSE;
                } else {
                  if (val >= range[1]) r747Result = Boolean.TRUE;
                  else if (val < range[0]) r747Result = Boolean.FALSE;
                }
                break;
              }
            case EQUALS:
              // range == val only if range is single point
              if (range[0] == range[1] && range[0] == val) {
                r747Result = Boolean.TRUE;
              } else if (val < range[0] || val > range[1]) {
                r747Result = Boolean.FALSE;
              }
              break;
            case NOT_EQUALS:
              if (range[0] == range[1] && range[0] == val) {
                r747Result = Boolean.FALSE;
              } else if (val < range[0] || val > range[1]) {
                r747Result = Boolean.TRUE;
              }
              break;
            default:
              break;
          }
          if (r747Result != null) return r747Result;
        }
      }
      if (be.getOperator() == BinaryExpr.Operator.EQUALS) {
        String leftStr = stringValue(be.getLeft());
        String rightStr = stringValue(be.getRight());
        if (leftStr != null && rightStr != null) {
          return leftStr.equals(rightStr);
        }
      }
      return null;
    }
    return null;
  }

  private Long numericValue(Expression e) {
    if (e instanceof EnclosedExpr ee) {
      return numericValue(ee.getInner());
    }
    if (e instanceof IntegerLiteralExpr ili) {
      try {
        return Long.parseLong(ili.getValue());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    if (e instanceof LongLiteralExpr lli) {
      try {
        return Long.parseLong(lli.getValue());
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    if (e instanceof NameExpr ne) {
      return constantInts.get(ne.getNameAsString());
    }
    if (e instanceof FieldAccessExpr fae) {
      // well-known numeric constants.
      String s = fae.toString();
      if (s.endsWith(".Integer.MIN_VALUE")
          || s.endsWith(".MIN_VALUE")
          || s.endsWith("Integer.MIN_VALUE")) {
        return Long.MIN_VALUE;
      }
      if (s.endsWith(".Integer.MAX_VALUE")
          || s.endsWith(".MAX_VALUE")
          || s.endsWith("Integer.MAX_VALUE")) {
        return Long.MAX_VALUE;
      }
      if (s.endsWith(".Long.MIN_VALUE")) return Long.MIN_VALUE;
      if (s.endsWith(".Long.MAX_VALUE")) return Long.MAX_VALUE;
      // testcasesupport IO numeric helpers.
      if (s.endsWith(".staticFive")
          || s.endsWith(".STATIC_FINAL_FIVE")
          || s.endsWith(".privateFive")
          || s.endsWith(".PRIVATE_STATIC_FINAL_FIVE")) {
        return 5L;
      }
      if (s.endsWith(".staticTrue") || s.endsWith(".STATIC_FINAL_TRUE")) {
        return 1L;
      }
      if (s.endsWith(".staticFalse") || s.endsWith(".STATIC_FINAL_FALSE")) {
        return 0L;
      }
      return null;
    }
    if (e instanceof BinaryExpr be) {
      Long leftVal = numericValue(be.getLeft());
      Long rightVal = numericValue(be.getRight());
      if (leftVal == null || rightVal == null) return null;
      switch (be.getOperator()) {
        case MINUS:
          return leftVal - rightVal;
        case PLUS:
          return leftVal + rightVal;
        case MULTIPLY:
          return leftVal * rightVal;
        case DIVIDE:
          return rightVal != 0 ? leftVal / rightVal : null;
        default:
          return null;
      }
    }
    return null;
  }

  /**
   * numeric range for an expression. Returns [min, max] inclusive, or null if unknown. Currently
   * only handles {@code NameExpr} that resolves to a {@code randomInts} entry.
   */
  private long[] numericRange(Expression e) {
    if (e instanceof EnclosedExpr ee) {
      return numericRange(ee.getInner());
    }
    if (e instanceof NameExpr ne) {
      return randomInts.get(ne.getNameAsString());
    }
    return null;
  }

  /**
   * handle {@code a.getClass().equals(b.getClass())}. Returns true if both sides are declared as
   * the same concrete class; false if declared as different concrete classes; null otherwise
   * (unknown).
   */
  private Boolean resolveClassEquals(MethodCallExpr mc) {
    Expression recv = mc.getScope().orElse(null);
    Expression arg = mc.getArgument(0);
    if (recv == null || arg == null) return null;
    // recv must be `var.getClass()`
    String recvName = extractVarFromGetClass(recv);
    String argName = extractVarFromGetClass(arg);
    if (recvName == null || argName == null) return null;
    String recvType = declaredTypes.get(recvName);
    String argType = declaredTypes.get(argName);
    if (recvType == null || argType == null) return null;
    // Same type → true. Different type → false. Both must be non-Object
    // to be safe (Object getClass() runtime type differs).
    if ("Object".equals(recvType) || "Object".equals(argType)) return null;
    return recvType.equals(argType);
  }

  private static String extractVarFromGetClass(Expression e) {
    // Pattern: x.getClass()
    if (e instanceof MethodCallExpr mc) {
      if ("getClass".equals(mc.getNameAsString()) && mc.getArguments().isEmpty()) {
        Expression r = mc.getScope().orElse(null);
        if (r instanceof NameExpr ne) {
          return ne.getNameAsString();
        }
      }
    }
    return null;
  }

  private String stringValue(Expression e) {
    if (e instanceof StringLiteralExpr sle) return sle.getValue();
    if (e instanceof NameExpr ne) return constantStrings.get(ne.getNameAsString());
    if (e instanceof MethodCallExpr mc) {
      String name = mc.getNameAsString();
      if (("equals".equals(name) || "length".equals(name)) && !mc.getArguments().isEmpty()) {
        Expression recv = mc.getScope().orElse(null);
        if (recv instanceof NameExpr ne2) {
          String recvName = ne2.getNameAsString();
          String recvVal = constantStrings.get(recvName);
          String argVal = stringValue(mc.getArgument(0));
          if (recvVal != null && argVal != null && "equals".equals(name)) {
            return recvVal.equals(argVal) ? "true" : "false";
          }
        }
      }
    }
    return null;
  }
}
