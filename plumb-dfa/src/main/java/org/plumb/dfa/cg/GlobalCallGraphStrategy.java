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
package org.plumb.dfa.cg;

/**
 * Cross-TU {@link CallGraphStrategy} backed by a {@link GlobalTACStore}.
 *
 * <p>Unlike the TU-local strategies (CHA, RTA, stub) which inspect a single {@link
 * org.plumb.tac.ir.TacProgram} and resolve callees by simple name + arity, this strategy consults
 * the global registry: the {@link GlobalTACStore} already knows every function defined across every
 * source file, so a call instruction's {@code calleeSig} is resolved by direct lookup. Cross-TU
 * calls land on the right node; library calls are filtered; the produced {@link CallGraph} spans
 * every loaded {@link org.plumb.tac.ir.TacProgram}.
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
