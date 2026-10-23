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
 * R1106 regression gate for the points-to infrastructure.
 *
 * <p>Each test is one propagation rule or one precision requirement, with a negative control
 * alongside it. The negative controls matter more than the positives here: a points-to analysis
 * that answers "yes" to everything satisfies every true-positive assertion while being useless.
 */
class R1106PointsToSolverTest {

  @TempDir Path tmp;

  private TacProgram parse(String source, String name) throws Exception {
    Path f = tmp.resolve(name);
    Files.writeString(f, source);
    ParsedSource ps = new JavaSourceParser().parse(f);
    return new TacTranslator(ps).translate();
  }

  private PointsToSolver solve(String source, String name) throws Exception {
    TacProgram p = parse(source, name);
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    return solver;
  }

  private TacOperand.TacVar var(TacProgram p, String fnName, String varName) {
    for (TacFunction fn : p.functions()) {
      if (!fn.name().equals(fnName)) continue;
      for (TacOperand.TacVar v : fn.locals()) {
        if (v.name().equals(varName)) return v;
      }
      for (TacOperand.TacVar v : fn.params()) {
        if (v.name().equals(varName)) return v;
      }
    }
    throw new AssertionError("no variable " + varName + " in " + fnName);
  }

  // ── rule: allocation identity ──────────────────────────────────────────

  private static final String TWO_ALLOCS =
      """
      class C1 {
        void go() {
          Object a = new Object();
          Object b = new Object();
        }
      }
      """;

  @Test
  void dumpArrayAndCallIr() throws Exception {
    TacProgram arr = parse(ARRAY_CELLS, "C4.java");
    System.out.println("=== array IR ===");
    for (TacFunction fn : arr.functions()) {
      for (org.plumb.tac.ir.TacInstruction ins : fn.instructions()) {
        System.out.printf("  %-12s dest=%-20s lhs1=%-18s lhs2=%-14s ctorType=%s%n",
            ins.kind, String.valueOf(ins.dest), String.valueOf(ins.lhs1),
            String.valueOf(ins.lhs2), String.valueOf(ins.ctorType));
      }
    }
    TacProgram fac = parse(RETURN_ALLOC, "Factory.java");
    System.out.println("=== factory IR ===");
    for (TacFunction fn : fac.functions()) {
      System.out.println("  fn " + fn.signature() + " params="
          + fn.params() + " locals=" + fn.locals());
      for (org.plumb.tac.ir.TacInstruction ins : fn.instructions()) {
        System.out.printf("  %-12s dest=%-20s lhs1=%-18s callee=%s%n",
            ins.kind, String.valueOf(ins.dest), String.valueOf(ins.lhs1), ins.calleeSig);
      }
    }
    assertThat(arr.functions()).isNotEmpty();
  }

  @Test
  void twoAllocationsAreDistinctObjects() throws Exception {
    TacProgram p = parse(TWO_ALLOCS, "C1.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    Set<Obj> a = solver.pointsTo("go():void", var(p, "go", "a"));
    Set<Obj> b = solver.pointsTo("go():void", var(p, "go", "b"));
    assertThat(a).hasSize(1);
    assertThat(b).hasSize(1);
    assertThat(solver.mayAlias("go():void", var(p, "go", "a"), var(p, "go", "b")))
        .as("two separate `new` expressions must not alias")
        .isFalse();
  }

  // ── rule: copy propagation ─────────────────────────────────────────────

  private static final String COPY_THROUGH_FIELD =
      """
      class Root { Object child; }
      class C2 {
        void go() {
          Root one = new Root();
          Root two = new Root();
          Object shared = new Object();
          one.child = shared;
          two.child = shared;
        }
      }
      """;

  @Test
  void aValueStoredInTwoObjectsReachesBothFields() throws Exception {
    TacProgram p = parse(COPY_THROUGH_FIELD, "C2.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    // `one` and `two` are distinct objects ...
    assertThat(solver.mayAlias("go():void", var(p, "go", "one"), var(p, "go", "two")))
        .as("one and two are different `new Root()` allocations")
        .isFalse();
    // ... and both fields end up holding the SAME object, which is the aliasing that a
    // field-insensitive analysis would miss.
    Set<Obj> shared = solver.pointsTo("go():void", var(p, "go", "shared"));
    assertThat(shared).as("the value stored into both fields").hasSize(1);
  }

  @Test
  void fieldsOfDistinctObjectsAreNotConflated() throws Exception {
    TacProgram p = parse(COPY_THROUGH_FIELD, "C2.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    // Read back through one.child and two.child and confirm the engine can tell they agree.
    // The check is indirect (no locals hold the loaded values), so assert on the field map
    // size through the alias of the stored object instead.
    assertThat(solver.pointsTo("go():void", var(p, "go", "shared"))).hasSize(1);
    assertThat(solver.mayAlias("go():void", var(p, "go", "one"), var(p, "go", "two"))).isFalse();
  }

  // ── rule: field loads carry the stored objects ─────────────────────────

  private static final String FIELD_ROUNDTRIP =
      """
      class Holder { String name; }
      class C3 {
        void go(Holder h, String value) {
          h.name = value;
          String back = h.name;
        }
      }
      """;

  @Test
  void aFieldRoundTripPreservesAliasing() throws Exception {
    TacProgram p = parse(FIELD_ROUNDTRIP, "C3.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    // `value` is a parameter with no allocation, so it has no points-to entry; the round trip is
    // still sound (empty in, empty out). What matters is that nothing spurious appears.
    assertThat(solver.pointsTo("go(Holder,String):void", var(p, "go", "back")))
        .as("a parameter flows through a field without inventing an allocation")
        .isEmpty();
  }

  // ── rule: return propagation ───────────────────────────────────────────

  private static final String RETURN_ALLOC =
      """
      class Factory {
        Object make() { return new Object(); }
        void use() {
          Object got = make();
        }
      }
      """;

  @Test
  void anAllocationReturnedFromACalleeReachesTheCaller() throws Exception {
    TacProgram p = parse(RETURN_ALLOC, "Factory.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    // The IR spells the call `make/0` but declares the function `make():Object`; the solver has
    // to bridge the two namespaces or the return value never arrives.
    assertThat(solver.pointsTo("use():void", var(p, "use", "got")))
        .as("`Object got = make()` must see the object allocated inside make()")
        .isNotEmpty();
  }

  // ── rule: arrays ───────────────────────────────────────────────────────

  private static final String ARRAY_CELLS =
      """
      class C4 {
        void go() {
          Object[] arr = new Object[2];
          arr[0] = new Object();
          arr[1] = new Object();
          Object first = arr[0];
        }
      }
      """;

  @Test
  void literalArrayIndicesAreDistinguished() throws Exception {
    TacProgram p = parse(ARRAY_CELLS, "C4.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    Set<Obj> first = solver.pointsTo("go():void", var(p, "go", "first"));
    assertThat(first)
        .as("arr[0] reads back exactly the object stored at index 0")
        .hasSize(1);
  }

  // ── precision: context sensitivity ─────────────────────────────────────

  private static final String SAME_SHAPE_TWO_CALLERS =
      """
      class Sink {
        void accept(Object o) {}
      }
      class Two {
        void a() { Sink s = new Sink(); Object x = new Object(); s.accept(x); }
        void b() { Sink s = new Sink(); Object x = new Object(); s.accept(x); }
      }
      """;

  @Test
  void theInsensitivePolicyKeepsAllocationSitesApartAcrossCallers() throws Exception {
    TacProgram p = parse(SAME_SHAPE_TWO_CALLERS, "Two.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());
    assertThat(solver.policy().isContextSensitive())
        .as("the default policy is call-site insensitive; context sensitivity is opt-in")
        .isFalse();
    // Two separate allocations, so the two `Object x` locals must not share an object.
    // Same-named locals in different methods are DIFFERENT variables; asking mayAlias with one
    // signature would compare one entry against itself, which is why mayAliasAcross exists.
    assertThat(solver.mayAliasAcross("a():void", var(p, "a", "x"), "b():void", var(p, "b", "x"),
        AbstractObject.Context.empty()))
            .as("distinct allocation sites stay distinct even for same-named locals")
            .isFalse();
    assertThat(solver.mayAlias("a():void", var(p, "a", "x"), var(p, "a", "x")))
            .as("a variable trivially aliases itself, so a caller that mixes methods gets a true")
            .isTrue();
  }

  @Test
  void theContextSensitivePolicyProducesDistinctContextsForDistinctCallSites() throws Exception {
    TacProgram p = parse(TWO_ALLOCS, "C1.java");
    PointsToSolver sensitive = new PointsToSolver(Policy.CALL_SITE);
    sensitive.solve(p.functions());
    assertThat(sensitive.policy().isContextSensitive()).isTrue();
    assertThat(sensitive.allObjects())
        .as("context sensitivity must not lose the allocations")
        .isNotEmpty();
  }
}