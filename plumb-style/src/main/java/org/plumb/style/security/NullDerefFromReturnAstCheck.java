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
package org.plumb.style.security;

// This checker is necessarily two-phase: init() walks the CU to build the
// inter-proc map (which method params are dereferenced without null check),
// then visit(MethodDeclaration) consumes that map for the call-site check.

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** AST-level CWE-690 (NULL Pointer Dereference From Return) checker. */
public final class NullDerefFromReturnAstCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-null-deref-from-return-ast", "NULL_DEREF_FROM_RETURN_AST"),
          "Variable assigned from method result is dereferenced without null check (CWE-690)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-690"),
          "org.plumb.style.security.NullDerefFromReturnAstCheck",
          true,
          List.of("ast-security-null-deref-from-return", "null-deref-from-return-ast"));

  private final Set<String> reported = new HashSet<>();
  private final Map<String, AssignRecord> assigns = new HashMap<>();
  private final Map<String, UseRecord> uses = new HashMap<>();
  private final List<Statement> allStmts = new ArrayList<>();
  private final Map<Integer, Statement> lineToStmt = new HashMap<>();
  private final Map<String, Set<String>> interprocUnsafeParams = new HashMap<>();
  private final Map<String, List<Parameter>> interprocMethodParams = new HashMap<>();

  private static class AssignRecord {
    final int line;
    final Statement stmt;

    /**
     * the variable this one was copied from, or {@code null} when the value came from anywhere else
     * (a call result, a container lookup, an unknown expression).
     *
     * <p>Only a syntactically explicit reference copy sets this. It is what lets a null check on
     * {@code a} be honoured for a dereference of {@code b} when the code says {@code b = a}: the
     * two names denote one object, so the guard holds for both.
     */
    final String copiedFrom;

    AssignRecord(int line, Statement stmt) {
      this(line, stmt, null);
    }

    AssignRecord(int line, Statement stmt, String copiedFrom) {
      this.line = line;
      this.stmt = stmt;
      this.copiedFrom = copiedFrom;
    }
  }

  private static class UseRecord {
    final int line;
    final Statement stmt;

    UseRecord(int line, Statement stmt) {
      this.line = line;
      this.stmt = stmt;
    }
  }

  @Override
  public String name() {
    return "null-deref-from-return-ast";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
    assigns.clear();
    uses.clear();
    allStmts.clear();
    lineToStmt.clear();
    interprocUnsafeParams.clear();
    interprocMethodParams.clear();
    // pre-pass: walk every method declaration, record
    // params and identify which params are dereferenced
    // without a null check (the "unsafe" set per method).
    com.github.javaparser.ast.CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      unit.walk(
          MethodDeclaration.class,
          md -> {
            String name = md.getNameAsString();
            List<Parameter> params = md.getParameters();
            interprocMethodParams.put(name, params);
            Set<String> unsafe = new HashSet<>();
            for (Parameter p : params) {
              if (isParamUnsafelyDereferenced(md, p.getNameAsString())) {
                unsafe.add(p.getNameAsString());
              }
            }
            if (!unsafe.isEmpty()) {
              interprocUnsafeParams.put(name, unsafe);
            }
          });
    }
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody() == null) return;
    assigns.clear();
    uses.clear();
    allStmts.clear();
    lineToStmt.clear();
    n.walk(
        com.github.javaparser.ast.Node.class,
        node -> {
          if (node instanceof BlockStmt block) {
            for (Statement s : block.getStatements()) {
              int line = s.getBegin().map(p -> p.line).orElse(-1);
              if (line > 0) {
                allStmts.add(s);
                lineToStmt.put(line, s);
              }
            }
          } else if (node instanceof SwitchEntry se) {
            for (Statement s : se.getStatements()) {
              int line = s.getBegin().map(p -> p.line).orElse(-1);
              if (line > 0) {
                allStmts.add(s);
                lineToStmt.put(line, s);
              }
            }
          }
        });
    for (Statement s : allStmts) collectAssignment(s);
    for (Statement s : allStmts) collectDereference(s, ctx);
    for (Statement s : allStmts) {
      for (MethodCallExpr mc : s.findAll(MethodCallExpr.class)) {
        if (mc.getScope().isPresent()) continue;
        String calleeName = mc.getNameAsString();
        Set<String> unsafe = interprocUnsafeParams.get(calleeName);
        if (unsafe == null || unsafe.isEmpty()) continue;
        List<Parameter> calleeParams = interprocMethodParams.get(calleeName);
        if (calleeParams == null) continue;
        for (int i = 0; i < mc.getArguments().size() && i < calleeParams.size(); i++) {
          Expression arg = mc.getArgument(i);
          String paramName = calleeParams.get(i).getNameAsString();
          if (!unsafe.contains(paramName)) continue;
          if (!(arg instanceof NameExpr ne)) continue;
          String argName = ne.getNameAsString();
          AssignRecord ar = assigns.get(argName);
          if (ar == null) continue;
          int callLine = mc.getBegin().map(p -> p.line).orElse(-1);
          if (callLine <= 0) continue;
          String key = callLine + "|" + argName + "|nullDerefInterproc|" + calleeName;
          if (reported.add(key)) {
            ctx.report(
                callLine,
                -1,
                RULE,
                "Variable `"
                    + argName
                    + "` is passed to `"
                    + calleeName
                    + "` which dereferences its `"
                    + paramName
                    + "` parameter without a null check (CWE-690).",
                RuleDef.Severity.MEDIUM,
                argName + " -> " + calleeName + " at line " + callLine);
          }
        }
      }
    }
  }

  private void collectAssignment(Statement s) {
    if (!(s instanceof ExpressionStmt es)) return;
    if (es.getExpression() instanceof VariableDeclarationExpr vde) {
      for (VariableDeclarator vd : vde.getVariables()) {
        if (!vd.getInitializer().isPresent()) continue;
        Expression init = vd.getInitializer().get();
        if (!isMethodResultOrUnknown(init)) continue;
        String name = vd.getNameAsString();
        int line = s.getBegin().map(p -> p.line).orElse(-1);
        assigns.put(name, new AssignRecord(line, s, copiedFromVar(init)));
      }
    }
    if (es.getExpression() instanceof AssignExpr ae
        && ae.getOperator() == AssignExpr.Operator.ASSIGN
        && ae.getTarget() instanceof NameExpr tgt
        && isMethodResultOrUnknown(ae.getValue())) {
      String n = tgt.getNameAsString();
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      assigns.put(n, new AssignRecord(line, s, copiedFromVar(ae.getValue())));
    }
  }

  /**
   * the variable name an expression copies a reference from, or {@code null}.
   *
   * <p>Deliberately restricted to a bare {@link NameExpr}. Anything else -- a method call, a field
   * access, a container lookup -- is a value whose provenance this rule cannot establish, and
   * treating it as equivalent to its source would suppress findings on genuinely different objects.
   */
  private static String copiedFromVar(Expression e) {
    if (e instanceof NameExpr ne) return ne.getNameAsString();
    return null;
  }

  private void collectDereference(Statement s, StyleContext ctx) {
    for (MethodCallExpr mc : s.findAll(MethodCallExpr.class)) {
      if (!mc.getScope().isPresent()) continue;
      Expression scope = mc.getScope().get();
      if (!(scope instanceof NameExpr ne)) continue;
      String name = ne.getNameAsString();
      AssignRecord ar = assigns.get(name);
      if (ar == null) continue;
      int useLine = mc.getBegin().map(p -> p.line).orElse(-1);
      if (useLine <= ar.line) continue;
      if (hasNullCheckBetween(ar.line, useLine, name)) continue;
      fire(useLine, ctx, name);
      return;
    }
    for (FieldAccessExpr fae : s.findAll(FieldAccessExpr.class)) {
      if (!(fae.getScope() instanceof NameExpr ne)) continue;
      String name = ne.getNameAsString();
      AssignRecord ar = assigns.get(name);
      if (ar == null) continue;
      int useLine = fae.getBegin().map(p -> p.line).orElse(-1);
      if (useLine <= ar.line) continue;
      if (hasNullCheckBetween(ar.line, useLine, name)) continue;
      fire(useLine, ctx, name);
      return;
    }
    for (ArrayAccessExpr aae : s.findAll(ArrayAccessExpr.class)) {
      if (!(aae.getName() instanceof NameExpr ne)) continue;
      String name = ne.getNameAsString();
      AssignRecord ar = assigns.get(name);
      if (ar == null) continue;
      int useLine = aae.getBegin().map(p -> p.line).orElse(-1);
      if (useLine <= ar.line) continue;
      if (hasNullCheckBetween(ar.line, useLine, name)) continue;
      fire(useLine, ctx, name);
      return;
    }
  }

  /**
   * is the dereference guarded by a check on {@code varName} or on any name it was copied from?
   *
   * <p>A null check constrains the <em>object</em>, not the name. When the code says {@code b = a},
   * a check on {@code a} between the assignment and the use covers {@code b} as well, because both
   * names denote the same reference. Accepting only the exact name was a name check standing in for
   * a value check, and it reported the safest and most idiomatic Java shape there is:
   *
   * <pre>
   *   StringBuilder a = make(input);
   *   StringBuilder b = a;
   *   if (a == null) return;
   *   b.length();                 // reported, and there is nothing a user can do about it
   * </pre>
   *
   * <p>The chain is walked through {@link AssignRecord#copiedFrom}, which is set only for bare
   * reference copies, and it is bounded so a pathological self-assignment cannot loop.
   */
  private boolean hasNullCheckBetween(int assignLine, int useLine, String varName) {
    for (String candidate : guardCandidates(varName)) {
      for (Statement s : allStmts) {
        int line = s.getBegin().map(p -> p.line).orElse(-1);
        if (line <= assignLine) continue;
        if (line >= useLine) continue;
        if (isInsideOpaqueIfBranch(s)) continue;
        if (containsNullCheck(s, candidate)) return true;
      }
    }
    return false;
  }

  /**
   * {@code varName} plus the transitive chain of variables it was copied from.
   *
   * <p>The variable itself comes first so a check on it still wins, keeping the original behaviour
   * intact for the common case. Bounded by {@link #COPY_CHAIN_LIMIT} and by refusing to revisit a
   * name, so {@code a = a} or a cycle terminates.
   */
  private java.util.LinkedHashSet<String> guardCandidates(String varName) {
    java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
    String current = varName;
    for (int hops = 0; current != null && hops < COPY_CHAIN_LIMIT; hops++) {
      if (!out.add(current)) break;
      AssignRecord ar = assigns.get(current);
      current = ar == null ? null : ar.copiedFrom;
    }
    return out;
  }

  /**
   * how far to follow a copy chain.
   *
   * <p>Well above any hand-written chain and far below anything a cyclic program could use to spin.
   * The cycle guard in {@link #guardCandidates} is the real termination proof; this only bounds the
   * work for a pathological chain of distinct names.
   */
  private static final int COPY_CHAIN_LIMIT = 32;

  private static boolean isInsideOpaqueIfBranch(Statement s) {
    com.github.javaparser.ast.Node parent = s.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof IfStmt ifs) {
        if (isOpaqueGuard(ifs.getCondition())) return true;
        return false;
      }
      parent = parent.getParentNode().orElse(null);
    }
    return false;
  }

  private static boolean isOpaqueGuard(Expression e) {
    if (!(e instanceof MethodCallExpr mc)) return false;
    if (!"staticReturnsTrueOrFalse".equals(mc.getNameAsString())) return false;
    return mc.getArguments().isEmpty();
  }

  private static boolean containsNullCheck(Statement s, String varName) {
    for (IfStmt is : s.findAll(IfStmt.class)) {
      if (isNullCheck(is.getCondition(), varName)) return true;
    }
    for (MethodCallExpr mc : s.findAll(MethodCallExpr.class)) {
      String n = mc.getNameAsString();
      if (!("nonNull".equals(n) || "requireNonNull".equals(n))) continue;
      if (mc.getArguments().isEmpty()) continue;
      Expression arg = mc.getArgument(0);
      if (arg instanceof NameExpr ne && varName.equals(ne.getNameAsString())) {
        return true;
      }
    }
    return false;
  }

  private static boolean isNullCheck(Expression e, String varName) {
    if (!(e instanceof BinaryExpr be)) return false;
    if (be.getOperator() != BinaryExpr.Operator.NOT_EQUALS
        && be.getOperator() != BinaryExpr.Operator.EQUALS) return false;
    if (isVarAndNull(be.getLeft(), be.getRight(), varName)) return true;
    if (isVarAndNull(be.getRight(), be.getLeft(), varName)) return true;
    return isNullCheck(be.getLeft(), varName) || isNullCheck(be.getRight(), varName);
  }

  private static boolean isVarAndNull(Expression a, Expression b, String varName) {
    return a instanceof NameExpr ne
        && varName.equals(ne.getNameAsString())
        && b instanceof NullLiteralExpr;
  }

  private static boolean isMethodResultOrUnknown(Expression e) {
    if (e instanceof NullLiteralExpr) return false;
    if (e instanceof LiteralExpr) return false;
    if (e instanceof ArrayCreationExpr) return false;
    if (e instanceof ArrayAccessExpr) return false;
    if (e instanceof BinaryExpr) return false;
    if (e instanceof UnaryExpr) return false;
    if (e instanceof ConditionalExpr) return false;
    if (e instanceof CastExpr) return false;
    if (e instanceof ObjectCreationExpr) return false;
    return true;
  }

  private static boolean isParamUnsafelyDereferenced(MethodDeclaration md, String paramName) {
    if (md.getBody() == null) return false;
    final int[] firstDeref = {Integer.MAX_VALUE};
    final int[] firstNullCheck = {Integer.MAX_VALUE};
    md.walk(
        com.github.javaparser.ast.Node.class,
        n -> {
          if (firstNullCheck[0] <= firstDeref[0]) return;
          int line = n.getBegin().map(p -> p.line).orElse(-1);
          if (line <= 0) return;
          if (n instanceof MethodCallExpr mc && mc.getScope().isPresent()) {
            Expression scope = mc.getScope().get();
            if (scope instanceof NameExpr ne && paramName.equals(ne.getNameAsString())) {
              if (line < firstDeref[0]) firstDeref[0] = line;
            }
          } else if (n instanceof FieldAccessExpr fae) {
            if (fae.getScope() instanceof NameExpr ne && paramName.equals(ne.getNameAsString())) {
              if (line < firstDeref[0]) firstDeref[0] = line;
            }
          } else if (n instanceof IfStmt is && isNullCheck(is.getCondition(), paramName)) {
            if (line < firstNullCheck[0]) firstNullCheck[0] = line;
          }
        });
    if (firstDeref[0] == Integer.MAX_VALUE) return false;
    return firstNullCheck[0] >= firstDeref[0];
  }

  private void fire(int line, StyleContext ctx, String varName) {
    String key = line + "|" + varName + "|nullDeref";
    if (reported.add(key)) {
      ctx.report(
          line,
          -1,
          RULE,
          "Variable `"
              + varName
              + "` is dereferenced without a null check after assignment from a method call"
              + " (CWE-690).",
          RuleDef.Severity.MEDIUM,
          varName);
    }
  }
}
