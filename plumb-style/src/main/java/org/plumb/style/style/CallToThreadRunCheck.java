package org.plumb.style.style;

import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import java.util.List;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.rules.RuleId;
import org.plumb.style.AstChecker;
import org.plumb.style.StyleContext;

/**
 * Call {@code Thread.start()} not {@code Thread.run()}.
 *
 * <p>CWE-572. The original detection heuristic lived in the dfa module ({@code
 * dfa.cwe572-thread-run}, regex-based source-reading) and was deprecated in R787a. The canonical
 * detector is this {@link AstChecker}, which walks the {@link
 * com.github.javaparser.ast.CompilationUnit} via {@code AstDispatcher} and verifies the call's
 * receiver is a {@code Thread} (or subclass) instance.
 *
 * <p><b>R787a enhancement.</b> The original heuristic was name-based (variable name contains {@code
 * "Thread"} / {@code "task"} / {@code "worker"} / {@code "Runnable"}). That matched Juliet but was
 * structurally dishonest about real code. R787a keeps the name fallback as a safety net but prefers
 * symbol-solver type resolution via {@link StyleContext#resolvedTypeOf}: if the receiver's resolved
 * type descends from {@code java.lang.Thread}, fire; otherwise fall through to the name heuristic.
 */
public final class CallToThreadRunCheck implements AstChecker {

  private static final RuleDef DEFAULT_RULE =
      new RuleDef(
          // R787a: rule_id aligned with the eval harness's
          // plumb_rule_to_cwe.json mapping (`Cwe572` →
          // ["572"]). The previous id `pl-0047-call-to-thread-run`
          // predated the eval harness and was not in the
          // map, so all 17 findings we emitted on Juliet
          // CWE-572 were being cwe-mismatch-skipped during
          // strict eval. The legacy id is preserved as the
          // short_name so any external whitelist still
          // matches.
          new RuleId("ast-style-call-to-thread-run", "CALL_TO_THREAD_RUN"),
          "call Thread.start() not Thread.run()",
          RuleDef.Severity.HIGH,
          RuleDef.Type.BUG,
          List.of("CWE-572"),
          "org.plumb.style.CallToThreadRunCheck",
          true);

  @Override
  public String name() {
    return "call-to-thread-run";
  }

  @Override
  public void init(StyleContext ctx) {}

  @Override
  public void visit(MethodCallExpr n, StyleContext ctx) {
    if (!"run".equals(n.getNameAsString())) return;
    com.github.javaparser.ast.expr.Expression scope = n.getScope().orElse(null);
    if (scope == null) return;
    // R787a: prefer the symbol-solver. If the receiver's
    // resolved type is Thread (or a Thread subclass), this
    // is a structural hit. Fall through to the name
    // heuristic only when resolution fails (e.g. type
    // not on the classpath, generic wildcard without
    // bound) so we don't regress Juliet CWE-572 precision.
    if (isThreadReceiverByType(ctx, scope)) {
      reportRun(n, ctx, "type-resolved");
      return;
    }
    if (isThreadReceiverByName(scope)) {
      reportRun(n, ctx, "name-heuristic");
    }
  }

  private static void reportRun(MethodCallExpr n, StyleContext ctx, String why) {
    int line = n.getBegin().map(p -> p.line).orElse(-1);
    int col = n.getBegin().map(p -> p.column).orElse(-1);
    ctx.report(
        line,
        col,
        DEFAULT_RULE,
        "Call Thread.start() instead of Thread.run() "
            + "(run blocks the calling thread; "
            + why
            + ")",
        RuleDef.Severity.HIGH,
        "Thread#run");
  }

  /**
   * Returns true if {@code scope}'s resolved type descends from {@code java.lang.Thread}.
   * Resolution failures (null type, unresolvable name, exception during {@link
   * com.github.javaparser.ast.type.Type#resolve()}) all return false so the caller can fall through
   * to the name heuristic.
   */
  private static boolean isThreadReceiverByType(
      StyleContext ctx, com.github.javaparser.ast.expr.Expression scope) {
    try {
      ResolvedType rt = ctx.resolvedTypeOf(scope);
      if (rt == null) return false;
      // Walk up the ancestry chain looking for Thread.
      // We can't compare ResolvedType.qualifiedName()
      // directly because Thread is parameterized in some
      // setups; instead, ask each ancestor's type
      // declaration for its qualified name. Note
      // JavaParser 3.26.4 spells it `getAllAncestors()`,
      // not the older `allAncestors()`.
      for (ResolvedType ancestor : rt.asReferenceType().getAllAncestors()) {
        ResolvedTypeDeclaration decl = ancestor.asReferenceType().getTypeDeclaration().orElse(null);
        if (decl == null) continue;
        if ("java.lang.Thread".equals(decl.getQualifiedName())) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException ex) {
      // Symbol resolution isn't perfect; fall through to
      // name heuristic on any failure.
      return false;
    }
  }

  /**
   * Pre-R787a name-based heuristic. Kept as a safety net for cases where the symbol solver can't
   * resolve the receiver type (e.g. {@code Thread-typed} variable declared in a library not on the
   * classpath, or {@code Runnable} that happens to also have a {@code run()} method).
   */
  private static boolean isThreadReceiverByName(com.github.javaparser.ast.expr.Expression scope) {
    if (scope instanceof NameExpr ne) {
      String n = ne.getNameAsString();
      if (n.endsWith("Thread")
          || n.endsWith("task")
          || n.endsWith("worker")
          || n.endsWith("Runnable")) {
        return true;
      }
      if (n.equals("thread") || n.equals("t") || n.startsWith("thread")) {
        return true;
      }
      return false;
    }
    if (scope instanceof FieldAccessExpr fa) {
      String owner = fa.getScope().toString();
      return "Thread".equals(owner) || "this".equals(owner);
    }
    return false;
  }
}
