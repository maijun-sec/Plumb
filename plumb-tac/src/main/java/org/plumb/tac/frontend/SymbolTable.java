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
package org.plumb.tac.frontend;

import java.util.Optional;

/**
 * A cross-language symbol table.
 *
 * <p>Each frontend registers the symbols it declares (classes, methods, their signatures) here
 * during a first pass, then consults the table while translating call sites during a second pass.
 * That is what lets a Java call site resolve to a Kotlin method without the Java frontend knowing
 * anything about Kotlin.
 *
 * <p>The key shape is deliberately the same for every language: an enclosing type (fully qualified)
 * plus a method name plus an arity. That is enough to disambiguate the overwhelming majority of
 * calls, and -- importantly -- it is exactly what Plumb's existing call graph strategies already
 * resolve against.
 */
public interface SymbolTable {

  /**
   * Registers a declared symbol.
   *
   * @param ownerType fully qualified enclosing type (e.g. {@code com.example.Service}); empty for a
   *     top-level function
   * @param methodName the method or property name
   * @param arity the parameter count
   * @param signature the TAC signature of the declaration
   */
  void register(String ownerType, String methodName, int arity, String signature);

  /**
   * Finds a declaration.
   *
   * @param ownerType fully qualified enclosing type, or empty when unknown
   * @param methodName the method name
   * @param arity the parameter count
   * @return the signature when exactly one declaration matches, or empty when none matches or the
   *     match is ambiguous. Ambiguity resolves to "unknown" on purpose: guessing here would produce
   *     a wrong call-graph edge, and a missing edge is safer than a wrong one.
   */
  Optional<String> lookup(String ownerType, String methodName, int arity);

  /**
   * Finds every declaration with this name and arity, regardless of owner.
   *
   * <p>Used when the call site's receiver type could not be resolved (a third-party type whose jar
   * is not on the classpath). Call-graph strategies already implement this "by name and arity"
   * fallback.
   */
  java.util.List<String> lookupByName(String methodName, int arity);
}
