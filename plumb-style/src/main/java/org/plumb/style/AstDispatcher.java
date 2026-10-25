package org.plumb.style;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-pass dispatcher for {@link AstChecker}s.
 *
 * <p><b>M4 / M5 audit:</b> the previous implementation listed 30+ per-node-type visit overrides
 * ({@code visit(CompilationUnit, Void)}, {@code visit(IfStmt, Void)}, …) each with the same
 * boilerplate: iterate every registered checker, catch RuntimeException, call {@code super.visit(n,
 * arg)}. The result was 900+ lines of repetition AND a per-node cost of {@code O(checkers)}
 * invocations ? for a typical file with 10K AST nodes and 600 checkers, that's 6M invocations where
 * 99.9% are no-ops (the checker didn't override that visit type).
 *
 * <p>This rewrite collapses the dispatcher into two responsibilities:
 *
 * <ol>
 *   <li><b>Build the dispatch table once</b> at first {@link #run(StyleContext)}. For each checker
 *       we reflect over its declared {@code visit(NodeType, StyleContext)} methods and register an
 *       entry under the NodeType key. Default no-op methods declared on {@link AstChecker} are
 *       skipped.
 *   <li><b>Walk the tree once</b> via {@link CompilationUnit#walk(java.util.function.Consumer)},
 *       invoking on each node only the checkers that declared a matching per-type visit method.
 *       Per-node cost drops to {@code O(checkers_that_care_about_node_type)} ? typically 1-5
 *       instead of 600.
 * </ol>
 *
 * <p><b>Why walk(Consumer) and not a custom VoidVisitorAdapter:</b> JavaParser's {@code
 * VoidVisitor} interface (and {@code VoidVisitorAdapter}) defines ~100 typed {@code
 * visit(TypedNode, A)} methods but NO generic {@code visit(Node, A)} fallback in 3.26.4 ?
 * overriding one would either re-introduce the boilerplate or skip nodes not covered by the 100
 * overloads. The tree {@link Node#walk(Consumer)} traversal visits every Node in pre-order and is
 * the API the original dispatcher was approximating by hand.
 *
 * <p>Side benefits:
 *
 * <ul>
 *   <li>Checkers no longer need to be aware of the dispatcher. They just override {@code
 *       visit(FooStmt, StyleContext)} for whatever node types they care about. The old "findAll
 *       inside visit(CompilationUnit)" anti-pattern becomes unnecessary ? the dispatcher already
 *       gives each checker a per-node-type callback for free.
 *   <li>Adding a new {@code visit(SomeNewNode, StyleContext)} method to {@link AstChecker} (e.g.
 *       when JavaParser adds a new node type) is automatically picked up ? no dispatcher edit
 *       required.
 * </ul>
 */
public final class AstDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(AstDispatcher.class);

  private final List<AstChecker> checkers = new ArrayList<>();

  /**
   * Dispatch table: node class → list of (checker, visit-method). Built lazily on the first {@link
   * #run(StyleContext)} call so register order doesn't matter and tests that swap checkers
   * mid-flight behave correctly.
   */
  private Map<Class<? extends Node>, List<Entry>> dispatch;

  /**
   * One row in the dispatch table: a single (checker, visit-method) pair for a specific node type.
   */
  private record Entry(AstChecker checker, Method method) {}

  public AstDispatcher register(AstChecker c) {
    checkers.add(Objects.requireNonNull(c));
    // Invalidate the cache — it will be rebuilt on the
    // next run(). O(1) so we don't bother being clever.
    dispatch = null;
    return this;
  }

  public int checkerCount() {
    return checkers.size();
  }

  /**
   * Initialize every checker with the context, walk the unit once, then call each checker's {@link
   * AstChecker#finish}. Returns the number of findings produced (delta on {@code ctx.findings()},
   * useful for timing reports).
   */
  public int run(StyleContext ctx) {
    int before = ctx.findings().size();

    // init phase — per-checker param scope set by dispatcher.
    // Errors here are isolated to one checker and never abort the run.
    for (AstChecker c : checkers) {
      try {
        ctx.setCurrentRule(c.name());
        c.init(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("AstChecker {} init failed: {}", c.name(), ex.toString());
      }
    }

    // Build the dispatch table lazily on first run (and
    // after every register()).
    if (dispatch == null) {
      dispatch = new HashMap<>();
      for (AstChecker c : checkers) {
        for (Method m : c.getClass().getMethods()) {
          if (!"visit".equals(m.getName())) continue;
          Class<?>[] params = m.getParameterTypes();
          if (params.length != 2) continue;
          if (params[1] != StyleContext.class) continue;
          if (!Node.class.isAssignableFrom(params[0])) continue;
          // Skip the default no-ops declared on the
          // AstChecker interface itself. They never fire
          // a finding and registering them would just
          // bloat the dispatch table.
          if (m.getDeclaringClass() == AstChecker.class) continue;
          @SuppressWarnings("unchecked")
          Class<? extends Node> nodeType = (Class<? extends Node>) params[0];
          dispatch.computeIfAbsent(nodeType, k -> new ArrayList<>()).add(new Entry(c, m));
        }
      }
    }

    // Walk the unit once. We use CompilationUnit.walk(Consumer)
    // because it's a single API call that visits every Node
    // in pre-order — no need to manually implement the
    // ~100 typed visit overrides the old dispatcher had.
    CompilationUnit unit = ctx.source().unit();
    if (unit != null) {
      try {
        unit.walk(
            n -> {
              List<Entry> entries = dispatch.get(n.getClass());
              if (entries != null) {
                for (Entry e : entries) {
                  try {
                    ctx.setCurrentRule(e.checker().name());
                    e.method().invoke(e.checker(), n, ctx);
                  } catch (InvocationTargetException ite) {
                    // The checker threw. Unwrap and log so
                    // the operator sees the real cause, not
                    // a reflective InvocationTargetException.
                    Throwable cause = ite.getCause();
                    LOG.warn(
                        "AstChecker {} visit failed on {}: {}",
                        e.checker().name(),
                        ctx.source().path(),
                        cause == null ? ite.toString() : cause.toString());
                  } catch (IllegalAccessException ex) {
                    LOG.warn(
                        "AstChecker {} visit access denied: {}", e.checker().name(), ex.toString());
                  }
                }
              }
            });
      } catch (RuntimeException ex) {
        // Defensive — a checker bug shouldn't abort the
        // whole scan. The per-call try/catch above should
        // already catch RuntimeExceptions, but if anything
        // escapes (e.g. a stack overflow from a malformed
        // AST), log and continue.
        LOG.warn("AstDispatcher walk failed: " + ex);
      }
    }

    // finish phase
    for (AstChecker c : checkers) {
      try {
        c.finish(ctx);
      } catch (RuntimeException ex) {
        LOG.warn("AstChecker {} finish failed: {}", c.name(), ex.toString());
      }
    }
    return ctx.findings().size() - before;
  }
}
