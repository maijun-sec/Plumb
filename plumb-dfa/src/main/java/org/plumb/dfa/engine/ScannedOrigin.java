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
package org.plumb.dfa.engine;

import java.util.Map;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;

/**
 * Re-stamps the origin of the functions a scan was ASKED to look at.
 *
 * <p><b>Why this exists.</b> The engine analyses a function body only when its origin is in the
 * configured set, and the default set is {@link SourceOrigin#SOURCE} alone. The bytecode front end
 * marks everything it lowers as {@link SourceOrigin#LIBRARY}, because a class file carries no
 * evidence of whether it is the code under review or a dependency of it. The consequence was that a
 * scan of a JAR produced no findings at all: every function body was skipped before a checker could
 * look at a single instruction. A scan that analyses nothing and reports nothing is
 * indistinguishable from a clean artifact, and that is the one outcome a scanner must never produce
 * silently.
 *
 * <p><b>Why the caller decides, not this class.</b> The bytecode front end cannot tell a
 * third-party dependency from the artifact the user asked about -- both arrive as class files. The
 * caller can: a JAR the user passed on the command line IS the thing under review, while a JAR
 * pulled off the classpath is a dependency. So the front end says {@code LIBRARY} (the safe,
 * conservative answer) and the scan assembly says {@code SOURCE} for the inputs the user named.
 *
 * <p><b>What it changes and what it does not.</b> Only the origin changes: the instructions, the
 * types, the language and the precision of a function are carried over untouched, so the analysis
 * sees exactly the IR the front end produced. The re-stamp is a new function object; the original
 * is left alone for callers that want to see what the front end said.
 */
public final class ScannedOrigin {

  private ScannedOrigin() {}

  /**
   * The function, re-stamped with the given origin.
   *
   * @param function the function as the front end produced it
   * @param origin the origin to give it
   * @return the re-stamped function, or the original when it already has that origin
   */
  public static TacFunction restamp(TacFunction function, SourceOrigin origin) {
    if (function == null) return null;
    if (function.origin() == origin) return function;
    return new TacFunction(
        function.name(),
        function.signature(),
        function.returnType(),
        function.params(),
        function.locals(),
        function.instructions(),
        function.classAnnotations(),
        function.methodAnnotations(),
        function.fieldAnnotations(),
        function.paramAnnotations(),
        function.enclosingClass(),
        origin,
        function.tryRegions(),
        function.sourceLanguage(),
        function.irPrecision(),
        function.isSynthetic(),
        function.annotationValues(),
        function.module());
  }

  /**
   * A program holding the re-stamped functions.
   *
   * <p>The class hierarchy and the lambda bindings of the original program are carried over, so the
   * re-stamped program resolves virtual calls and lambda parameters exactly as the original did.
   *
   * <p>The functions are keyed on their enclosing class as well as their signature, because the
   * plain signature key collapses two methods that share a name and an arity in different classes
   * into one -- and the survivor is whichever was added last. That is the same reason the merged
   * program keys on both.
   *
   * @param program the program as the front end produced it
   * @param origin the origin to give the functions that need one
   * @return a program with the re-stamped functions
   */
  public static TacProgram restampProgram(TacProgram program, SourceOrigin origin) {
    TacProgram out = new TacProgram(program.sourcePath(), program.typeName());
    for (TacFunction function : program.functions()) {
      out.addFunctionByKey(mergedKeyOf(function), restamp(function, origin));
    }
    for (Map.Entry<String, String> parent : program.classToParent().entrySet()) {
      out.registerClassParent(parent.getKey(), parent.getValue());
    }
    for (Map.Entry<String, String> binding : program.lambdaBindings().entrySet()) {
      out.bindLambda(binding.getKey(), binding.getValue());
    }
    return out;
  }

  /**
   * The key a function is stored under: its enclosing class and its signature.
   *
   * @param function the function
   * @return the key
   */
  private static String mergedKeyOf(TacFunction function) {
    String enclosing = function.enclosingClass();
    if (enclosing == null || enclosing.isEmpty()) {
      return function.signature();
    }
    return enclosing + " :: " + function.signature();
  }
}
