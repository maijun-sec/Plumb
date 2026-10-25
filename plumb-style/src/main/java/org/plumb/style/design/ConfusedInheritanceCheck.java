package org.plumb.style.design;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Confused inheritance ? the class declares an {@code implements} interface that is already
 * inherited from its parent.
 *
 * <p>The classic pattern is:
 *
 * <pre>
 *   abstract class AbstractThing implements Closeable { ... }
 *   class ConcreteThing extends AbstractThing implements Closeable { ... }   // ← redundant
 * </pre>
 *
 * <p>The pre-S4 implementation flagged every class that had {@code extends} + empty {@code
 * implements} list, which produced 100% false positives ? almost every JPA / Spring class has
 * exactly this shape ({@code class User extends BaseEntity implements Serializable}).
 *
 * <p>S4 audit replaced that with a meaningful check: walk the extends/implements list of the parent
 * class as written in the same compilation unit (single-file resolution) and flag the child only
 * when it declares an interface that the parent already declares.
 *
 * <p><b>M5 part-2 migration:</b> the original used {@code visit(CompilationUnit) +
 * findAll(ClassOrInterfaceDeclaration)} (1 walk) + a manual per-class loop. This rewrite keeps the
 * per-class logic in {@code visit(ClassOrInterfaceDeclaration, ctx)} ? the dispatcher hands each
 * declaration to us directly. The class-by-name index is still needed (we look up the parent's
 * interfaces by simple name) and is built lazily in {@code init} so the work is amortised across
 * every {@code visit} call within the same file.
 */
public final class ConfusedInheritanceCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          new RuleId("ast-design-confused-inheritance", "CONFUSED_INHERITANCE"),
          "Confused inheritance — child repeats an interface already declared on parent",
          RuleDef.Severity.LOW,
          RuleDef.Type.STYLE,
          List.of(),
          "org.plumb.style.ConfusedInheritanceCheck",
          false,
          List.of(
              "ast-design-confused-inheritance",
              "confused-inheritance")); // R-834: disabled by default until Juliet baseline

  // re-measured.

  /**
   * Per-file index of class declarations by simple name. Populated by {@link #init} and consumed by
   * {@link #visit(ClassOrInterfaceDeclaration, StyleContext)}. Cleared in {@code init} so the
   * checker is re-entrant across multiple files (the dispatcher shares one checker instance per
   * type but invokes init + visit per file).
   */
  private Map<String, ClassOrInterfaceDeclaration> bySimpleName;

  private CompilationUnit indexedUnit;

  @Override
  public String name() {
    return "confused-inheritance";
  }

  @Override
  public void init(StyleContext ctx) {
    bySimpleName = new HashMap<>();
    CompilationUnit unit = ctx.source().unit();
    indexedUnit = unit;
    if (unit == null) return;
    // One walk of the file at the start of the AST
    // traversal — far cheaper than the previous
    // visit(CompilationUnit) pattern that ran for every
    // file the dispatcher visited.
    for (ClassOrInterfaceDeclaration c : unit.findAll(ClassOrInterfaceDeclaration.class)) {
      bySimpleName.putIfAbsent(c.getNameAsString(), c);
    }
  }

  @Override
  public void visit(ClassOrInterfaceDeclaration c, StyleContext ctx) {
    if (bySimpleName == null) {
      // init() was never called for this run (test
      // environments sometimes skip init). Build the
      // index lazily here as a fallback.
      init(ctx);
    }
    if (bySimpleName == null) return;
    if (c.getExtendedTypes().isEmpty()) return;
    if (c.getImplementedTypes().isEmpty()) return;
    // Build the set of interfaces the immediate parent
    // already declares. We look up the parent in this
    // same file only — cross-file parent interfaces are
    // out of scope for the AST-only check.
    Set<String> parentInterfaces = new HashSet<>();
    for (ClassOrInterfaceType parentRef : c.getExtendedTypes()) {
      ClassOrInterfaceDeclaration parent = bySimpleName.get(parentRef.getNameAsString());
      if (parent == null) continue;
      for (ClassOrInterfaceType pt : parent.getImplementedTypes()) {
        parentInterfaces.add(pt.getNameAsString());
      }
      for (ClassOrInterfaceType pt : parent.getExtendedTypes()) {
        parentInterfaces.add(pt.getNameAsString());
      }
    }
    if (parentInterfaces.isEmpty()) return;

    // Find the first child-declared interface that is
    // already on the parent.
    ClassOrInterfaceType redundant = null;
    for (ClassOrInterfaceType impl : c.getImplementedTypes()) {
      if (parentInterfaces.contains(impl.getNameAsString())) {
        redundant = impl;
        break;
      }
    }
    if (redundant == null) return;

    int line = c.getBegin().map(p -> p.line).orElse(-1);
    int col = c.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Class '"
            + c.getNameAsString()
            + "' implements '"
            + redundant.getNameAsString()
            + "' which is already inherited from its parent — drop the redundant declaration",
        RuleDef.Severity.LOW,
        c.getNameAsString());
  }
}
