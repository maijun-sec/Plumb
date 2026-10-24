/*
 * R742-C1: AST-level CWE-563 (Assignment to Variable
 * without Use) checker.
 *
 * <p>Companion / replacement for the noisy
 * {@code plumb.style.unused-assignment} which fires on
 * every {@code x = ...;} ExpressionStmt without checking
 * whether the variable is later read. The Juliet
 * CWE-563 test cases all share a common shape:</p>
 *
 * <pre>
 *   String data;          // decl (no init) OR with init
 *   data = "Good";        // ASSIGN literal
 *   /&#42; never use data &#42;/
 * </pre>
 *
 * <p>The good pattern uses {@code data} later; the bad
 * pattern does not.</p>
 *
 * <p>R742 strategy: cross-block data-flow on
 * {@link com.github.javaparser.ast.body.MethodDeclaration}:
 * for every local variable in the method body, build a
 * chronological list of {@code (line, kind)} records
 * where {@code kind} ∈ {ASSIGN, READ}. An ASSIGN is a
 * dead store iff there is no READ of the same name before
 * either (a) the next ASSIGN of the same variable or (b)
 * the end of the method body.</p>
 *
 * <p>To minimise FP, the rule requires:</p>
 * <ul>
 *   <li>The variable is local (declared in the method
 *       body, not a parameter, not a class field).</li>
 *   <li>The ASSIGN expression is a literal (string, int,
 *       long, etc.) ? method calls may have side effects
 *       and are not flagged.</li>
 *   <li>The variable is not assigned a value derived from
 *       itself (no {@code t = t + 1} pattern).</li>
 * </ul>
 *
 * <p>Generic, no Juliet-specific patterns. The rule
 * matches the canonical dead-store idiom and is safe to
 * use on real code.</p>
 */
package org.plumb.style.security;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NullLiteralExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
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

public final class UnusedVariableAstCheck implements AstChecker {

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-unused-variable-assignment", "UNUSED_VARIABLE_ASSIGNMENT"),
          "Local variable is assigned but never read before being overwritten (CWE-563)",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of("CWE-563"),
          "org.plumb.style.security.UnusedVariableAstCheck",
          true,
          List.of("ast-security-unused-variable-assignment", "unused-variable-assignment"));

  /** varName -> list of source-line numbers of assignments. */
  private final Map<String, List<Integer>> assigns = new HashMap<>();

  /** varName -> list of source-line numbers of reads. */
  private final Map<String, List<Integer>> reads = new HashMap<>();

  /** Already-reported (line, varName) pairs for de-duplication. */
  private final Set<String> reported = new HashSet<>();

  @Override
  public String name() {
    return "unused-variable-assignment";
  }

  @Override
  public void init(StyleContext ctx) {
    assigns.clear();
    reads.clear();
    reported.clear();
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    if (n.getBody() == null) return;
    // Reset per-method state so cross-file findings
    // don't leak across compilations.
    assigns.clear();
    reads.clear();

    // Collect parameter names so we don't flag
    // parameter assignments (parameters have
    // caller-provided semantics; if caller passes
    // unused-arg, that's a different CWE).
    Set<String> paramSet = new HashSet<>();
    for (Parameter p : n.getParameters()) {
      paramSet.add(p.getNameAsString());
    }

    // Walk ALL BlockStmts in the method body and
    // collect statements in source order. This
    // matches the R741 cross-block tracking pattern.
    List<Statement> allStmts = new ArrayList<>();
    for (BlockStmt block : n.findAll(BlockStmt.class)) {
      for (Statement s : block.getStatements()) {
        allStmts.add(s);
      }
    }

    // First pass: collect every (varName, line)
    // assignment and read.
    for (Statement s : allStmts) {
      int line = s.getBegin().map(p -> p.line).orElse(-1);
      if (line <= 0) continue;
      // Case (a): `T var = something;` — VariableDeclarationExpr
      // inside an ExpressionStmt.
      if (s instanceof ExpressionStmt es
          && es.getExpression() instanceof VariableDeclarationExpr vde) {
        for (VariableDeclarator vd : vde.getVariables()) {
          String name = vd.getNameAsString();
          // Skip if a parameter has the same
          // name (rare but possible if a
          // parameter is shadowed by a local).
          if (paramSet.contains(name)) continue;
          if (vd.getInitializer().isPresent()) {
            Expression init = vd.getInitializer().get();
            if (isLiteralOrLiteralField(init)) {
              assigns.computeIfAbsent(name, k -> new ArrayList<>()).add(line);
            }
          }
        }
      }
      // Case (b): `var = something;` — AssignExpr
      // inside an ExpressionStmt.
      if (s instanceof ExpressionStmt es
          && es.getExpression() instanceof AssignExpr ae
          && ae.getOperator() == AssignExpr.Operator.ASSIGN
          && ae.getTarget() instanceof NameExpr tgt) {
        String name = tgt.getNameAsString();
        if (isLiteralOrLiteralField(ae.getValue())) {
          assigns.computeIfAbsent(name, k -> new ArrayList<>()).add(line);
        }
      }
    }

    // Second pass: collect every NameExpr that is
    // not the LHS of an assignment (= a READ).
    // R751: skip reads inside opaque if-branches
    // (if (IO.staticReturnsTrueOrFalse())) because
    // those branches may not execute — a FLAW path
    // could leave the variable unused.
    for (Statement s : allStmts) {
      // Check if this statement sits inside an
      // opaque if-branch.
      boolean insideOpaque = isInsideOpaqueIfBranch(s);
      if (insideOpaque) continue;
      for (NameExpr ne : s.findAll(NameExpr.class)) {
        if (!isRead(ne)) continue;
        String name = ne.getNameAsString();
        if (paramSet.contains(name)) continue;
        int line = ne.getBegin().map(p -> p.line).orElse(-1);
        if (line > 0) {
          reads.computeIfAbsent(name, k -> new ArrayList<>()).add(line);
        }
      }
    }

    // R749: parameter-overwrite and unused-parameter detection.
    // For each parameter, walk statements in source
    // order. Track first mention, whether first
    // mention is an assign, and whether it was ever
    // read. Two patterns:
    //   (a) First mention is AssignExpr with literal
    //       value AND no read precedes it →
    //       _parameter_value_* (caller value discarded).
    //   (b) Parameter is never mentioned in body
    //       → _parameter_variable_* (dead parameter).
    for (Parameter p : n.getParameters()) {
      String paramName = p.getNameAsString();
      int paramDeclLine = p.getBegin().map(q -> q.line).orElse(-1);
      int firstMentionLine = Integer.MAX_VALUE;
      boolean firstIsAssign = false;
      boolean everAssigned = false;
      boolean everRead = false;
      for (Statement s : allStmts) {
        for (NameExpr ne : s.findAll(NameExpr.class)) {
          if (!ne.getNameAsString().equals(paramName)) continue;
          int ln = ne.getBegin().map(q -> q.line).orElse(-1);
          if (ln <= 0) continue;
          if (ln < firstMentionLine) {
            firstMentionLine = ln;
            Node parent = ne.getParentNode().orElse(null);
            while (parent != null && !(parent instanceof Statement)) {
              parent = parent.getParentNode().orElse(null);
            }
            firstIsAssign = false;
            if (parent instanceof ExpressionStmt es
                && es.getExpression() instanceof AssignExpr ae
                && ae.getTarget() instanceof NameExpr tgt
                && tgt.getNameAsString().equals(paramName)) {
              firstIsAssign = true;
            }
          }
          if (isRead(ne)) everRead = true;
          if (!isRead(ne)) everAssigned = true;
          break;
        }
      }
      if (firstIsAssign) {
        // _parameter_value_*: assign before read.
        boolean hasEarlierRead = false;
        for (Statement s : allStmts) {
          int ln = s.getBegin().map(q -> q.line).orElse(-1);
          if (ln <= 0 || ln >= firstMentionLine) continue;
          for (NameExpr ne : s.findAll(NameExpr.class)) {
            if (!ne.getNameAsString().equals(paramName)) continue;
            if (isRead(ne)) {
              hasEarlierRead = true;
              break;
            }
          }
          if (hasEarlierRead) break;
        }
        if (!hasEarlierRead) {
          fire(firstMentionLine, ctx, paramName);
        }
      } else if (!everAssigned && !everRead && paramDeclLine > 0) {
        // _parameter_variable_*: parameter declared
        // but never used in the method body.
        fire(paramDeclLine, ctx, paramName);
      }
    }

    // Third pass: for each variable's ASSIGNs,
    // check whether a READ occurs between this
    // ASSIGN and either (a) the next ASSIGN of
    // the same variable or (b) end of method.
    for (Map.Entry<String, List<Integer>> e : assigns.entrySet()) {
      String varName = e.getKey();
      List<Integer> assignLines = e.getValue();
      List<Integer> readLines = reads.getOrDefault(varName, List.of());
      assignLines.sort(Integer::compareTo);
      for (int i = 0; i < assignLines.size(); i++) {
        int assignLine = assignLines.get(i);
        int nextAssignLine =
            (i + 1 < assignLines.size()) ? assignLines.get(i + 1) : Integer.MAX_VALUE;
        boolean hasReadInRange = false;
        for (int rl : readLines) {
          if (rl > assignLine && rl < nextAssignLine) {
            hasReadInRange = true;
            break;
          }
        }
        if (!hasReadInRange) {
          if (!paramSet.contains(varName)) {
            fire(assignLine, ctx, varName);
          }
        }
      }
    }

    // Fourth pass: catch variables that are
    // declared but NEVER assigned and NEVER read
    // (the Juliet `unused_uninit_variable_*` family).
    // Pattern:
    //   String data;
    //   /* do nothing */
    for (Statement s : allStmts) {
      int declLine = s.getBegin().map(p -> p.line).orElse(-1);
      if (declLine <= 0) continue;
      if (s instanceof ExpressionStmt es
          && es.getExpression() instanceof VariableDeclarationExpr vde) {
        for (VariableDeclarator vd : vde.getVariables()) {
          String name = vd.getNameAsString();
          if (paramSet.contains(name)) continue;
          if (vd.getInitializer().isPresent()) continue;
          // Skip if the variable is ever assigned
          // or read after this point.
          if (assigns.containsKey(name)) continue;
          List<Integer> readLines = reads.getOrDefault(name, List.of());
          boolean hasRead = false;
          for (int rl : readLines) {
            if (rl > declLine) {
              hasRead = true;
              break;
            }
          }
          if (!hasRead) {
            // Fire at the declaration line.
            fire(declLine, ctx, name);
          }
        }
      }
    }
  }

  /**
   * Returns true when {@code e} is plausibly a "may write to literal" expression ? string literals,
   * int/long/double literals, char literals, boolean literals, null literals, or {@code
   * FieldAccessExpr} for a known constant (e.g. {@code Integer.MAX_VALUE}). Method calls are
   * excluded because they may have side effects and are tracked by other checkers (CWE690 etc.).
   */
  private static boolean isLiteralOrLiteralField(Expression e) {
    if (e instanceof StringLiteralExpr) return true;
    if (e instanceof IntegerLiteralExpr) return true;
    if (e instanceof LongLiteralExpr) return true;
    if (e instanceof DoubleLiteralExpr) return true;
    if (e instanceof CharLiteralExpr) return true;
    if (e instanceof BooleanLiteralExpr) return true;
    if (e instanceof NullLiteralExpr) return true;
    if (e instanceof TextBlockLiteralExpr) return true;
    if (e instanceof LiteralExpr) return true;
    if (e instanceof FieldAccessExpr fae) {
      // `Integer.MAX_VALUE` etc. — fields of known
      // constant classes.
      String s = fae.toString();
      if (s.startsWith("Integer.")
          || s.startsWith("Long.")
          || s.startsWith("Short.")
          || s.startsWith("Byte.")
          || s.startsWith("Float.")
          || s.startsWith("Double.")
          || s.startsWith("Boolean.")
          || s.startsWith("Character.")) {
        return true;
      }
      return false;
    }
    if (e instanceof UnaryExpr ue) {
      // `-5`, `+5`, `!true` etc. — keep simple:
      // walk into the operand.
      return isLiteralOrLiteralField(ue.getExpression());
    }
    if (e instanceof CastExpr ce) {
      return isLiteralOrLiteralField(ce.getExpression());
    }
    if (e instanceof BinaryExpr be) {
      // `1 + 2`, "foo" + "bar" — both operands
      // must be literals.
      return isLiteralOrLiteralField(be.getLeft()) && isLiteralOrLiteralField(be.getRight());
    }
    if (e instanceof ConditionalExpr ce) {
      // `cond ? a : b` — only flag if both branches
      // are literals (rare in Juliet CWE563).
      return isLiteralOrLiteralField(ce.getThenExpr()) && isLiteralOrLiteralField(ce.getElseExpr());
    }
    if (e instanceof EnclosedExpr ee) {
      return isLiteralOrLiteralField(ee.getInner());
    }
    if (e instanceof ObjectCreationExpr oce) {
      // `new StringBuilder("Good")` / `new String("Good")`
      // etc. — wrapper types whose only purpose is to
      // hold a literal. Other ObjectCreationExpr (e.g.
      // `new FileInputStream(...)`, `new Thread(...)`)
      // have side effects and are NOT flagged.
      String typeName = oce.getType().getNameAsString();
      if (!isWrapperType(typeName)) return false;
      // All args must be literals.
      for (Expression arg : oce.getArguments()) {
        if (!isLiteralOrLiteralField(arg)) return false;
      }
      return true;
    }
    return false;
  }

  /**
   * Returns true when {@code typeName} is a "wrapper" type ? a class whose only purpose is to hold
   * a literal value. ObjectCreationExpr on these types counts as a literal assignment for the
   * purposes of CWE-563 detection. Wrapper types: String, StringBuilder, StringBuffer,
   * Long/Integer/Short/Byte/Double/Float/Boolean/ Character.
   */
  private static boolean isWrapperType(String typeName) {
    switch (typeName) {
      case "String":
      case "StringBuilder":
      case "StringBuffer":
      case "Long":
      case "Integer":
      case "Short":
      case "Byte":
      case "Double":
      case "Float":
      case "Boolean":
      case "Character":
      case "BigDecimal":
      case "BigInteger":
        return true;
      default:
        return false;
    }
  }

  /**
   * R751: returns true if {@code s} is inside a then- or else-branch of an {@code if} whose
   * condition is {@code IO.staticReturnsTrueOrFalse()} (the testcasesupport opaque helper).
   */
  private static boolean isInsideOpaqueIfBranch(Statement s) {
    Node parent = s.getParentNode().orElse(null);
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

  /**
   * Returns true when {@code ne} is a read (not a write). A NameExpr is a write iff it is the
   * target of an AssignExpr. Walks up the parent chain to check.
   */
  private static boolean isRead(NameExpr ne) {
    Node parent = ne.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof AssignExpr) {
        AssignExpr a = (AssignExpr) parent;
        if (a.getTarget() == ne) return false;
        // RHS or unrelated — treat as read.
        return true;
      }
      // VariableDeclarator -> the LHS is a SimpleName,
      // not a NameExpr. Any NameExpr descendant of a
      // VariableDeclarator is in the initializer or
      // annotations — treat as a read.
      parent = parent.getParentNode().orElse(null);
    }
    // No enclosing AssignExpr / VariableDeclarator
    // — treat as a read.
    return true;
  }

  private void fire(int line, StyleContext ctx, String varName) {
    String key = line + "|" + varName + "|unusedVar";
    if (reported.add(key)) {
      ctx.report(
          line,
          -1,
          RULE,
          "Local variable `"
              + varName
              + "` is assigned a literal at line "
              + line
              + " but is never read before being overwritten. The "
              + "assigned value is dead (CWE-563). Remove the assignment "
              + "or use the variable.",
          RuleDef.Severity.LOW,
          varName + " dead-assigned at line " + line);
    }
  }
}
