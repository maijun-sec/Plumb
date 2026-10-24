/*
 * R-813: AST-style CWE-200 (Information Exposure) checker.
 *
 * <p>Fires on Spring / Jakarta web endpoint methods that return a user-declared class (a JPA
 * entity, a domain object, or a generic wrapper around one) directly to the client. The fix is
 * to project to a sanitized DTO so internal fields (passwords, IDs, emails) are not serialized
 * into the response.
 *
 * <p>M5 part-4 migration (2026-10-17): per-node-type callback; was {@code
 * visit(CompilationUnit) + findAll(ClassOrInterfaceDeclaration.class)} before. The enclosing
 * class (for the controller annotation) is recovered via
 * {@code findAncestor(ClassOrInterfaceDeclaration.class)}.
 */
package org.plumb.style.security;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.PrimitiveType;
import com.github.javaparser.ast.type.Type;
import java.util.List;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

public final class InformationExposureCheck implements AstChecker {

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

  /** Types that are always safe to return from an endpoint. */
  private static final Set<String> INHERENTLY_SAFE =
      Set.of(
          "void",
          "boolean",
          "byte",
          "short",
          "char",
          "int",
          "long",
          "float",
          "double",
          "String",
          "CharSequence",
          "Object");

  /** JDK / framework classes that endpoints may legitimately return. */
  private static final Set<String> JDK_SAFE =
      Set.of(
          "ResponseEntity",
          "HttpEntity",
          "Response",
          "Page",
          "Slice",
          "Pageable",
          "PagedList",
          "List",
          "Set",
          "Collection",
          "Iterable",
          "Queue",
          "Deque",
          "SortedSet",
          "NavigableSet",
          "Map",
          "SortedMap",
          "NavigableMap",
          "Map.Entry",
          "Set.Entry",
          "HashMap",
          "LinkedHashMap",
          "TreeMap",
          "ArrayList",
          "LinkedList",
          "HashSet",
          "LinkedHashSet",
          "TreeSet",
          "CollectionModel",
          "PagedModel",
          "Optional",
          "Future",
          "CompletableFuture",
          "EntityModel",
          "RepresentationModel",
          "Link",
          "Resource",
          "ResourceSupport",
          "ResponseBodyEmitter",
          "SseEmitter",
          "JsonNode",
          "Document",
          "Node",
          "Element");

  private static final RuleDef RULE =
      new RuleDef(
          new RuleId("ast-security-information-exposure", "INFORMATION_EXPOSURE"),
          "Web endpoint returns user-declared class (CWE-200)",
          RuleDef.Severity.MEDIUM,
          RuleDef.Type.SECURITY,
          List.of("CWE-200"),
          "org.plumb.style.security.InformationExposureCheck",
          true,
          List.of("ast-security-information-exposure", "information-exposure"));

  @Override
  public String name() {
    return "information-exposure";
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
    if (!isController && !hasAnnotation(md, ENDPOINT_ANNOS)) return;
    if (!shouldFire(md.getType())) return;
    int line = md.getBegin().map(p -> p.line).orElse(-1);
    int col = md.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        RULE,
        "Web endpoint '"
            + name
            + "' returns type '"
            + md.getType().asString()
            + "' — consider projecting to a"
            + " sanitized DTO so internal fields"
            + " (passwords, IDs, emails) aren't"
            + " serialized into the response (CWE-200)",
        RuleDef.Severity.MEDIUM,
        md.getType().asString());
  }

  private static boolean shouldFire(Type t) {
    if (t instanceof PrimitiveType) return false;
    if (!(t instanceof ClassOrInterfaceType cit)) return false;
    String simple = simpleName(cit.getNameAsString());
    if (INHERENTLY_SAFE.contains(simple)) return false;
    if (JDK_SAFE.contains(simple)) {
      // Wrapper: fire iff a type arg is a user class.
      return cit.getTypeArguments().isPresent() && typeArgsContainUserClass(cit);
    }
    return true;
  }

  private static boolean typeArgsContainUserClass(ClassOrInterfaceType cit) {
    var args = cit.getTypeArguments().orElse(null);
    if (args == null) return false;
    for (var ta : args) {
      if (!(ta instanceof ClassOrInterfaceType inner)) continue;
      String s = simpleName(inner.getNameAsString());
      if (!INHERENTLY_SAFE.contains(s) && !JDK_SAFE.contains(s)) {
        return true;
      }
      if (!inner.getTypeArguments().isEmpty() && typeArgsContainUserClass(inner)) return true;
    }
    return false;
  }

  private static String simpleName(String s) {
    if (s == null) return "";
    int lt = s.indexOf('<');
    String head = lt < 0 ? s : s.substring(0, lt);
    int dot = head.lastIndexOf('.');
    return dot < 0 ? head : head.substring(dot + 1);
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
