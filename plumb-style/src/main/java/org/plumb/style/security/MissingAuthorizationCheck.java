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
 * AST-style CWE-862 (Missing Authorization) checker.
 *
 * <p>Fires on Spring / Jakarta web endpoint methods that expose a web route but lack an
 * authorization annotation ({@code @PreAuthorize}, {@code @Secured}, {@code @RolesAllowed},
 * ...). The fix is either to add the annotation, or to enable method-level security globally
 * via {@code @EnableMethodSecurity} / etc.
 *
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class MissingAuthorizationCheck implements AstChecker {

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

  private static final Set<String> AUTHZ_OPT_IN =
      Set.of(
          "PreAuthorize",
          "PostAuthorize",
          "Secured",
          "RolesAllowed",
          "DenyAll",
          "RequiresPermissions",
          "RequiresRoles",
          "Authorize",
          "AuthorizeResource");

  private static final Set<String> PERMIT_ANON = Set.of("PermitAll", "AnonymousAllowed");

  private static final Set<String> CLASS_LEVEL_SECURITY =
      Set.of(
          "EnableMethodSecurity",
          "EnableGlobalMethodSecurity",
          "EnableWebSecurity",
          "EnableResourceServer");

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-missing-authorization", "MISSING_AUTHORIZATION"),
          "Web endpoint without authorization annotation (CWE-862)",
          RuleDef.Severity.HIGH,
          RuleDef.Type.SECURITY,
          List.of("CWE-862"),
          "org.plumb.style.security.MissingAuthorizationCheck",
          true,
          List.of("ast-security-missing-authorization", "missing-authorization"));

  @Override
  public String name() {
    return "missing-authorization";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodDeclaration md, StyleContext ctx) {
    String name = md.getNameAsString();
    if ("<init>".equals(name) || "<clinit>".equals(name)) return;
    // Find the enclosing class for annotation inspection.
    ClassOrInterfaceDeclaration cls =
        md.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
    if (cls == null) return;
    boolean isController = hasAnnotation(cls, CONTROLLER_ANNOS);
    boolean classLevelSec = hasAnnotation(cls, CLASS_LEVEL_SECURITY);
    if (!isController && !hasAnnotation(md, ENDPOINT_ANNOS)) return;
    if (hasAnnotation(md, AUTHZ_OPT_IN)) return;
    if (hasAnnotation(md, PERMIT_ANON)) return;
    if (classLevelSec) return;
    int line = md.getBegin().map(p -> p.line).orElse(-1);
    int col = md.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        RULE,
        "Web endpoint '"
            + name
            + "' has no authorization"
            + " annotation — any caller (authenticated or"
            + " not) can invoke it (CWE-862). Add"
            + " @PreAuthorize / @Secured / @RolesAllowed,"
            + " or enable @EnableMethodSecurity at the class level.",
        RuleDef.Severity.HIGH,
        name);
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
