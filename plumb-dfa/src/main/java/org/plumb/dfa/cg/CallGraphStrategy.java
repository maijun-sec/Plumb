package org.plumb.dfa.cg;

import org.plumb.tac.ir.TacProgram;

/**
 * Strategy interface for building a {@link CallGraph} from a {@link TacProgram}.
 *
 * <p>Different call-graph construction algorithms trade off precision for speed. The interface
 * allows the analyzer to swap strategies without touching the rest of the engine.
 *
 * <h2>Available strategies</h2>
 *
 * <ul>
 *   <li>{@link ChaCallGraphStrategy} ? Class Hierarchy Analysis. Conservatively resolves every
 *       virtual call to all implementors of the receiver's static type. Fast, imprecise.
 *   <li>{@link RtaCallGraphStrategy} ? Rapid Type Analysis. Adds a whole-program set of
 *       instantiated types, then filters virtual-call targets to types that are actually
 *       instantiated. Slower than CHA, more precise.
 *   <li>{@link StubCallGraphStrategy} ? Conservative: only direct (static) calls are added; virtual
 *       calls are recorded but unresolved. Useful for very large projects where CHA / RTA would be
 *       too slow.
 *   <li>{@link CachedCallGraphStrategy} ? Wraps another strategy and loads/saves the resulting
 *       {@link CallGraph} to a local cache (JSON). Speeds up repeated analysis of the same
 *       codebase.
 * </ul>
 *
 * <h2>Configuration</h2>
 *
 * <p>Strategies are selected by name via {@link CallGraphStrategyFactory#create(String,
 * TacProgram)}. The CLI flag {@code --cg-strategy} maps to these names.
 */
public interface CallGraphStrategy {

  /**
   * Build the call graph. The returned {@link CallGraph} is immutable; the strategy may share
   * internal state but must not mutate the input program.
   */
  CallGraph build();

  /**
   * Human-readable name of this strategy. Used in logs and for cache-key derivation in {@link
   * CachedCallGraphStrategy}.
   */
  String name();
}
