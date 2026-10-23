package org.plumb.style.performance;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/** Synchronize on interned String / boxed primitive is unsafe (use a private Object lock). */
public final class SynchronizationOnStringOrBoxedCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-performance-synchronization-on-string-or-boxed", "SYNCHRONIZATION_ON_STRING_OR_BOXED"),
          "Synchronize on interned String / boxed primitive is unsafe (use a private Object lock)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.MULTITHREADING,
          List.of("CWE-820"),
          "org.plumb.style.SynchronizationOnStringOrBoxedCheck",
          true,
          List.of("ast-performance-string-boxed", "synchronization-on-string-or-boxed")
      );

  /**
   * The FORBIDDEN_TYPES set narrowed to the most common narrowed to the most common Java SE types
   * (boxed primitives + String + Optional + a few time types). We match by simple type name.
   */
  private static final Set<String> FORBIDDEN_TYPES =
      Set.of(
          "String",
          "Boolean",
          "Byte",
          "Character",
          "Double",
          "Float",
          "Integer",
          "Long",
          "Short",
          "Optional",
          "OptionalInt",
          "OptionalLong",
          "OptionalDouble",
          "Instant",
          "LocalDate",
          "LocalTime",
          "LocalDateTime");

  @Override
  public String name() {
    return "synchronization-on-string-or-boxed";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(ClassOrInterfaceDeclaration n, StyleContext ctx) {
    // First pass: collect forbidden-type field names
    // (per-class) and per-method forbidden-type local
    // var names. We do this before walking
    // SynchronizedStmts.
    final Set<String> forbiddenFields = new HashSet<>();
    for (FieldDeclaration f : n.getFields()) {
      final String typeName = typeBaseName(f.getElementType());
      if (FORBIDDEN_TYPES.contains(typeName)) {
        for (VariableDeclarator v : f.getVariables()) {
          forbiddenFields.add(v.getNameAsString());
        }
      }
    }
    // Walk each method's body for synchronized statements.
    for (MethodDeclaration m : n.getMethods()) {
      // Collect local var types within this method.
      final Map<String, String> localVarTypes = new HashMap<>();
      m.getBody()
          .ifPresent(
              body ->
                  body.walk(
                      com.github.javaparser.ast.stmt.ExpressionStmt.class,
                      es -> {
                        if (es.getExpression()
                            instanceof com.github.javaparser.ast.expr.VariableDeclarationExpr vde) {
                          final String typeName = typeBaseName(vde.getElementType());
                          vde.getVariables()
                              .forEach(v -> localVarTypes.put(v.getNameAsString(), typeName));
                        }
                      }));
      // Walk synchronized statements.
      m.getBody()
          .ifPresent(
              body ->
                  body.walk(
                      SynchronizedStmt.class,
                      sync -> {
                        final Expression lock = sync.getExpression();
                        if (lock == null) return;
                        // Direct string literal: always fires
                        // (interned string).
                        if (lock.isStringLiteralExpr()) {
                          fire(ctx, sync, "String");
                          return;
                        }
                        // NameExpr: check against forbidden fields
                        // and forbidden-type locals.
                        if (lock instanceof NameExpr ne) {
                          final String name = ne.getNameAsString();
                          if (forbiddenFields.contains(name)) {
                            fire(ctx, sync, "Field " + name);
                            return;
                          }
                          final String localType = localVarTypes.get(name);
                          if (localType != null && FORBIDDEN_TYPES.contains(localType)) {
                            fire(ctx, sync, localType);
                          }
                        }
                      }));
    }
  }

  private static String typeBaseName(Type t) {
    if (t instanceof ClassOrInterfaceType coi) {
      return coi.getNameAsString();
    }
    if (t.isPrimitiveType()) {
      // Synchronize on a primitive autoboxes it
      // (e.g. synchronized (int) : Integer.valueOf).
      // That's a different rule ( etc.); we
      // narrow to reference types.
      return null;
    }
    return null;
  }

  private void fire(StyleContext ctx, SynchronizedStmt sync, String typeName) {
    ctx.report(
        sync.getBegin().map(p -> p.line).orElse(-1),
        sync.getBegin().map(p -> p.column).orElse(-1),
        DEFAULT_RULE,
        "Synchronize on a new \"Object\" instead of "
            + "the type '"
            + typeName
            + "' (which is shared/cached)",
        RuleDef.Severity.HIGH,
        typeName);
  }
}
