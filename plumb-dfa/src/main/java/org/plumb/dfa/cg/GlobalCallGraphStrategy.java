package org.plumb.dfa.cg;

/**
 * Cross-TU {@link CallGraphStrategy} backed by a {@link GlobalTACStore}.
 *
 * <p>Unlike the TU-local strategies (CHA, RTA, stub) which inspect a single {@link
 * org.plumb.tac.ir.TacProgram} and resolve callees by name + class hierarchy, this strategy
 * consults the global registry: the {@link GlobalTACStore} already knows every function defined
 * across every source file, so a call instruction's {@code calleeSig} is resolved by direct lookup.
 * Cross-TU calls land on the right node; library calls are filtered; the produced {@link CallGraph}
 * spans every loaded {@link org.plumb.tac.ir.TacProgram}.
 *
 * <p>Selection via {@link CallGraphStrategyFactory}:
 *
 * <pre>
 *   CallGraphStrategy s = CallGraphStrategyFactory
 *       .createCrossTu("global", store, null);
 *   CallGraph cg = s.build();
 * </pre>
 */
public final class GlobalCallGraphStrategy implements CallGraphStrategy {

  private final GlobalTACStore store;
  private final GlobalCallGraphBuilder builder;

  public GlobalCallGraphStrategy(GlobalTACStore store) {
    this.store = store;
    this.builder = new GlobalCallGraphBuilder(store);
  }

  @Override
  public String name() {
    return "global";
  }

  @Override
  public CallGraph build() {
    return builder.build();
  }

  /** Configurable library-call inclusion (default: false). */
  public GlobalCallGraphStrategy withIncludeLibraries(boolean v) {
    builder.withIncludeLibraries(v);
    return this;
  }
}
