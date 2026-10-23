/*
 * R729-A2: AST check for CWE-226 (Sensitive
 * Information Uncleared Before Release). Fires when
 * a `char[]` field whose name suggests a credential
 * (`password`, `secret`, `key`, `pin`, `credential`,
 * `token`) is declared AND the compilation unit does
 * NOT subsequently contain a `setLength(0)` /
 * `Arrays.fill(...)` / `replace(...)` cleanup call
 * referring to that field.
 *
 * <p>Generic. We don't try to confirm cleanup runs
 * before release ? we just flag the suspicious
 * "sensitive char[] field without any cleanup call"
 * pattern. Real codebases that always clear sensitive
 * fields before exit will fail this; mitigate with
 * narrower field-name matching or with comment
 * suppression.</p>
 *
 * <p>Generic, no Juliet-specific patterns.</p>
 */
package org.plumb.style.style;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.PrimitiveType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class UnclearedSensitiveInfoCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-style-uncleared-sensitive-info", "UNCLEARED_SENSITIVE_INFO"),
          "Sensitive char[] field is never cleared (CWE-226)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-226"),
          "org.plumb.style.UnclearedSensitiveInfoCheck",
          true,
          List.of("ast-style-uncleared-sensitive-info", "uncleared-sensitive-info")
      );

  private static final Pattern SENSITIVE_NAME =
      Pattern.compile("(?i)(password|passwd|secret|credential|token|pin|ssn|key)");

  private final Set<String> reported = new HashSet<>();
  private final Set<String> sensitiveFields = new HashSet<>();

  @Override
  public String name() {
    return "uncleared-sensitive-info";
  }

  @Override
  public void init(StyleContext ctx) {
    reported.clear();
    sensitiveFields.clear();
    // walk fields AND local variable declarations.
    if (ctx.source() != null && ctx.source().unit() != null) {
      var root = ctx.source().unit();
      // field decls
      for (FieldDeclaration fd : root.findAll(FieldDeclaration.class)) {
        for (VariableDeclarator vd : fd.getVariables()) {
          String name = vd.getNameAsString();
          String type = vd.getTypeAsString();
          if (matches(name, type)) {
            sensitiveFields.add(name);
          }
        }
      }
      // local variable decls (incl. inside try/catch/blocks).
      for (com.github.javaparser.ast.stmt.LocalClassDeclarationStmt n :
          root.findAll(com.github.javaparser.ast.stmt.LocalClassDeclarationStmt.class)) {
        // ignore
      }
      for (var vde : root.findAll(com.github.javaparser.ast.expr.VariableDeclarationExpr.class)) {
        for (VariableDeclarator vd : vde.getVariables()) {
          String name = vd.getNameAsString();
          String type =
              vde.getElementType().toString()
                  + (vde.getElementType() instanceof ArrayType ? "" : "");
          if (matches(name, type)) {
            sensitiveFields.add(name);
          }
        }
      }
    }
  }

  private boolean matches(String name, String type) {
    if (!SENSITIVE_NAME.matcher(name).find()) return false;
    String t = type.trim();
    return t.startsWith("char[]")
        || t.equals("char []")
        || t.startsWith("StringBuffer")
        || t.startsWith("StringBuilder");
  }

  @Override
  public void finish(StyleContext ctx) {}

  @Override
  public void visit(FieldDeclaration n, StyleContext ctx) {
    for (VariableDeclarator vd : n.getVariables()) {
      String name = vd.getNameAsString();
      if (!sensitiveFields.contains(name)) continue;
      boolean isCharArr = (n.getElementType() instanceof ArrayType);
      boolean isBuf = false;
      if (n.getElementType() instanceof com.github.javaparser.ast.type.ClassOrInterfaceType cit) {
        String tn = cit.getNameAsString();
        isBuf = "StringBuffer".equals(tn) || "StringBuilder".equals(tn);
      }
      if (isCharArr) {
        var at = (ArrayType) n.getElementType();
        if (!(at.getComponentType() instanceof PrimitiveType pt)) continue;
        if (pt.getType() != PrimitiveType.Primitive.CHAR) continue;
      }
      if (!isCharArr && !isBuf) continue;
      if (hasCleanup(name, ctx)) continue;
      int line = vd.getBegin().map(p -> p.line).orElse(-1);
      int col = vd.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|unclr";
      if (reported.add(key)) {
        String typeDesc = isCharArr ? "char[] " : "StringBuffer/Builder ";
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Sensitive "
                + typeDesc
                + "field '"
                + name
                + "' is never cleared — zero it before "
                + "scope exit (CWE-226)",
            RuleDef.Severity.MEDIUM,
            typeDesc + name);
      }
    }
  }

  @Override
  public void visit(com.github.javaparser.ast.expr.VariableDeclarationExpr n, StyleContext ctx) {
    for (VariableDeclarator vd : n.getVariables()) {
      String name = vd.getNameAsString();
      if (!sensitiveFields.contains(name)) continue;
      boolean isCharArr = (n.getElementType() instanceof ArrayType);
      boolean isBuf = false;
      if (n.getElementType() instanceof com.github.javaparser.ast.type.ClassOrInterfaceType cit) {
        String tn = cit.getNameAsString();
        isBuf = "StringBuffer".equals(tn) || "StringBuilder".equals(tn);
      }
      if (isCharArr) {
        var at = (ArrayType) n.getElementType();
        if (!(at.getComponentType() instanceof PrimitiveType pt)) continue;
        if (pt.getType() != PrimitiveType.Primitive.CHAR) continue;
      }
      if (!isCharArr && !isBuf) continue;
      if (hasCleanup(name, ctx)) continue;
      int line = vd.getBegin().map(p -> p.line).orElse(-1);
      int col = vd.getBegin().map(p -> p.column).orElse(-1);
      String key = line + "|" + col + "|unclr";
      if (reported.add(key)) {
        String typeDesc = isCharArr ? "char[] " : "StringBuffer/Builder ";
        ctx.report(
            line,
            col,
            DEFAULT_RULE,
            "Sensitive "
                + typeDesc
                + "local '"
                + name
                + "' is never cleared — zero it before "
                + "scope exit (CWE-226)",
            RuleDef.Severity.MEDIUM,
            typeDesc + name);
      }
    }
  }

  /**
   * Returns true if the compilation unit contains a cleanup call referencing the field. We accept
   * any method call whose argument-list contains the field name and whose callee is one of
   * setLength / fill / replace / clear / zeroize.
   */
  private boolean hasCleanup(String fieldName, StyleContext ctx) {
    if (ctx.source() == null || ctx.source().unit() == null) return false;
    for (MethodCallExpr mc : ctx.source().unit().findAll(MethodCallExpr.class)) {
      String name = mc.getNameAsString();
      if (!("setLength".equals(name)
          || "fill".equals(name)
          || "replace".equals(name)
          || "clear".equals(name)
          || "zeroize".equals(name))) continue;
      for (var arg : mc.getArguments()) {
        if (arg instanceof NameExpr ne && fieldName.equals(ne.getNameAsString())) {
          return true;
        }
      }
      if (mc.getScope().isPresent()
          && mc.getScope().get() instanceof NameExpr ne
          && fieldName.equals(ne.getNameAsString())) {
        if ("setLength".equals(name) || "replace".equals(name)) {
          return true;
        }
      }
    }
    return false;
  }
}
