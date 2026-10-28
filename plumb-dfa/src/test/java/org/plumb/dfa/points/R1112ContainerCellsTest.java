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
package org.plumb.dfa.points;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.points.AbstractObject.Obj;
import org.plumb.dfa.points.AbstractObject.Policy;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1112: Map and List must be modelled as store/load edges, keyed by the access site's literal.
 *
 * <h2>Why this is not the same as an array</h2>
 *
 * <p>A Java container is reached through a CALL, so {@code store.get("k")} carries no indexed
 * instruction for the heap model to hook. Without a dedicated rule the retrieved reference simply
 * has no points-to set, and every question asked of it -- "is this the object I checked?" -- is
 * unanswerable. That is why the container case was previously measured as a gap rather than worked
 * around.
 *
 * <h2>What the keying buys, and what it cannot</h2>
 *
 * <p>The TAC gives both the key and the index as literals at the access site, so entries are kept
 * apart: a guard on one key must not silence a dereference of a different one. That distinction is
 * the difference between a precise answer and a wrong "safe", and both controls below exist to keep
 * it.
 *
 * <p>{@code List.add} cannot know the index it will occupy, so it writes the {@code ANY_KEY} cell
 * and two elements of one list are not separable. That is recorded in the positive test's own
 * wording rather than being left to be discovered.
 */
class R1112ContainerCellsTest {

  @TempDir Path tmp;

  private TacProgram parse(String source, String name) throws Exception {
    Path f = tmp.resolve(name);
    Files.writeString(f, source);
    ParsedSource ps = new JavaSourceParser().parse(f);
    return new TacTranslator(ps).translate();
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

  private String sig(TacProgram p, String fnName) {
    for (TacFunction fn : p.functions()) {
      if (fn.name() != null && fn.name().equals(fnName)) return fn.signature();
    }
    throw new AssertionError("no function " + fnName);
  }

  private PointsToSolver solve(TacProgram p) {
    PointsToSolver solver = new PointsToSolver(Policy.CALL_SITE);
    solver.solve(p.functions());
    return solver;
  }

  // ── the capability ────────────────────────────────────────────────────

  /** A value put into a map and read back out is the same object. */
  @Test
  void aMapRoundTripReturnsTheStoredObject() throws Exception {
    String src =
        """
        import java.util.*;
        class C1 {
          void go(String input) {
            Map<String, StringBuilder> store = new HashMap<>();
            StringBuilder a = new StringBuilder(input);
            store.put("k", a);
            StringBuilder b = store.get("k");
          }
        }
        """;
    TacProgram p = parse(src, "C1.java");
    PointsToSolver solver = solve(p);
    String sig = sig(p, "go");

    Set<Obj> stored = solver.pointsTo(sig, var(p, "go", "a"));
    Set<Obj> fetched = solver.pointsTo(sig, var(p, "go", "b"));

    assertThat(stored).as("the allocated object must be tracked").isNotEmpty();
    assertThat(fetched)
        .as("the retrieved reference must carry the stored object; before R1112 this was empty")
        .isNotEmpty();
    assertThat(solver.mayAlias(sig, var(p, "go", "a"), var(p, "go", "b")))
        .as("a round trip through a map key returns the stored reference")
        .isTrue();
  }

  /** The same for a list, with the ANY_KEY caveat stated rather than hidden. */
  @Test
  void aListRoundTripReturnsAStoredObject() throws Exception {
    String src =
        """
        import java.util.*;
        class C2 {
          void go(String input) {
            List<StringBuilder> items = new ArrayList<>();
            StringBuilder a = new StringBuilder(input);
            items.add(a);
            StringBuilder b = items.get(0);
          }
        }
        """;
    TacProgram p = parse(src, "C2.java");
    PointsToSolver solver = solve(p);
    String sig = sig(p, "go");

    assertThat(solver.pointsTo(sig, var(p, "go", "b")))
        .as(
            "List.add writes the ANY_KEY cell because the index is not known at the call, so get(0)"
                + " sees it; the consequence is that two elements of one list are not separable")
        .isNotEmpty();
    assertThat(solver.mayAlias(sig, var(p, "go", "a"), var(p, "go", "b"))).isTrue();
  }

  // ── controls: the keying must not over-claim ──────────────────────────

  /**
   * Two different keys are two different cells.
   *
   * <p>This is the control that matters most. A single merged cell per container would make {@code
   * get("b")} see {@code put("a", ..)}'s value, and then a guard on one entry would silence a
   * dereference of an unrelated entry -- a false "safe" produced by imprecision rather than by a
   * real alias.
   */
  @Test
  void twoDifferentKeysDoNotShareACell() throws Exception {
    String src =
        """
        import java.util.*;
        class C3 {
          void go(String input) {
            Map<String, StringBuilder> store = new HashMap<>();
            StringBuilder a = new StringBuilder(input);
            StringBuilder other = new StringBuilder("other");
            store.put("a", a);
            store.put("b", other);
            StringBuilder fetchedB = store.get("b");
          }
        }
        """;
    TacProgram p = parse(src, "C3.java");
    PointsToSolver solver = solve(p);
    String sig = sig(p, "go");

    Set<Obj> fetched = solver.pointsTo(sig, var(p, "go", "fetchedB"));
    Set<Obj> underA = solver.pointsTo(sig, var(p, "go", "a"));

    assertThat(fetched).as("get(\"b\") must see the object stored under \"b\"").isNotEmpty();
    assertThat(fetched)
        .as("get(\"b\") must not see the object stored under \"a\"")
        .doesNotContainAnyElementsOf(underA);
  }

  /** Reading a key nobody wrote yields nothing rather than another entry's object. */
  @Test
  void readingAnAbsentKeyYieldsNothing() throws Exception {
    String src =
        """
        import java.util.*;
        class C4 {
          void go(String input) {
            Map<String, StringBuilder> store = new HashMap<>();
            StringBuilder a = new StringBuilder(input);
            store.put("a", a);
            StringBuilder missing = store.get("zzz");
          }
        }
        """;
    TacProgram p = parse(src, "C4.java");
    PointsToSolver solver = solve(p);
    assertThat(solver.pointsTo(sig(p, "go"), var(p, "go", "missing")))
        .as("a key that was never written must not resolve to another entry's object")
        .isEmpty();
  }

  /**
   * A user-defined {@code put} must not be captured by the container rule.
   *
   * <p>The signature matcher keys on the bare method name, so a class of the user's own with a
   * {@code put} method would otherwise be treated as a map and its receiver's cells would come back
   * populated.
   */
  @Test
  void aUserDefinedPutIsNotAContainerOperation() throws Exception {
    String src =
        """
        class Holder {
          StringBuilder stored;
          StringBuilder put(String key, StringBuilder value) {
            this.stored = value;
            return value;
          }
        }
        class C5 {
          void go(String input) {
            Holder h = new Holder();
            StringBuilder a = new StringBuilder(input);
            h.put("k", a);
          }
        }
        """;
    TacProgram p = parse(src, "C5.java");
    PointsToSolver solver = solve(p);
    // The container rule must not invent a cell for a user class; the only objects in play are the
    // two allocations, and neither may acquire a phantom third one.
    assertThat(solver.allObjects())
        .as("a user-defined put must not be modelled as a container store")
        .hasSizeLessThanOrEqualTo(2);
  }
}
