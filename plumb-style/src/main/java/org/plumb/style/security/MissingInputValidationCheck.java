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
/*
 * AST-style CWE-20 (Improper Input Validation) checker.
 *
 * <p>Fires on web endpoint methods that accept user input via parameter annotations
 * ({@code @RequestParam}, {@code @PathVariable}, {@code @RequestBody}, ...) but don't opt into
 * Bean Validation through {@code @Valid}, {@code @Validated}, or a class-level
 * {@code @Validated}.
 *
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
import org.plumb.rules.framework.FrameworkInputAnnotations;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class MissingInputValidationCheck implements AstChecker {

  /**
   * The parameter annotations that bind a request value to a parameter.
   *
   * <p>This is the shared catalogue, not a local list. The taint engine decides where a taint flow
   * STARTS from these annotations, and this checker decides whether the value is VALIDATED. The two
   * have to agree: when each declared its own list, thirteen annotations were an HTTP trust
   * boundary to the taint engine but invisible here, so a {@code @RestQuery} parameter was tracked
   * as attacker-controlled by one layer and treated as trusted-by-default by the other.
   */
  private static final Set<String> USER_INPUT_PARAM_ANNOS =
      FrameworkInputAnnotations.USER_INPUT_PARAM_ANNOTATIONS;

  private static final Set<String> CLASS_OR_METHOD_VALIDATION = Set.of("Validated", "Valid");

  /**
   * The class-level annotations that make a class a web endpoint holder.
   *
   * <p>JAX-RS and Quarkus REST put {@code @Path} on the CLASS and the HTTP method annotation on the
   * METHOD, while Spring puts {@code @RestController} on the class and {@code @GetMapping} on the
   * method. Micronaut puts {@code @Controller} on the class. So the class-level markers are not the
   * same set as the method-level ones, and a checker that only recognised Spring's class annotation
   * never fired on a JAX-RS resource at all.
   */
  private static final Set<String> CONTROLLER_ANNOS =
      Set.of(
          // Spring MVC
          "RestController",
          "RequestMapping",
          // Micronaut
          "Controller",
          // JAX-RS / Quarkus REST
          "Path");

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
          "PATCH",
          "Path",
          // Micronaut
          "Get",
          "Post",
          "Put",
          "Delete",
          "Patch",
          "Head",
          "Options");

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
