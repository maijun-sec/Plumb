package org.plumb.style.style;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** CWE-690 (NULL Pointer Dereference From Return). */
public final class NullDerefFromReturnCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-null-deref-from-return", "NULL_DEREF_FROM_RETURN"),
          "Return value used without null check (CWE-690).",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-690"),
          "org.plumb.style.NullDerefFromReturnCheck",
          true,
          List.of("ast-style-null-deref-from-return-style", "null-deref-from-return"));

  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "null-deref-from-return";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
  }

  @Override
  public void visit(MethodDeclaration m, StyleContext ctx) {
    BlockStmt body = m.getBody().orElse(null);
    if (body == null) return;
    final java.util.Map<String, Integer> declared = new java.util.LinkedHashMap<>();
    final java.util.Set<String> nullChecked = new java.util.HashSet<>();
    final java.util.Set<String> notNull = new java.util.HashSet<>();
    checkBody(ctx, body.getStatements(), declared, nullChecked, notNull);
  }

  private void checkBody(
      StyleContext ctx,
      java.util.List<Statement> stmts,
      java.util.Map<String, Integer> declared,
      java.util.Set<String> nullChecked,
      java.util.Set<String> notNull) {
    for (Statement s : stmts) {
      if (s instanceof ExpressionStmt es) {
        Expression e = es.getExpression();
        handleExpression(ctx, e, declared, nullChecked, notNull);
      } else if (s instanceof IfStmt ifs) {
        Expression cond = ifs.getCondition();
        if (isNullCheck(cond)) {
          String varName = nullCheckVar(cond);
          if (varName != null) nullChecked.add(varName);
        }
        if (isEarlyReturnOnNull(cond, ifs.getThenStmt())) {
          String varName = nullCheckVar(cond);
          if (varName != null) nullChecked.add(varName);
        }
        checkBody(
            ctx,
            ifs.getThenStmt() instanceof BlockStmt bs
                ? bs.getStatements()
                : java.util.List.of(ifs.getThenStmt()),
            declared,
            nullChecked,
            notNull);
        if (ifs.getElseStmt().isPresent()) {
          Statement elseStmt = ifs.getElseStmt().get();
          checkBody(
              ctx,
              elseStmt instanceof BlockStmt bs ? bs.getStatements() : java.util.List.of(elseStmt),
              declared,
              nullChecked,
              notNull);
        }
      } else if (s instanceof BlockStmt bs) {
        checkBody(ctx, bs.getStatements(), declared, nullChecked, notNull);
      }
    }
  }

  private void handleExpression(
      StyleContext ctx,
      Expression e,
      java.util.Map<String, Integer> declared,
      java.util.Set<String> nullChecked,
      java.util.Set<String> notNull) {
    if (e == null) return;
    if (e instanceof VariableDeclarationExpr vde) {
      for (VariableDeclarator v : vde.getVariables()) {
        declared.put(v.getNameAsString(), v.getBegin().map(p -> p.line).orElse(-1));
        nullChecked.remove(v.getNameAsString());
        v.getInitializer()
            .ifPresent(init -> recordNonNullIfApplicable(ctx, init, v.getNameAsString(), notNull));
        v.getInitializer()
            .ifPresent(init -> checkForVarUse(ctx, init, declared, nullChecked, notNull));
      }
      return;
    }
    if (e instanceof AssignExpr ae) {
      Expression target = ae.getTarget();
      if (target instanceof NameExpr ne) {
        declared.put(ne.getNameAsString(), target.getBegin().map(p -> p.line).orElse(-1));
        nullChecked.remove(ne.getNameAsString());
        recordNonNullIfApplicable(ctx, ae.getValue(), ne.getNameAsString(), notNull);
      }
      checkForVarUse(ctx, ae.getValue(), declared, nullChecked, notNull);
      return;
    }
    if (e instanceof MethodCallExpr mc) {
      checkForVarUse(ctx, mc, declared, nullChecked, notNull);
    }
  }

  private void recordNonNullIfApplicable(
      StyleContext ctx, Expression init, String varName, java.util.Set<String> notNull) {
    if (init == null || varName == null) return;
    if (init instanceof ObjectCreationExpr) {
      notNull.add(varName);
      return;
    }
    if (init instanceof StringLiteralExpr) {
      notNull.add(varName);
      return;
    }
    if (init.isBooleanLiteralExpr()
        || init.isIntegerLiteralExpr()
        || init.isLongLiteralExpr()
        || init.isDoubleLiteralExpr()
        || init.isCharLiteralExpr()) {
      notNull.add(varName);
      return;
    }
    if (init instanceof ConditionalExpr ce) {
      if (isNullCheck(ce.getCondition())) {
        String checked = nullCheckVar(ce.getCondition());
        if (varName.equals(checked)
            && ce.getElseExpr() instanceof NameExpr elseName
            && varName.equals(elseName.getNameAsString())) {
          notNull.add(varName);
        }
      }
      return;
    }
    if (init instanceof MethodCallExpr mc && ctx != null) {
      String calleeKey = staticHelperKey(mc);
      if (calleeKey != null) {
        int hash = calleeKey.indexOf('#');
        if (hash > 0) {
          String fqcn = calleeKey.substring(0, hash);
          String methodName = calleeKey.substring(hash + 1);
          com.github.javaparser.ast.body.MethodDeclaration callee =
              ctx.lookupStaticMethod(fqcn, methodName);
          if (callee != null && calleeNonNullReturning(callee, notNull, varName)) {
            notNull.add(varName);
          }
        }
      }
    }
  }

  private static String staticHelperKey(MethodCallExpr mc) {
    Expression scope = mc.getScope().orElse(null);
    if (!(scope instanceof NameExpr)) return null;
    String scopeName = ((NameExpr) scope).getNameAsString();
    String[] parts = scopeName.split("\\.");
    if (parts.length < 1) return null;
    return scopeName + "#" + mc.getNameAsString();
  }

  private static boolean calleeNonNullReturning(
      com.github.javaparser.ast.body.MethodDeclaration callee,
      java.util.Set<String> notNull,
      String varName) {
    if (callee.getBody().isEmpty()) return false;
    com.github.javaparser.ast.stmt.BlockStmt body = callee.getBody().get();
    boolean hasUnambiguousReturn = false;
    boolean hasAmbiguousReturn = false;
    for (com.github.javaparser.ast.stmt.Statement s : body.getStatements()) {
      if (s instanceof com.github.javaparser.ast.stmt.ReturnStmt rs) {
        Expression returned = rs.getExpression().orElse(null);
        if (returned == null) return false;
        if (isUnambiguouslyNonNull(returned)) {
          hasUnambiguousReturn = true;
        } else {
          hasAmbiguousReturn = true;
        }
      }
    }
    if (hasAmbiguousReturn) return false;
    return hasUnambiguousReturn;
  }

  private static boolean isUnambiguouslyNonNull(Expression e) {
    if (e == null) return false;
    if (e instanceof ObjectCreationExpr) return true;
    if (e instanceof StringLiteralExpr) return true;
    if (e.isBooleanLiteralExpr()
        || e.isIntegerLiteralExpr()
        || e.isLongLiteralExpr()
        || e.isDoubleLiteralExpr()
        || e.isCharLiteralExpr()) return true;
    if (e instanceof ConditionalExpr ce) {
      if (isNullCheckStatic(ce.getCondition())) {
        String checked = nullCheckVarStatic(ce.getCondition());
        if (checked != null
            && ce.getElseExpr() instanceof NameExpr elseName
            && checked.equals(elseName.getNameAsString())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isNullCheckStatic(Expression e) {
    if (e == null) return false;
    com.github.javaparser.ast.expr.BinaryExpr unwrapped = unwrapEnclosedStatic(e);
    if (unwrapped == null) return false;
    if (unwrapped.getOperator() != com.github.javaparser.ast.expr.BinaryExpr.Operator.EQUALS
        && unwrapped.getOperator()
            != com.github.javaparser.ast.expr.BinaryExpr.Operator.NOT_EQUALS) {
      return false;
    }
    return unwrapped.getLeft() instanceof NameExpr || unwrapped.getRight() instanceof NameExpr;
  }

  private static String nullCheckVarStatic(Expression e) {
    if (e == null) return null;
    com.github.javaparser.ast.expr.BinaryExpr unwrapped = unwrapEnclosedStatic(e);
    if (unwrapped == null) return null;
    com.github.javaparser.ast.expr.Expression nameSide =
        unwrapped.getLeft() instanceof NameExpr ? unwrapped.getLeft() : unwrapped.getRight();
    return ((NameExpr) nameSide).getNameAsString();
  }

  private static com.github.javaparser.ast.expr.BinaryExpr unwrapEnclosedStatic(Expression e) {
    while (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      e = ee.getInner();
    }
    return e instanceof com.github.javaparser.ast.expr.BinaryExpr be ? be : null;
  }

  private void checkForVarUse(
      StyleContext ctx,
      Expression e,
      java.util.Map<String, Integer> declared,
      java.util.Set<String> nullChecked,
      java.util.Set<String> notNull) {
    if (e == null) return;
    if (e instanceof MethodCallExpr mc) {
      if (mc.getScope().isPresent() && mc.getScope().get() instanceof NameExpr ne) {
        String varName = ne.getNameAsString();
        if (declared.containsKey(varName)
            && !nullChecked.contains(varName)
            && !notNull.contains(varName)) {
          fire(ctx, varName, mc);
        }
      }
    }
  }

  private static boolean isNullCheck(Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      e = ee.getInner();
      if (e == null) return false;
    }
    if (!(e instanceof BinaryExpr be)) return false;
    if (be.getOperator() != BinaryExpr.Operator.NOT_EQUALS
        && be.getOperator() != BinaryExpr.Operator.EQUALS) return false;
    return (be.getRight() instanceof NullLiteralExpr) || (be.getLeft() instanceof NullLiteralExpr);
  }

  private static String nullCheckVar(Expression e) {
    if (e instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      e = ee.getInner();
      if (e == null) return null;
    }
    if (!(e instanceof BinaryExpr be)) return null;
    Expression other = be.getLeft() instanceof NullLiteralExpr ? be.getRight() : be.getLeft();
    if (other instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }

  private static boolean isEarlyReturnOnNull(Expression cond, Statement thenStmt) {
    if (cond == null || thenStmt == null) return false;
    if (cond instanceof com.github.javaparser.ast.expr.EnclosedExpr ee) {
      cond = ee.getInner();
      if (cond == null) return false;
    }
    if (!isNullCheck(cond)) return false;
    if (!(cond instanceof BinaryExpr be) || be.getOperator() != BinaryExpr.Operator.EQUALS) {
      return false;
    }
    return containsReturnOrThrow(thenStmt);
  }

  private static boolean containsReturnOrThrow(Statement s) {
    if (s == null) return false;
    if (s instanceof com.github.javaparser.ast.stmt.ReturnStmt) return true;
    if (s instanceof com.github.javaparser.ast.stmt.ThrowStmt) return true;
    if (s instanceof BlockStmt bs) {
      for (Statement child : bs.getStatements()) {
        if (containsReturnOrThrow(child)) return true;
      }
    }
    return false;
  }

  private void fire(StyleContext ctx, String varName, MethodCallExpr mc) {
    int line = mc.getBegin().map(p -> p.line).orElse(-1);
    String key = "cwe690|" + varName + "|" + line;
    if (reported.add(key)) {
      ctx.report(
          line,
          -1,
          DEFAULT_RULE,
          "Variable `"
              + varName
              + "` is dereferenced at line "
              + line
              + " without a null check after assignment from a method call (CWE-690).",
          RuleDef.Severity.MEDIUM,
          varName);
    }
  }
}
