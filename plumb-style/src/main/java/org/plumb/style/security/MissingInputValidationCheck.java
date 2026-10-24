/*
 * R-813: AST-style CWE-20 (Improper Input Validation) checker.
 *
 * <p>Fires on web endpoint methods that accept user input via parameter annotations
 * ({@code @RequestParam}, {@code @PathVariable}, {@code @RequestBody}, ...) but don't opt into
 * Bean Validation through {@code @Valid}, {@code @Validated}, or a class-level
 * {@code @Validated}.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before. The enclosing
 * class (for the controller / class-level validation annotations) is recovered via
 * {@code findAncestor(ClassOrInterfaceDeclaration.class)}.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class MissingInputValidationCheck implements AstChecker {

  private static final Set<String> USER_INPUT_PARAM_ANNOS =
      Set.of(
          "RequestParam",
          "PathVariable",
          "RequestBody",
          "RequestHeader",
          "CookieValue",
          "RequestPart",
          "ModelAttribute",
          "QueryParam",
          "PathParam",
          "HeaderParam",
          "CookieParam",
          "BeanParam",
          "FormParam");

  private static final Set<String> CLASS_OR_METHOD_VALIDATION = Set.of("Validated", "Valid");

  private static final Set<String> CONTROLLER_ANNOS = Set.of("RestController", "Controller");

  private static final Set<String> ENDPOINT_ANNOS =
      Set.of(
          "GetMapping",
          "PostMapping",
          "PutMapping",
          "DeleteMapping",
          "PatchMapping",
          "RequestMapping",
          "GET",
          "POST",
          "PUT",
          "DELETE",
          "HEAD",
          "OPTIONS",
          "Path");

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-missing-input-validation", "MISSING_INPUT_VALIDATION"),
          "Web endpoint accepts user input without @Valid/@Validated (CWE-20)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-20"),
          "org.plumb.style.security.MissingInputValidationCheck",
          true,
          List.of("ast-security-missing-input-validation", "missing-input-validation"));

  @Override
  public String name() {
    return "missing-input-validation";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    String name = md.getNameAsString();
    if ("<init>".equals(name) || "<clinit>".equals(name)) return;
    ClassOrInterfaceDeclaration cls =
        md.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (cls == null) return;
    boolean isController = hasAnnotation(cls, CONTROLLER_ANNOS);
    boolean classValidated = hasAnnotation(cls, CLASS_OR_METHOD_VALIDATION);
    if (!isController && !hasAnnotation(md, ENDPOINT_ANNOS)) return;
    if (!hasUserInputParam(md)) return;
    if (hasValidationOptIn(md, classValidated)) return;
    int line = md.getBegin().map(p -> p.line).orElse(-1);
    int col = md.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        RULE,
        "Web endpoint '"
            + name
            + "' accepts user input"
            + " without Bean Validation. Add @Valid to"
            + " each user-input parameter, or @Validated"
            + " at the class level (CWE-20).",
        RuleDef.Severity.MEDIUM,
        name);
  }

  private static boolean hasUserInputParam(MethodDeclaration md) {
    for (Parameter p : md.getParameters()) {
      for (AnnotationExpr a : p.getAnnotations()) {
        if (USER_INPUT_PARAM_ANNOS.contains(a.getNameAsString())) return true;
      }
    }
    return false;
  }

  private static boolean hasValidationOptIn(MethodDeclaration md, boolean classValidated) {
    if (classValidated) return true;
    if (hasAnnotation(md, CLASS_OR_METHOD_VALIDATION)) return true;
    for (Parameter p : md.getParameters()) {
      for (AnnotationExpr a : p.getAnnotations()) {
        if (CLASS_OR_METHOD_VALIDATION.contains(a.getNameAsString())) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasAnnotation(MethodDeclaration md, Set<String> wanted) {
    for (AnnotationExpr a : md.getAnnotations()) {
      if (wanted.contains(a.getNameAsString())) return true;
    }
    return false;
  }

  private static boolean hasAnnotation(ClassOrInterfaceDeclaration cls, Set<String> wanted) {
    for (AnnotationExpr a : cls.getAnnotations()) {
      if (wanted.contains(a.getNameAsString())) return true;
    }
    return false;
  }
}
