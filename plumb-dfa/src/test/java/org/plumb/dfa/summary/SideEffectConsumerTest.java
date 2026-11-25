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

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.cg.CallGraph;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The side-effect invalidation log: the contract checkers read it by.
 *
 * <p><b>Why this class exists.</b> {@link SideEffectConsumer} records, for every call the engine
 * observes, which operands a callee invalidated -- and the checkers ask it with a STRING key
 * ({@code wasInvalidated("this:foo")}). That string contract is written down in the class javadoc
 * and nowhere else, and the class had no test at all: the archived ones could not be compiled, and
 * the live tree never replaced them.
 *
 * <p><b>Why the contract matters.</b> A checker that asks for {@code "this:foo"} while the consumer
 * recorded {@code "this:foo"} sees the invalidation; a mismatch in either spelling turns every
 * invalidation into silence, and the silence is a false negative that no other gate notices. The
 * key spelling is therefore part of the contract, not an implementation detail.
 *
 * <p><b>What is NOT covered here.</b> {@code recordCall} is driven through a real analysis context,
 * which needs a program, a call graph and a configuration; the cases below use the engine's own
 * configuration so that the fact kind is the registered one. What this guard does NOT pin is the
 * engine's own wiring -- that the engine calls {@code recordCall} at every CALL instruction is the
 * engine's business, and a change there shows up as a different finding count, not as a failure
 * here.
 *
 * <p>One case is deliberately a BEHAVIOUR and not a branch: a "pure" callee invalidates nothing.
 * The consumer's own {@code "pure"} test in {@code recordCall} is redundant -- {@code normalise}
 * returns no key for a value with no {@code writes-} prefix, so the value would be dropped anyway
 * -- and a guard that pinned that branch would be pinning redundancy.
 */
class SideEffectConsumerTest {

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, TacType.INT, false);
  }

  private static TacInstruction call(TacOperand dest, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .dest(dest)
        .calleeSig("some/Caller.method/0")
        .args(List.of())
        .line(line)
        .build();
  }

  private static AnalysisContext context() {
    TacProgram program = new TacProgram(Paths.get("N.java"), "test");
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    CallGraph cg = new CallGraph(Set.of(), Map.of(), Map.of(), List.of(), Set.of());
    return new AnalysisContext(program, cg, cfg, new FindingReporter());
  }

  private static FactKind sideEffectKind(AnalysisContext ctx) {
    return ctx.config().factKinds().byName(BuiltinFactKinds.SIDE_EFFECT_RECORDED).orElseThrow();
  }

  // ------------------------------------------------------------------ key spelling

  @Test
  void aWriteToAFieldOfThisBecomesAThisKey() {
    assertThat(SideEffectConsumer.normalise("writes-this:count"))
        .as("the javadoc says `writes-this:<name>` becomes `this:<name>`")
        .isEqualTo("this:count");
  }

  @Test
  void aWriteToAParameterBecomesAParamKey() {
    assertThat(SideEffectConsumer.normalise("writes-param:0"))
        .as("the javadoc says `writes-param:<i>` becomes `param:<i>`")
        .isEqualTo("param:0");
  }

  @Test
  void aWriteToAStaticFieldBecomesAStaticKey() {
    assertThat(SideEffectConsumer.normalise("writes-static:com.example.Counter.value"))
        .as("the javadoc says `writes-static:<FQCN.f>` becomes `static:<FQCN.f>`")
        .isEqualTo("static:com.example.Counter.value");
  }

  @Test
  void aFlagThatInvalidatesNoOperandHasNoKey() {
    assertThat(SideEffectConsumer.normalise("may-throw"))
        .as("a throwing call invalidates no specific operand")
        .isNull();
    assertThat(SideEffectConsumer.normalise("calls:com.example.Other.do/0"))
        .as("a nested call invalidates no specific operand")
        .isNull();
  }

  @Test
  void anUnknownFlagHasNoKey() {
    assertThat(SideEffectConsumer.normalise("writes-something:else"))
        .as("a flag nobody documents must not invent a key")
        .isNull();
  }

  @Test
  void aNullFlagHasNoKey() {
    assertThat(SideEffectConsumer.normalise(null)).isNull();
  }

  // ------------------------------------------------------------------ the log

  @Test
  void aFreshConsumerHasInvalidatedNothing() {
    SideEffectConsumer consumer = new SideEffectConsumer();
    assertThat(consumer.wasInvalidated("this:count")).isFalse();
    assertThat(consumer.invalidationKeys()).isEmpty();
    assertThat(consumer.invalidationSitesFor("this:count")).isEmpty();
    assertThat(consumer.invalidationCount()).isZero();
  }

  @Test
  void aRecordedCallInvalidatesTheOperandItWrote() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "writes-this:count");

    SideEffectConsumer consumer = new SideEffectConsumer();
    consumer.recordCall(call(dest, 42), ctx);

    assertThat(consumer.wasInvalidated("this:count"))
        .as("the call wrote `this.count`, so the facts about `this.count` are stale")
        .isTrue();
    assertThat(consumer.wasInvalidated("this:other"))
        .as("the call wrote `this.count`, not `this.other`")
        .isFalse();
    assertThat(consumer.invalidationKeys()).containsExactly("this:count");
    assertThat(consumer.invalidationSitesFor("this:count"))
        .as("the site is the call that caused it")
        .containsExactly("call@42");
    assertThat(consumer.invalidationCount()).isEqualTo(1);
  }

  @Test
  void aPureCallInvalidatesNothing() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "pure");

    SideEffectConsumer consumer = new SideEffectConsumer();
    consumer.recordCall(call(dest, 7), ctx);

    assertThat(consumer.invalidationKeys()).as("a pure callee changed nothing").isEmpty();
    assertThat(consumer.invalidationCount()).isZero();
  }

  @Test
  void aCallThatOnlyThrowsInvalidatesNothing() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "may-throw,calls:com.example.Other.do/0");

    SideEffectConsumer consumer = new SideEffectConsumer();
    consumer.recordCall(call(dest, 8), ctx);

    assertThat(consumer.invalidationKeys()).as("neither of those flags names an operand").isEmpty();
  }

  @Test
  void severalCallsOnTheSameOperandAreAllRecorded() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "writes-this:count");

    SideEffectConsumer consumer = new SideEffectConsumer();
    consumer.recordCall(call(dest, 1), ctx);
    consumer.recordCall(call(dest, 2), ctx);

    assertThat(consumer.invalidationSitesFor("this:count"))
        .as("both call sites invalidated the same operand")
        .containsExactly("call@1", "call@2");
    assertThat(consumer.invalidationCount()).isEqualTo(2);
  }

  @Test
  void clearingRemovesTheWholeLog() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "writes-this:count");

    SideEffectConsumer consumer = new SideEffectConsumer();
    consumer.recordCall(call(dest, 3), ctx);
    assertThat(consumer.wasInvalidated("this:count")).isTrue();

    consumer.clear();

    assertThat(consumer.wasInvalidated("this:count"))
        .as("the consumer is per-function, so the log starts empty again")
        .isFalse();
    assertThat(consumer.invalidationKeys()).isEmpty();
    assertThat(consumer.invalidationCount()).isZero();
  }

  // ------------------------------------------------------------------ the read side

  @Test
  void factsSurviveWhileNothingInvalidatedThem() {
    AnalysisContext ctx = context();
    TacOperand.TacVar dest = var("receiver");
    ctx.factBag().add(sideEffectKind(ctx), dest, "writes-this:count");

    SideEffectConsumer consumer = new SideEffectConsumer();

    assertThat(consumer.liveFactsOf(dest, ctx.factBag()))
        .as("nothing invalidated this operand, so its facts are still live")
        .hasSize(1);
  }

  @Test
  void aMissingOperandOrBagYieldsNoFacts() {
    SideEffectConsumer consumer = new SideEffectConsumer();
    assertThat(consumer.liveFactsOf(null, null)).isEmpty();
    assertThat(consumer.liveFactsOf(var("x"), null)).isEmpty();
  }
}
