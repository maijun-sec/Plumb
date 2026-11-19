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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.reporter.ModelCoverage;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.translate.TacBuilder;

/**
 * How much of the analysed code the engine actually modelled.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The report carries a ratio, and a ratio that is wrong in the flattering direction is worse
 * than no ratio: it tells the reader the analysis understood more than it did. The number is
 * therefore counted from the IR, and this test pins the counting rules.
 *
 * <h2>What the cases below pin</h2>
 *
 * <ul>
 *   <li>a call to a function the scan analysed is <b>modelled</b> -- the engine derived its effect;
 *   <li>a call the language table names is <b>modelled</b> -- the engine knows what it does;
 *   <li>a call nobody knows anything about is <b>unmodelled</b>;
 *   <li>the buckets are DISJOINT and add up, so the ratio is a ratio of something;
 *   <li>a function the front end could only lower partially counts as below full precision;
 *   <li>an instruction the front end could not model is counted as a HAVOC.
 * </ul>
 *
 * <p>The programs are built by hand rather than lowered from source, so every count in the
 * assertions is exact: there is no hidden call the compiler added and no catalogue entry that
 * changed the result.
 */
@DisplayName("Coverage: how much of the analysed code the engine modelled")
class ModelCoverageTest {

  private static final EngineConfig CONFIG = ConfigLoader.loadFromClasspath();

  @Test
  @DisplayName("a call to a function the scan analysed has a model")
  void callToAnAnalysedFunctionHasABody() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(function("helper", "helper(String):String", builder -> {}));
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder -> builder.emit(call("helper/1", builder, "value", "t0"))));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.callSitesWithBody())
        .as("the call to helper has a body in the scan")
        .isEqualTo(1);
    assertThat(coverage.callSitesUnmodelled()).as("and it is not unmodelled").isZero();
  }

  @Test
  @DisplayName("a call the language table names has a model")
  void callNamedByTheSemanticsTableHasAModel() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder -> builder.emit(call("java.lang.String.valueOf/1", builder, "value", "t0"))));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.callSitesWithSemantics())
        .as("the Java semantics table names java.lang.String.valueOf")
        .isEqualTo(1);
    assertThat(coverage.callSitesUnmodelled()).isZero();
  }

  @Test
  @DisplayName("a call nobody knows anything about has no model")
  void unknownCallHasNoModel() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder -> builder.emit(call("com.acme.Widget.render/1", builder, "value", "t0"))));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.callSitesUnmodelled()).as("nothing knows this callee").isEqualTo(1);
    assertThat(coverage.callSitesWithBody()).isZero();
    assertThat(coverage.callSitesWithSemantics()).isZero();
  }

  @Test
  @DisplayName("a call the front end could not see is a call site with no model")
  void uninterpretedCallIsACallSiteWithoutAModel() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder ->
                builder.emit(
                    TacInstruction.builder(TacInstruction.Kind.UNINTERPRETED)
                        .calleeSig("com.acme.Widget.paint/0")
                        .args(List.of())
                        .dest(new TacOperand.TacVar("t0", TacType.STRING, true))
                        .build())));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.callSites()).as("an UNINTERPRETED is a call site").isEqualTo(1);
    assertThat(coverage.callSitesUnmodelled()).isEqualTo(1);
    assertThat(coverage.uninterpretedInstructions()).isEqualTo(1);
  }

  @Test
  @DisplayName("the buckets are disjoint and add up")
  void theBucketsAddUp() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(function("helper", "helper(String):String", builder -> {}));
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder -> {
              builder.emit(call("helper/1", builder, "value", "t0"));
              builder.emit(call("java.lang.String.valueOf/1", builder, "value", "t1"));
              builder.emit(call("com.acme.Widget.render/1", builder, "value", "t2"));
              builder.emit(call("com.acme.Other.paint/1", builder, "value", "t3"));
            }));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.callSites()).isEqualTo(4);
    assertThat(
            coverage.callSitesWithBody()
                + coverage.callSitesWithSemantics()
                + coverage.callSitesWithPassthrough()
                + coverage.callSitesUnmodelled())
        .as("every call site lands in exactly one bucket")
        .isEqualTo(coverage.callSites());
    assertThat(coverage.callSitesUnmodelled()).isEqualTo(2);
    assertThat(coverage.unmodelledCallRatio())
        .isEqualTo(0.5, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  @DisplayName("a function the front end could only lower partially is below full precision")
  void aLoweredFunctionIsBelowFullPrecision() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(function("whole", "whole(String):String", builder -> {}));
    program.addFunction(
        function(
            "partial",
            "partial(String):String",
            builder -> {
              builder.setIrPrecision(IrPrecision.SOURCE_LOWERED);
            }));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.functions()).isEqualTo(2);
    assertThat(coverage.functionsAtFullPrecision()).isEqualTo(1);
    assertThat(coverage.functionsBelowFullPrecision()).isEqualTo(1);
    assertThat(coverage.fullPrecisionFunctionRatio())
        .isEqualTo(0.5, org.assertj.core.data.Offset.offset(0.001));
  }

  @Test
  @DisplayName("an instruction the front end could not model is counted as a HAVOC")
  void aHavocIsCounted() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder ->
                builder.emit(
                    TacInstruction.builder(TacInstruction.Kind.HAVOC)
                        .dest(new TacOperand.TacVar("t0", TacType.UNKNOWN, true))
                        .build())));

    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(program), CONFIG);

    assertThat(coverage.havocInstructions()).isEqualTo(1);
    assertThat(coverage.instructions()).isEqualTo(1);
    assertThat(coverage.havocInstructionRatio()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("nothing analysed is reported as nothing, not as a clean bill of health")
  void nothingAnalysed() {
    ModelCoverage coverage = ModelCoverageComputer.compute(List.of(), CONFIG);

    assertThat(coverage.functions()).isZero();
    assertThat(coverage.callSites()).isZero();
    assertThat(coverage.callSitesUnmodelled()).isZero();
    assertThat(coverage.unmodelledCallRatio()).isZero();
    assertThat(coverage.summary()).contains("no data flow was analysed");
  }

  @Test
  @DisplayName("the record travels as JSON, with every count named")
  void theRecordTravelsAsJson() {
    TacProgram program = new TacProgram(Path.of("Demo.java"), "Demo");
    program.addFunction(
        function(
            "caller",
            "caller(String):String",
            builder -> builder.emit(call("com.acme.Widget.render/1", builder, "value", "t0"))));

    String json = ModelCoverageComputer.compute(List.of(program), CONFIG).toJson();

    assertThat(json).contains("\"callSites\":1");
    assertThat(json).contains("\"callSitesUnmodelled\":1");
    assertThat(json).contains("\"unmodelledCallRatio\":1.000");
  }

  // ------------------------------------------------------------------ the harness

  /** A function with one string parameter, built by the given body. */
  private static TacFunction function(String name, String signature, Body body) {
    TacOperand.TacVar param = new TacOperand.TacVar("value", TacType.STRING, false);
    TacBuilder builder =
        new TacBuilder(
            new TacBuilder.FunctionSpec(name, signature, TacType.STRING), List.of(param));
    body.accept(builder);
    return builder.build();
  }

  /** A CALL instruction with a receiver-shaped argument list and a fresh destination. */
  private static TacInstruction call(
      String calleeSig, TacBuilder builder, String argument, String dest) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(calleeSig)
        .args(List.of(new TacOperand.TacVar(argument, TacType.STRING, false)))
        .dest(new TacOperand.TacVar(dest, TacType.STRING, true))
        .build();
  }

  /** The body of a function under construction. */
  private interface Body {
    void accept(TacBuilder builder);
  }
}
