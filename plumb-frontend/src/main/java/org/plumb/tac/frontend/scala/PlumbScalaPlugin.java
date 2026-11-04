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

import dotty.tools.dotc.plugins.PluginPhase;
import dotty.tools.dotc.plugins.StandardPlugin;
import scala.Option;

/**
 * The Scala 3 compiler plugin that carries the TAC lowering into the compiler's pipeline.
 *
 * <p><b>Why a plugin, and not a hand-rolled phase list.</b> dotc's phase pipeline is
 * internal API: the phase names, their grouping, and the order they run in are not a
 * stable contract. The plugin API ({@link StandardPlugin} + {@link PluginPhase}) is the
 * one mechanism dotc actually supports for extending the pipeline, and it is what the
 * {@code -Xplugin} command-line option uses. Registering the phase through it means the
 * frontend asks dotc to place the phase, and dotc decides where it goes.
 *
 * <p><b>Why it is written in Java.</b> The Plumb build is a Java-only reactor: adding a
 * Scala compile step to it (through {@code scala-maven-plugin}) would make every module
 * that depends on the Scala frontend carry a second build tool. The plugin API itself is
 * reachable from Java -- the Scala traits it exposes are ordinary Java interfaces, and
 * {@code dotty.tools.dotc.transform.MegaPhase.MiniPhase} is an ordinary Java abstract
 * class. The only friction is that Scala's collections are not {@code java.util}
 * collections, which the bridge in {@link ScalaTacPhase} handles.
 *
 * <p><b>Statelessness.</b> A plugin instance is created fresh for every file the
 * frontend parses, and holds no state beyond the phase it hands out. Two frontends
 * running concurrently never share one.
 *
 * @see ScalaTacPhase
 * @see PlumbScalaContext
 */
final class PlumbScalaPlugin implements StandardPlugin {

  /** The name dotc reports for this plugin. */
  private static final String NAME = "plumb-scala-tac";

  /** The phase this plugin contributes. */
  private final PluginPhase phase;

  /** The plugin's option text; {@code None} because the plugin takes no options. */
  private Option<String> optionsHelp = Option.empty();

  /**
   * Creates the plugin.
   *
   * @param phase the phase the plugin contributes to the compiler's pipeline
   */
  PlumbScalaPlugin(PluginPhase phase) {
    this.phase = phase;
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "Lowers Scala 3 source into Plumb's three-address-code IR.";
  }

  @Override
  public Option<String> optionsHelp() {
    return optionsHelp;
  }

  /**
   * The setter for {@link #optionsHelp()}.
   *
   * <p>{@code optionsHelp} is a {@code var} in the Scala trait, so the trait declares
   * this setter. The plugin takes no options, so the setter only records the value.
   */
  @Override
  public void dotty$tools$dotc$plugins$Plugin$_setter_$optionsHelp_$eq(Option<String> x) {
    this.optionsHelp = x == null ? Option.empty() : x;
  }

  /**
   * The plugin's phases.
   *
   * <p>dotc calls this to ask the plugin what it contributes. The frontend does not
   * install the plugin through this method -- it installs the phase directly through
   * {@link PlumbScalaContext#addPluginPhases} -- but the method is still implemented
   * because it is part of the plugin contract, and because it is what makes the plugin
   * usable through dotc's own {@code -Xplugin} mechanism.
   *
   * @param options the plugin's command-line options; unused
   * @return the single phase this plugin contributes
   */
  @Override
  public scala.collection.immutable.List<PluginPhase> init(
      scala.collection.immutable.List<String> options) {
    java.util.List<PluginPhase> phases = java.util.List.of(phase);
    return scala.jdk.javaapi.CollectionConverters.asScala(phases).toList();
  }
}
