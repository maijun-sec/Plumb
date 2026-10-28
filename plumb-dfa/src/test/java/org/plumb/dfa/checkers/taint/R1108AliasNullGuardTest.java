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
package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1108: a null-ness guard must transfer across aliases.
 *
 * <h2>The claim</h2>
 *
 * <p>{@code if (a != null)} constrains the object {@code a} denotes, not the name {@code a}. If
 * {@code b} may hold that same object, then on this path {@code b} is non-null too. A checker that
 * reasons per-variable-name therefore reports a genuine false positive on:
 *
 * <pre>
 *   if (a == null) return;
 *   b.use();          // b aliases a -> provably non-null here
 * </pre>
 *
 * <h2>Why the direction matters</h2>
 *
 * <p>Every branch of the suppression logic can only conclude "this path already proves the receiver
 * safe". None of them can conclude "this path might make it less safe". That asymmetry is the
 * point: the alias map is conservative, so any use of it that could <em>add</em> findings would
 * fire on unrelated same-type locals and turn a precision fix into a false-positive generator.
 *
 * <p>So the tests below pair every positive with the control that must keep firing.
 */
class R1108AliasNullGuardTest {

  @TempDir Path tmp;

  private TacProgram parse(String source, String name) throws Exception {
    Path f = tmp.resolve(name);
    Files.writeString(f, source);
    ParsedSource ps = new JavaSourceParser().parse(f);
    return new TacTranslator(ps).translate();
  }

  /**
   * Is {@code probe} provably non-null at the point where it is dereferenced?
   *
   * <p>Drives the same GuardTracker + alias query pair the NullDeref checker uses, against real
   * translated IR. Testing the pieces rather than the reporting checker keeps the assertion about
   * the <em>fact</em> (this reference is safe on this path) separate from the reporting policy.
   */
  private boolean provenNonNullAtUse(
      TacProgram p, String fnName, TacOperand.TacVar probe, TacOperand.TacVar useReceiver) {
    org.plumb.dfa.taint.GuardTracker tracker = new org.plumb.dfa.taint.GuardTracker();
    for (TacFunction fn : p.functions()) {
      if (fn.name() == null || !fn.name().equals(fnName)) continue;
      for (TacInstruction ins : fn.instructions()) {
        tracker.track(ins);
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        if (ins.args == null || ins.args.isEmpty()) continue;
        if (!(ins.args.get(0) instanceof TacOperand.TacVar recv)) continue;
        if (!recv.name().equals(useReceiver.name())) continue;
        // The full suppression predicate, minus the checker plumbing: either the receiver itself is
        // guarded, or an alias of it is.
        if (tracker.isProvenNonNull(recv)) return true;
        for (TacOperand.TacVar alias : aliasesOf(p, fn, recv, probe)) {
          if (tracker.isProvenNonNull(alias)) return true;
        }
        return false;
      }
    }
    throw new AssertionError("no call on receiver " + useReceiver.name() + " in " + fnName);
  }

  private java.util.Set<TacOperand.TacVar> aliasesOf(
      TacProgram p, TacFunction fn, TacOperand.TacVar a, TacOperand.TacVar b) {
    org.plumb.dfa.points.PointsToSolver solver =
        new org.plumb.dfa.points.PointsToSolver(
            org.plumb.dfa.points.AbstractObject.Policy.CALL_SITE);
    solver.solve(p.functions());
    java.util.Set<TacOperand.TacVar> out =
        new java.util.LinkedHashSet<>(
            solver.aliasesOf(
                fn.signature(), a, org.plumb.dfa.points.AbstractObject.Context.empty()));
    // Copy equivalence: `b = a` makes the two names the same reference regardless of whether
    // either has a points-to set. A parameter has none by construction (nobody calls the entry
    // method), so the points-to-only answer is empty and the alias question would be unanswerable
    // for exactly the commonest case: a guarded parameter.
    out.addAll(org.plumb.dfa.points.CopyEquivalence.of(p.functions()).classOf(fn.signature(), a));
    return out;
  }

  private TacOperand.TacVar var(TacProgram p, String fnName, String varName) {
    for (TacFunction fn : p.functions()) {
      if (fn.name() == null || !fn.name().equals(fnName)) continue;
      for (TacOperand.TacVar v : fn.locals()) {
        if (v.name().equals(varName)) return v;
      }
      for (TacOperand.TacVar v : fn.params()) {
        if (v.name().equals(varName)) return v;
      }
    }
    throw new AssertionError("no variable " + varName + " in " + fnName);
  }

  // ── the transfer case ─────────────────────────────────────────────────

  /**
   * {@code b} aliases {@code a}, and {@code a} is guarded. The dereference of {@code b} is safe.
   */
  @Test
  void guardOnOneNameCoversItsAlias() throws Exception {
    String source =
        """
        class A1 {
          void sink(String s) {
          }
          void run(String a) {
            String b = a;
            if (a == null) {
              return;
            }
            sink(b);
          }
        }
        """;
    TacProgram p = parse(source, "A1.java");
    TacOperand.TacVar a = var(p, "run", "a");
    TacOperand.TacVar b = var(p, "run", "b");
    assertThat(provenNonNullAtUse(p, "run", a, b))
        .as("b is an alias of the guarded a, so b.use() is safe")
        .isTrue();
  }

  /**
   * The same transfer when the guard is the {@code != null} spelling rather than the early return.
   */
  @Test
  void positiveGuardAlsoCoversTheAlias() throws Exception {
    String source =
        """
        class A2 {
          void sink(String s) {
          }
          void run(String a) {
            String b = a;
            if (a != null) {
              sink(b);
            }
          }
        }
        """;
    TacProgram p = parse(source, "A2.java");
    TacOperand.TacVar a = var(p, "run", "a");
    TacOperand.TacVar b = var(p, "run", "b");
    assertThat(provenNonNullAtUse(p, "run", a, b)).isTrue();
  }

  // ── controls: the transfer must NOT over-reach ────────────────────────

  /**
   * Two distinct allocations of the same type are not aliases. Guarding one must not suppress the
   * other's dereference.
   *
   * <p>This is the control that keeps the alias step honest: if the implementation answered "safe"
   * whenever <em>some</em> same-typed local was guarded, this case would be wrongly suppressed and
   * the rule would lose a true positive.
   */
  @Test
  void guardingADifferentObjectDoesNotSuppress() throws Exception {
    String source =
        """
        class A3 {
          void sink(String s) {
          }
          void run() {
            String a = makeOne();
            String b = makeTwo();
            if (a != null) {
              sink(b);
            }
          }
          String makeOne() {
            return new String();
          }
          String makeTwo() {
            return new String();
          }
        }
        """;
    TacProgram p = parse(source, "A3.java");
    TacOperand.TacVar a = var(p, "run", "a");
    TacOperand.TacVar b = var(p, "run", "b");
    assertThat(provenNonNullAtUse(p, "run", a, b))
        .as("b is a different allocation; guarding a says nothing about b")
        .isFalse();
  }

  /** An unguarded alias must keep reporting. Absence of a guard is not a proof of safety. */
  @Test
  void unguardedAliasIsNotSuppressed() throws Exception {
    String source =
        """
        class A4 {
          void sink(String s) {
          }
          void run(String a, String other) {
            String b = a;
            sink(b);
          }
        }
        """;
    TacProgram p = parse(source, "A4.java");
    TacOperand.TacVar a = var(p, "run", "a");
    TacOperand.TacVar b = var(p, "run", "b");
    assertThat(provenNonNullAtUse(p, "run", a, b)).as("no guard anywhere on this path").isFalse();
  }

  /**
   * An empty alias class must not be read as "no aliases".
   *
   * <p>The receiver here is a parameter, whose points-to set is empty, so the solver reports
   * nothing. Treating that as "provably unshared" would suppress every unguarded parameter
   * dereference — i.e. it would silently disable the rule. The suppression must fall through to
   * reporting.
   */
  @Test
  void anUnknownReceiverIsNotTreatedAsProvablySafe() throws Exception {
    String source =
        """
        class A5 {
          void sink(String s) {
          }
          void run(String a, String other) {
            if (other != null) {
              sink(a);
            }
          }
        }
        """;
    TacProgram p = parse(source, "A5.java");
    TacOperand.TacVar a = var(p, "run", "a");
    TacOperand.TacVar other = var(p, "run", "other");
    assertThat(provenNonNullAtUse(p, "run", other, a))
        .as("a is an unknown parameter; the empty alias class must not suppress the finding")
        .isFalse();
  }
}
