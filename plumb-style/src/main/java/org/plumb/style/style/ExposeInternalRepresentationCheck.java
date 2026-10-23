package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Getter returns a mutable internal array or collection (defensive copy required). */
public final class ExposeInternalRepresentationCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-expose-internal-representation", "EXPOSE_INTERNAL_REPRESENTATION"),
          "Getter returns a mutable internal array or collection (defensive copy required)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.CORRECTNESS,
          List.of("CWE-374"),
          "org.plumb.style.ExposeInternalRepresentationCheck",
          true,
          List.of("ast-style-expose-internal-representation", "expose-internal-representation")
      );

  private static final List<String> MUTABLE_RETURN_TYPES =
      List.of(
          "Date",
          "Calendar",
          "GregorianCalendar",
          "ArrayList",
          "LinkedList",
          "HashSet",
          "LinkedHashSet",
          "HashMap",
          "LinkedHashMap",
          "TreeMap",
          "TreeSet");

  @Override
  public String name() {
    return "expose-internal-representation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration n, StyleContext ctx) {
    // Must be a getter.
    if (!n.getNameAsString().startsWith("get")) return;
    if (n.getParameters().size() != 0) return;
    // Return type must be mutable (array or known
    // collection type).
    final Type returnType = n.getType();
    final boolean isArray =
        returnType.isPrimitiveType() == false && returnType instanceof ArrayType;
    final boolean isMutableCollection = isKnownMutableType(returnType);
    if (!isArray && !isMutableCollection) return;
    // Build a map of field name ⇒?field type for
    // the enclosing class.
    final Map<String, Type> fieldTypes = collectEnclosingFieldTypes(n);
    // Walk the body for return statements that
    // return a NameExpr whose name matches a
    // field of the same type.
    final Optional<BlockStmt> body = n.getBody();
    if (body.isEmpty()) return;
    body.get()
        .walk(
            ReturnStmt.class,
            rs -> {
              if (rs.getExpression().isEmpty()) return;
              if (!(rs.getExpression().get() instanceof NameExpr ne)) return;
              final String name = ne.getNameAsString();
              if (!fieldTypes.containsKey(name)) return;
              final Type fieldType = fieldTypes.get(name);
              if (!typesMatch(returnType, fieldType)) return;
              ctx.report(
                  rs.getBegin().map(p -> p.line).orElse(-1),
                  rs.getBegin().map(p -> p.column).orElse(-1),
                  DEFAULT_RULE,
                  "Getter returns a reference to the mutable internal field '"
                      + name
                      + "' (return a defensive copy)",
                  RuleDef.Severity.MEDIUM,
                  name);
            });
  }

  private static boolean isKnownMutableType(Type t) {
    if (!(t instanceof ClassOrInterfaceType cit)) return false;
    return MUTABLE_RETURN_TYPES.contains(cit.getNameAsString());
  }

  private static Map<String, Type> collectEnclosingFieldTypes(MethodDeclaration n) {
    final Map<String, Type> result = new HashMap<>();
    n.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration.class)
        .ifPresent(
            cls -> {
              for (FieldDeclaration fd : cls.getFields()) {
                for (VariableDeclarator v : fd.getVariables()) {
                  // Use v.getType()
                  // (the variable's type, e.g.
                  // int[] for `int[] data`),
                  // not fd.getElementType()
                  // (the array's element type,
                  // e.g. int for `int[] data`).
                  // The latter is wrong for
                  // array fields.
                  result.put(v.getNameAsString(), v.getType());
                }
              }
            });
    return result;
  }

  /**
   * Loose type match by name. Two types "match" if they have the same simple name (e.g. "int[]"
   * matches "int[]"). For class types, just compare the simple name.
   */
  private static boolean typesMatch(Type a, Type b) {
    if (a instanceof ArrayType && b instanceof ArrayType) return true;
    if (a instanceof ArrayType || b instanceof ArrayType) return false;
    if (a instanceof ClassOrInterfaceType ca && b instanceof ClassOrInterfaceType cb) {
      return ca.getNameAsString().equals(cb.getNameAsString());
    }
    return false;
  }
}
