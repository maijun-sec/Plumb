package org.plumb.style.style;

import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** externally provided mutable stored in field (EI_EXPOSE_REP2) : defensive copy needed. */
public final class ExposeMutableRepStorageCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-expose-mutable-rep2", "EXPOSE_MUTABLE_REP2"),
          "externally provided mutable stored in field (EI_EXPOSE_REP2) : defensive copy needed",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.BUG,
          List.of("CWE-374"),
          "org.plumb.style.ExposeMutableRepStorageCheck",
          true,
          List.of("ast-style-expose-rep2", "expose-mutable-rep2"));

  @Override
  public String name() {
    return "expose-mutable-rep2";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    // FieldDeclaration#getElementType returns the component
    // type (e.g. `int` for `int[] data`); use #getCommonType
    // for the declared type (`int[]`).
    Type fieldType = n.getCommonType();
    if (!isMutableRep(fieldType)) return;
    for (VariableDeclarator v : n.getVariables()) {
      if (v.getInitializer().isEmpty()) continue;
      if (!(v.getInitializer().get() instanceof NameExpr ne)) continue;
      if (ne.getNameAsString().length() == 0) continue;
      int line = v.getBegin().map(p -> p.line).orElse(-1);
      int col = v.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          "Storing externally-provided mutable object into '"
              + v.getNameAsString()
              + "' : defensive copy needed",
          RuleDef.Severity.MEDIUM,
          v.getNameAsString());
    }
  }

  /** Walk a constructor / setter body for a known mutable type. */
  @Override
  public void visit(ConstructorDeclaration n, StyleContext ctx) {
    Map<String, Type> fieldTypes = enclosingFieldTypes(n);
    if (fieldTypes.isEmpty()) return;
    scanBodyForThisFieldAssign(n.getBody(), fieldTypes, ctx);
  }

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Only consider setters: void return, single param.
    if (!n.getType().isVoidType()) return;
    if (n.getParameters().size() != 1) return;
    Map<String, Type> fieldTypes = enclosingFieldTypes(n);
    if (fieldTypes.isEmpty()) return;
    if (n.getBody().isEmpty()) return;
    scanBodyForThisFieldAssign(n.getBody().get(), fieldTypes, ctx);
  }

  private void scanBodyForThisFieldAssign(
      BlockStmt body, Map<String, Type> fieldTypes, StyleContext ctx) {
    if (body == null) return;
    for (com.github.javaparser.ast.stmt.Statement s : body.getStatements()) {
      if (!(s instanceof ExpressionStmt es)) continue;
      if (!(es.getExpression() instanceof AssignExpr ae)) continue;
      if (ae.getOperator() != AssignExpr.Operator.ASSIGN) continue;
      // Two patterns: `this.field = param` (FieldAccessExpr)
      // and `field = param` (bare NameExpr : common when
      // there's no `this.` and the field is in scope).
      String fieldName = null;
      if (ae.getTarget() instanceof FieldAccessExpr fa) {
        if (fa.getScope() instanceof NameExpr ne && "this".equals(ne.getNameAsString())) {
          fieldName = fa.getNameAsString();
        }
      } else if (ae.getTarget() instanceof NameExpr ne) {
        fieldName = ne.getNameAsString();
      }
      if (fieldName == null) continue;
      Type ft = fieldTypes.get(fieldName);
      if (ft == null) continue;
      if (!isMutableRep(ft)) continue;
      int line = ae.getBegin().map(p -> p.line).orElse(-1);
      int col = ae.getBegin().map(p -> p.column).orElse(-1);
      ctx.report(
          line,
          col,
          DEFAULT_RULE,
          fieldName + " assigned from parameter : " + "defensive copy needed",
          RuleDef.Severity.MEDIUM,
          fieldName);
    }
  }

  private static Map<String, Type> enclosingFieldTypes(com.github.javaparser.ast.Node n) {
    Map<String, Type> result = new HashMap<>();
    com.github.javaparser.ast.Node cur = n.getParentNode().orElse(null);
    while (cur != null) {
      if (cur instanceof ClassOrInterfaceDeclaration cid) {
        for (FieldDeclaration fd : cid.getFields()) {
          // FieldDeclaration#getElementType returns the
          // component type (e.g. `int` for `int[] data`).
          // Use #getCommonType for the declared type
          // (`int[]`).
          Type t = fd.getCommonType();
          for (VariableDeclarator v : fd.getVariables()) {
            result.put(v.getNameAsString(), t);
          }
        }
        break;
      }
      cur = cur.getParentNode().orElse(null);
    }
    return result;
  }

  private static boolean isMutableRep(Type t) {
    if (t instanceof ArrayType) return true;
    String s = t.asString();
    switch (s) {
      case "Date":
      case "java.util.Date":
      case "Calendar":
      case "java.util.Calendar":
      case "Hashtable":
      case "java.util.Hashtable":
      case "HashMap":
      case "java.util.HashMap":
      case "ArrayList":
      case "java.util.ArrayList":
      case "HashSet":
      case "java.util.HashSet":
      case "BitSet":
      case "java.util.BitSet":
        return true;
      default:
        return false;
    }
  }
}
