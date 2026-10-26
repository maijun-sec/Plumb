package org.plumb.style.annotations;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Annotation-extraction utilities that work even when the {@link
 * com.github.javaparser.resolution.TypeSolver} isn't wired up to the project (so {@code
 * AnnotationExpr.resolve()} would throw on project-local annotations).
 *
 * <p>The strategy is: an annotation's textual name is a simple name ({@code "NonNull"}) most of the
 * time, but the source may also write the FQN ({@code "javax.annotation.Nonnull"}) directly on the
 * annotation node. We match the textual name first (allowing simple-name), then look at the {@code
 * import} declarations of the enclosing compilation unit to map the simple name to its imported
 * FQN. The output is the <em>union</em> of "short name" and "long name" forms so callers can match
 * against either spelling.
 *
 * <p>This class is read-only -- it does not mutate the AST.
 */
public final class AnnotationExtractor {

  private AnnotationExtractor() {}

  /**
   * Returns the names of all annotations on {@code node} as a {@code {shortName, fqn}} set. The set
   * is the union of:
   *
   * <ol>
   *   <li>The annotation's textual simple name (e.g. {@code Id}).
   *   <li>The FQN as reconstructed from the surrounding {@code import} declarations when the simple
   *       name maps to one (e.g. {@code javax.persistence.Id}).
   *   <li>The annotation's FQN as written on the node, if the developer used the full form
   *       directly.
   * </ol>
   */
  public static Set<String> namesOf(Node node) {
    Set<String> out = new LinkedHashSet<>();
    NodeList<AnnotationExpr> anns = annotationsOf(node);
    for (AnnotationExpr a : anns) {
      addAnnotationNames(a, out);
    }
    return out;
  }

  /**
   * Returns true if any annotation on {@code node} matches one of the FQNs / short names in {@code
   * candidates}. The match is done on the same union that {@link #namesOf} builds, so a check for
   * {@code "NonNull"} matches both {@code @NonNull} and {@code @Nonnull} (via the candidates set),
   * regardless of import statements.
   */
  public static boolean hasAny(Node node, Set<String> candidates) {
    if (node == null || candidates == null || candidates.isEmpty()) return false;
    Set<String> actual = namesOf(node);
    for (String c : candidates) {
      if (actual.contains(c)) return true;
    }
    return false;
  }

  /**
   * Returns the first annotation name (from {@code candidates}) that appears on {@code node}, or
   * empty.
   */
  public static Optional<String> findFirst(Node node, Set<String> candidates) {
    if (node == null || candidates == null || candidates.isEmpty()) return Optional.empty();
    Set<String> actual = namesOf(node);
    for (String c : candidates) {
      if (actual.contains(c)) return Optional.of(c);
    }
    return Optional.empty();
  }

  /**
   * Returns the value of a named member of a single- or normal-annotation expression.
   * {@code @Foo(value = "x")} with {@code memberName == "value"} returns {@code Optional.of("x")}.
   * The return value is the AST text of the right-hand side -- the caller is responsible for
   * resolving to a typed value.
   */
  public static Optional<String> memberValue(AnnotationExpr a, String memberName) {
    if (a == null) return Optional.empty();
    if (a instanceof NormalAnnotationExpr) {
      return ((NormalAnnotationExpr) a)
          .getPairs().stream()
              .filter(p -> p.getNameAsString().equals(memberName))
              .map(p -> p.getValue().toString())
              .findFirst();
    }
    if (a instanceof SingleMemberAnnotationExpr) {
      if ("value".equals(memberName)) {
        return Optional.of(((SingleMemberAnnotationExpr) a).getMemberValue().toString());
      }
    }
    return Optional.empty();
  }

  // ─────── internals ───────

  /**
   * JavaParser hides the annotation list behind several node types: {@link FieldDeclaration},
   * {@link MethodDeclaration}, {@link Parameter}, etc. all expose it via {@link
   * BodyDeclaration#getAnnotations()}; for {@code Parameter} the method is also named {@code
   * getAnnotations()}. This helper unifies those.
   */
  private static NodeList<AnnotationExpr> annotationsOf(Node node) {
    if (node instanceof BodyDeclaration) {
      return ((BodyDeclaration<?>) node).getAnnotations();
    }
    if (node instanceof Parameter) {
      return ((Parameter) node).getAnnotations();
    }
    return new NodeList<>();
  }

  /** Adds the annotation's short and FQN spellings to {@code out}. */
  private static void addAnnotationNames(AnnotationExpr a, Set<String> out) {
    if (a == null) return;
    // Short name: the last segment of the type reference.
    String shortName = a.getName().getIdentifier();
    if (!shortName.isEmpty()) out.add(shortName);
    // FQN-as-written: walk the qualifier chain.
    String qualifier = a.getName().getQualifier().map(Object::toString).orElse("");
    if (!qualifier.isEmpty()) {
      out.add(qualifier + "." + shortName);
    }
    // FQN-via-import: look up the short name in the enclosing
    // compilation unit's import list.
    if (!shortName.isEmpty() && !qualifier.isEmpty()) {
      // already handled
    } else if (!shortName.isEmpty()) {
      resolveViaImport(a, shortName).ifPresent(out::add);
    }
  }

  /**
   * Find the {@code import} declaration that maps {@code shortName} to an FQN, if any, in the
   * enclosing compilation unit.
   */
  private static Optional<String> resolveViaImport(AnnotationExpr a, String shortName) {
    CompilationUnit cu = ancestorCu(a);
    if (cu == null) return Optional.empty();
    for (ImportDeclaration imp : cu.getImports()) {
      String name = imp.getNameAsString();
      if (name.endsWith("." + shortName)) {
        return Optional.of(name);
      }
    }
    return Optional.empty();
  }

  private static CompilationUnit ancestorCu(Node n) {
    Node cur = n;
    while (cur != null) {
      if (cur instanceof CompilationUnit) return (CompilationUnit) cur;
      cur = cur.getParentNode().orElse(null);
    }
    return null;
  }

  /**
   * Convenience: returns true if {@code decl} (or any enclosing class) is annotated with one of
   * {@link AnnotationFacts#JPA_ENTITY}.
   */
  public static boolean isEntityClass(ClassOrInterfaceDeclaration decl) {
    if (decl == null) return false;
    if (hasAny(decl, AnnotationFacts.JPA_ENTITY)) return true;
    // Check outer classes for @Entity on a static inner class.
    // JavaParser exposes the parent chain via getParentNode.
    Node parent = decl.getParentNode().orElse(null);
    while (parent != null) {
      if (parent instanceof ClassOrInterfaceDeclaration) {
        if (hasAny(parent, AnnotationFacts.JPA_ENTITY)) return true;
      }
      parent = parent.getParentNode().orElse(null);
    }
    return false;
  }

  /**
   * Returns the field declarations of a class whose name matches one of the FQNs / short names in
   * {@code candidates}. Used by the JPA / Hibernate checkers to find e.g. {@code @Id} fields
   * without resolving their type.
   */
  public static List<FieldDeclaration> fieldsAnnotatedWith(
      ClassOrInterfaceDeclaration decl, Set<String> candidates) {
    List<FieldDeclaration> out = new java.util.ArrayList<>();
    for (FieldDeclaration f : decl.getFields()) {
      if (hasAny(f, candidates)) out.add(f);
    }
    return out;
  }

  /**
   * Returns the constructor declarations of a class that are explicitly declared in the source.
   * Synthetic default constructors (no source) are skipped.
   */
  public static List<ConstructorDeclaration> declaredConstructors(
      ClassOrInterfaceDeclaration decl) {
    return decl.getConstructors();
  }
}
