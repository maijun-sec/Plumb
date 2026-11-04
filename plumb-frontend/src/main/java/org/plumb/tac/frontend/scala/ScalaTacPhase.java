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

import dotty.tools.dotc.ast.Trees;
import dotty.tools.dotc.core.Contexts.Context;
import dotty.tools.dotc.core.Types.Type;
import dotty.tools.dotc.plugins.PluginPhase;
import java.util.ArrayList;
import java.util.List;

/**
 * The compiler phase that lowers Scala 3 source into Plumb's TAC.
 *
 * <p><b>What the phase does.</b> It runs once per compilation unit, on the fully typed
 * tree (after the {@code typer}), and hands the tree to a {@link ScalaTacTranslator}.
 * The translator produces the {@link org.plumb.tac.ir.TacFunction}s; the phase collects
 * them and hands them back to the frontend.
 *
 * <p><b>Why {@code transformUnit} and not {@code transformBlock}.</b> {@code
 * transformBlock} fires only on {@code Block} nodes, and a compilation unit is a {@code
 * PackageDef}, not a {@code Block}. {@code transformUnit} is the one hook that fires
 * exactly once per unit, with the whole typed tree -- which is what the frontend wants,
 * because it needs the enclosing package and class structure too, not just one method
 * body.
 *
 * <p><b>Why the phase extends {@code MegaPhase.MiniPhase}.</b> In Scala, a plugin phase
 * is written {@code class MyPhase extends MiniPhase with PluginPhase}. {@code
 * MiniPhase} is where the transform hooks live; {@code PluginPhase} only adds the
 * ordering information ({@code runsBefore} / {@code runsAfter}) that the plugin API
 * needs. From Java the two are separate types: {@code PluginPhase} is an interface with
 * no transform method at all, and {@code MiniPhase} is the abstract class that carries
 * them. So the phase implements both.
 *
 * <p><b>Why {@code superPhase} is set by hand.</b> A {@code MiniPhase} normally belongs
 * to a {@code MegaPhase} that owns it, and {@code MiniPhase.superPhase} points back at
 * that owner. A phase that is not owned by any {@code MegaPhase} has a null {@code
 * superPhase}, and the {@code relaxedTyping} machinery NPEs on it. This phase therefore
 * owns itself: it is the single mini phase of a {@code MegaPhase} it creates.
 *
 * <p><b>The phase does not transform anything.</b> It reads the tree and returns it
 * unchanged. Lowering is a read-only view of the source, and dotc's own later phases
 * are left to compile the Scala normally.
 *
 * @see ScalaTacTranslator
 * @see PlumbScalaPlugin
 */
final class ScalaTacPhase extends dotty.tools.dotc.transform.MegaPhase.MiniPhase
    implements PluginPhase {

  /** The name dotc reports for this phase. */
  private static final String PHASE_NAME = "plumbScalaTac";

  /** The functions the translator produced, in source order. */
  private final List<org.plumb.tac.ir.TacFunction> functions = new ArrayList<>();

  /** The problems the translator found, in the order it found them. */
  private final List<String> problems = new ArrayList<>();

  /**
   * The same problems, in the IR's structured form.
   *
   * <p>These travel with the IR, so a source-less consumer can see which construct the frontend
   * could not lower, in which function, at which line.
   */
  private final List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems =
      new ArrayList<>();

  /**
   * The module the compiled unit belongs to, or {@code null} when there is no module boundary.
   *
   * <p>It is handed to the translator so every produced function carries it. A single-module
   * analysis needs that: it reports the findings of one module and only those, while the other
   * modules are still read so the call graph keeps crossing the boundary.
   */
  private String module;

  /**
   * Sets the module the compiled unit belongs to.
   *
   * @param module the module id, or {@code null} for "no module boundary"
   */
  void setModule(String module) {
    this.module = module == null || module.isBlank() ? null : module;
  }

  /**
   * Creates the phase.
   *
   * <p>The constructor makes the phase its own {@code MegaPhase}'s only mini phase, so
   * that {@code superPhase} is never null.
   */
  ScalaTacPhase() {
    superPhase_$eq(
        new dotty.tools.dotc.transform.MegaPhase(
            new dotty.tools.dotc.transform.MegaPhase.MiniPhase[] {this}));
  }

  @Override
  public String phaseName() {
    return PHASE_NAME;
  }

  /**
   * The phases this one runs before.
   *
   * <p>Empty: the phase wants to run as early as the pipeline allows after the {@code
   * typer}, so it names no successor.
   */
  @Override
  public scala.collection.immutable.Set<String> runsBefore() {
    return scala.jdk.javaapi.CollectionConverters.asScala(List.<String>of()).toSet();
  }

  /**
   * The phases this one runs after.
   *
   * <p>{@code typer} only: the lowering needs resolved types, and it wants them before
   * the trees are serialised.
   */
  @Override
  public scala.collection.immutable.Set<String> runsAfter() {
    return scala.jdk.javaapi.CollectionConverters.asScala(List.of("typer")).toSet();
  }

  /**
   * Lowers one compilation unit.
   *
   * <p>The tree is returned unchanged -- the lowering reads it, it does not rewrite it.
   *
   * @param tree the fully typed compilation unit
   * @param ctx the compiler context the unit is in
   * @return the same tree
   */
  @Override
  public Trees.Tree<Type> transformUnit(Trees.Tree<Type> tree, Context ctx) {
    try {
      ScalaTacTranslator translator = new ScalaTacTranslator(ctx);
      translator.setModule(module);
      translator.translateUnit(tree, functions, problems);
      // The STRUCTURED problems, which travel with the IR. Without them a Scala function
      // that was only partly lowered produced findings that looked exactly as complete as
      // a fully lowered one.
      structuredProblems.addAll(translator.structuredProblems());
    } catch (RuntimeException | LinkageError ex) {
      // A frontend must be total: a tree the lowering cannot handle becomes a problem,
      // never an exception out of the compiler.
      String message =
          "lowering failure in "
              + (tree == null ? "<unknown unit>" : tree.getClass().getSimpleName())
              + ": "
              + ex.getClass().getSimpleName()
              + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
      problems.add(message);
      structuredProblems.add(
          new org.plumb.tac.ir.TacProgram.FunctionProblem(
              "<scala>",
              "TRANSLATION_ERROR",
              message,
              ex.getClass().getSimpleName(),
              -1,
              -1));
    }
    return tree;
  }

  /** The functions the phase produced. */
  List<org.plumb.tac.ir.TacFunction> functions() {
    return List.copyOf(functions);
  }

  /** The problems the phase found. */
  List<String> problems() {
    return List.copyOf(problems);
  }

  /** The problems the phase found, in the IR's structured form. */
  List<org.plumb.tac.ir.TacProgram.FunctionProblem> structuredProblems() {
    return List.copyOf(structuredProblems);
  }
}
