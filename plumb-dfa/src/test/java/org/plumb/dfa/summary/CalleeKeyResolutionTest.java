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
package org.plumb.dfa.summary;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.cg.FunctionSignature;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The two ends of a summary lookup have to spell the class the same way.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The engine stores a function's summary under a key it builds from the function; the call site
 * looks that summary up under a key it builds from the IR's callee signature. The two keys have to
 * agree, or the lookup finds nothing -- and a lookup that finds nothing is invisible: the scan
 * still runs and still reports, so the missing fold shows up nowhere.
 *
 * <p>The class is the part that has to agree, and it has to be the class that DECLARES the method.
 * A key without a class in it addresses every same-named method at once, so the summary that comes
 * back may belong to a different method than the one that was called.
 *
 * <h2>What it would catch</h2>
 *
 * <ul>
 *   <li>The engine key and the call key drifting apart: the lookup misses, and the test that
 *       compares them fails.
 *   <li>A key that stops naming the declaring class: two same-named methods of two classes share
 *       one entry again, and the test that compares them fails.
 * </ul>
 */
class CalleeKeyResolutionTest {

  @TempDir Path tmp;

  private static final String CAPS =
      """
      package com.example;

      class Caps {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  private static final String OTHER =
      """
      package com.example;

      class Other {
        private String slot;

        void write(String v) {
          slot = v;
        }
      }
      """;

  private List<TacFunction> functionsOf(String source, String fileName) throws Exception {
    Path file = tmp.resolve(fileName);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    return program.functions();
  }

  /** The two classes, each in its own file, as the program holds them. */
  private List<TacFunction> program() throws Exception {
    List<TacFunction> all = new ArrayList<>();
    all.addAll(functionsOf(CAPS, "Caps.java"));
    all.addAll(functionsOf(OTHER, "Other.java"));
    return all;
  }

  private TacFunction methodOf(List<TacFunction> functions, String className, String methodName) {
    return functions.stream()
        .filter(f -> className.equals(f.enclosingClass()) && methodName.equals(f.name()))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "no method "
                        + className
                        + "."
                        + methodName
                        + " in the translated program; the functions were "
                        + functions.stream()
                            .map(f -> f.enclosingClass() + "." + f.name())
                            .collect(java.util.stream.Collectors.toList())));
  }

  @Test
  @DisplayName("a qualified call is keyed by the receiver's class")
  void aQualifiedCallIsKeyedByTheReceiversClass() {
    FunctionSignature key = SummaryApplier.fallbackParse("Caps.write/1", null);

    assertThat(key).isNotNull();
    assertThat(key.className()).isEqualTo("Caps");
    assertThat(key.method()).isEqualTo("write");
  }

  @Test
  @DisplayName("an unqualified call is keyed by the caller's class")
  void anUnqualifiedCallIsKeyedByTheCallersClass() {
    FunctionSignature key = SummaryApplier.fallbackParse("write/1", "Caps");

    assertThat(key).isNotNull();
    assertThat(key.className()).isEqualTo("Caps");
    assertThat(key.method()).isEqualTo("write");
  }

  @Test
  @DisplayName("a call with no class on either side lands in the class-less bucket")
  void aCallWithNoClassLandsInTheClasslessBucket() {
    FunctionSignature key = SummaryApplier.fallbackParse("write/1", null);

    assertThat(key).isNotNull();
    assertThat(key.className()).isEqualTo(SummaryApplier.NO_CLASS);
  }

  @Test
  @DisplayName("a fully qualified receiver is reduced to its simple name")
  void aFullyQualifiedReceiverIsReducedToItsSimpleName() {
    FunctionSignature key = SummaryApplier.fallbackParse("com.example.Caps.write/1", null);

    assertThat(key).isNotNull();
    assertThat(key.className()).isEqualTo("Caps");
  }

  @Test
  @DisplayName("the engine keys a function by the class that declares it")
  void theEngineKeysAFunctionByItsDeclaringClass() throws Exception {
    List<TacFunction> functions = program();

    FunctionSignature key = DfaEngine.parseFnSignature(methodOf(functions, "Caps", "write"));

    assertThat(key.className()).isEqualTo("Caps");
    assertThat(key.method()).isEqualTo("write");
  }

  @Test
  @DisplayName("two classes' same-named methods are two keys")
  void twoClassesSameNamedMethodsAreTwoKeys() throws Exception {
    List<TacFunction> functions = program();

    FunctionSignature capsWrite = DfaEngine.parseFnSignature(methodOf(functions, "Caps", "write"));
    FunctionSignature otherWrite =
        DfaEngine.parseFnSignature(methodOf(functions, "Other", "write"));

    assertThat(capsWrite.className()).isEqualTo("Caps");
    assertThat(otherWrite.className()).isEqualTo("Other");
    assertThat(capsWrite)
        .as("two same-named methods of two classes must not share one key")
        .isNotEqualTo(otherWrite);
  }

  @Test
  @DisplayName("the engine's key and the call site's key are one key")
  void theTwoEndsOfALookupAgree() throws Exception {
    List<TacFunction> functions = program();

    FunctionSignature engineKey = DfaEngine.parseFnSignature(methodOf(functions, "Caps", "write"));
    FunctionSignature callKey = SummaryApplier.fallbackParse("write/1", "Caps");

    assertThat(callKey)
        .as("the call site and the engine have to build the SAME key, or the lookup finds nothing")
        .isEqualTo(engineKey);
  }
}
