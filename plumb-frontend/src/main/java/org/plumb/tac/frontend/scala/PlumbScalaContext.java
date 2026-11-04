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
package org.plumb.tac.frontend.scala;

import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.core.Phases.Phase;

/**
 * The compiler context that installs the TAC lowering into dotc's phase pipeline.
 *
 * <p><b>Why this class exists.</b> dotc decides where a plugin phase runs by consulting
 * the phase plan, and the plan is built once, from the phase list the driver assembles.
 * The one extension point the driver offers is {@link
 * dotty.tools.dotc.core.Contexts.ContextBase#addPluginPhases}, which the driver calls
 * with the plan it has assembled. Overriding it and calling {@link
 * dotty.tools.dotc.plugins.Plugins#schedule} is exactly what dotc does for a plugin
 * loaded through {@code -Xplugin}, so the phase lands in the same place a real plugin's
 * phase would.
 *
 * <p><b>Why the phase runs after {@code typer}.</b> The lowering needs fully typed
 * trees: a {@code match}'s pattern types, a method's resolved signature, and a symbol's
 * synthetic flag all only exist once the typer has run. Before the typer the trees carry
 * unresolved names; after the {@code pickler} the trees have been serialised and the
 * source-level shape is gone. So the phase asks to run after {@code typer} and before
 * everything else.
 *
 * <p><b>Statelessness.</b> The context holds only the plugin, and the plugin holds only
 * the phase. Both are created fresh per file by {@link ScalaTacFrontend}, so no state
 * outlives one parse.
 *
 * @see PlumbScalaPlugin
 * @see ScalaTacPhase
 */
final class PlumbScalaContext extends dotty.tools.dotc.core.Contexts.ContextBase {

  /** The plugin whose phase is installed into the pipeline. */
  private final PlumbScalaPlugin plugin;

  /**
   * Creates the context.
   *
   * @param plugin the plugin whose phase is installed into the pipeline
   */
  PlumbScalaContext(PlumbScalaPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Installs the plugin's phase into the compiler's phase plan.
   *
   * <p>This is the hook dotc calls once the plan has been assembled. The plugin's
   * phases are handed to {@link dotty.tools.dotc.plugins.Plugins#schedule}, which places
   * them relative to the phases they declare {@code runsAfter} / {@code runsBefore}.
   *
   * @param plan the plan dotc assembled
   * @param ctx the context the plan is built in
   * @return the plan with the plugin's phase inserted
   */
  @Override
  public scala.collection.immutable.List<scala.collection.immutable.List<Phase>> addPluginPhases(
      scala.collection.immutable.List<scala.collection.immutable.List<Phase>> plan, Context ctx) {
    scala.collection.immutable.List<dotty.tools.dotc.plugins.PluginPhase> phases =
        plugin.initialize(scala.collection.immutable.List$.MODULE$.<String>empty(), ctx);
    return dotty.tools.dotc.plugins.Plugins$.MODULE$.schedule(plan, phases);
  }

  /** The plugin this context installs. */
  PlumbScalaPlugin plugin() {
    return plugin;
  }

  /** Unused helper kept for symmetry with the Scala collections the plugin API uses. */
  static <T> scala.collection.immutable.List<T> toScalaList(java.util.List<T> source) {
    return scala.jdk.javaapi.CollectionConverters.asScala(source).toList();
  }
}
