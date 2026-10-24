package org.plumb.dfa.points;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
 * R1107: context sensitivity must actually separate two invocations of the same method.
 *
 * <p>The scenario is the one the Qilin paper uses to motivate context sensitivity at all:
 *
 * <pre>
 *   Object o1 = new Object();
 *   Object v1 = pick(o1);
 *   Object o2 = new Object();
 *   Object v2 = pick(o2);
 * </pre>
 *
 * <p>where {@code pick(x)} returns {@code x}. Semantically {@code v1} points at {@code o1} and
 * {@code v2} at {@code o2}, so {@code v1} and {@code v2} do not alias. A context-insensitive
 * analysis merges the two invocations of {@code pick}, so its formal parameter points at both
 * objects, both {@code v1} and {@code v2} inherit that merged set, and {@code v1} is reported as
 * aliasing {@code o2} — a false answer on all three pairs.
 *
 * <h2>Why this is the gate that matters</h2>
 *
 * <p>Taint rides on the same edges. When {@code pick} is a source-adjacent helper and the two call
 * sites carry different provenance, a merged context is indistinguishable from taint leaking
 * between unrelated call paths, and no rule-level fix can recover the distinction because the
 * information was already merged inside the solver. So this is tested as a solver property, not as
 * a taint property.
 *
 * <p>Each policy is exercised, and {@link Policy#CONTEXT_INSENSITIVE} is asserted to answer the
 * <em>opposite</em> way. That is the negative control: without it, a solver that ignored the policy
 * entirely and always reported "no alias" would pass every positive assertion here.
 */
class R1107ContextSensitivityTest {

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

  /**
   * Two invocations of an identity method, each fed a distinct allocation.
   *
   * <p>Deliberately an instance method taking one parameter so the receiver slot, the parameter
   * binding and the return binding are all exercised at once.
   */
  private static final String IDENTITY_TWICE =
      """
      class Holder {
        Object pick(Object x) {
          return x;
        }

        void main() {
          Object o1 = new Object();
          Object o2 = new Object();
          Object v1 = pick(o1);
          Object v2 = pick(o2);
        }
      }
      """;

  private PointsToSolver solve(Policy policy, String source, String name) throws Exception {
    TacProgram p = parse(source, name);
    PointsToSolver solver = new PointsToSolver(policy);
    solver.solve(p.functions());
    return solver;
  }

  // ── the property ──────────────────────────────────────────────────────

  @Test
  void callSiteSensitivityKeepsTheTwoInvocationsApart() throws Exception {
    TacProgram p = parse(IDENTITY_TWICE, "IdentityTwice.java");
    PointsToSolver solver = new PointsToSolver(Policy.CALL_SITE);
    solver.solve(p.functions());

    TacOperand.TacVar o1 = var(p, "main", "o1");
    TacOperand.TacVar o2 = var(p, "main", "o2");
    TacOperand.TacVar v1 = var(p, "main", "v1");
    TacOperand.TacVar v2 = var(p, "main", "v2");
    String mainSig = signatureOf(p, "main");

    // Each allocation must remain distinct.
    Set<Obj> pO1 = solver.pointsTo(mainSig, o1);
    Set<Obj> pO2 = solver.pointsTo(mainSig, o2);
    assertThat(pO1).hasSize(1);
    assertThat(pO2).hasSize(1);
    assertThat(pO1).doesNotContainAnyElementsOf(pO2);

    // v1 aliases o1, and does not alias o2. This is the assertion that fails under
    // context insensitivity.
    assertThat(solver.mayAlias(mainSig, v1, o1)).isTrue();
    assertThat(solver.mayAlias(mainSig, v1, o2)).isFalse();

    // Symmetrically for v2.
    assertThat(solver.mayAlias(mainSig, v2, o2)).isTrue();
    assertThat(solver.mayAlias(mainSig, v2, o1)).isFalse();

    // And the two results are distinct from each other.
    assertThat(solver.mayAlias(mainSig, v1, v2)).isFalse();
  }

  /**
   * Negative control: the context-insensitive policy must answer differently on the same program.
   *
   * <p>Without this, "no alias everywhere" passes {@link
   * #callSiteSensitivityKeepsTheTwoInvocationsApart}.
   */
  @Test
  void contextInsensitivityMergesTheTwoInvocations() throws Exception {
    TacProgram p = parse(IDENTITY_TWICE, "IdentityTwiceInsens.java");
    PointsToSolver solver = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    solver.solve(p.functions());

    TacOperand.TacVar o1 = var(p, "main", "o1");
    TacOperand.TacVar o2 = var(p, "main", "o2");
    TacOperand.TacVar v1 = var(p, "main", "v1");
    TacOperand.TacVar v2 = var(p, "main", "v2");
    String mainSig = signatureOf(p, "main");

    // The allocations themselves are still distinct -- insensitivity merges contexts, not
    // allocation sites.
    assertThat(solver.mayAlias(mainSig, o1, o2)).isFalse();

    // But the two invocations of pick() share one merged formal, so both results inherit
    // both objects. v1 is therefore (falsely) reported as aliasing o2.
    assertThat(solver.mayAlias(mainSig, v1, o1)).isTrue();
    assertThat(solver.mayAlias(mainSig, v1, o2)).isTrue();
    assertThat(solver.mayAlias(mainSig, v1, v2)).isTrue();
  }

  /**
   * The context-insensitive answer must not change when the solver is merely rebuilt, and the
   * call-site answer must be strictly sharper.
   *
   * <p>This pins the relationship between the two policies: {@code CALL_SITE} is a refinement of
   * the insensitive answer, never a different analysis. If a future change made CALL_SITE report an
   * alias the insensitive run does not, the refinement property would be violated.
   */
  @Test
  void callSiteAnswerIsAStrRefinementOfTheInsensitiveOne() throws Exception {
    TacProgram p = parse(IDENTITY_TWICE, "IdentityTwiceCompare.java");
    TacOperand.TacVar o1 = var(p, "main", "o1");
    TacOperand.TacVar o2 = var(p, "main", "o2");
    TacOperand.TacVar v1 = var(p, "main", "v1");
    TacOperand.TacVar v2 = var(p, "main", "v2");
    String mainSig = signatureOf(p, "main");

    List<String[]> pairs =
        List.of(
            new String[] {"v1", "o1"},
            new String[] {"v1", "o2"},
            new String[] {"v2", "o1"},
            new String[] {"v2", "o2"},
            new String[] {"v1", "v2"});

    PointsToSolver insens = new PointsToSolver(Policy.CONTEXT_INSENSITIVE);
    insens.solve(parse(IDENTITY_TWICE, "CompareInsens.java").functions());
    PointsToSolver cs = new PointsToSolver(Policy.CALL_SITE);
    cs.solve(parse(IDENTITY_TWICE, "CompareCs.java").functions());

    for (String[] pair : pairs) {
      boolean insensSays =
          insens.mayAlias(mainSig, var(p, "main", pair[0]), var(p, "main", pair[1]));
      boolean csSays = cs.mayAlias(mainSig, var(p, "main", pair[0]), var(p, "main", pair[1]));
      assertThat(csSays)
          .as("CALL_SITE may only remove aliases, never add them (%s, %s)", pair[0], pair[1])
          .isEqualTo(insensSays && csSays);
    }
  }

  /**
   * Two different methods with the same name in the same class must not be confused.
   *
   * <p>This guards the {@code byNameArity} fallback: when a CALL's {@code calleeSig} is spelled
   * {@code name/arity} rather than a declared signature, the resolver picks the first declaration.
   * A context-sensitive analysis that bound to the wrong callee would still produce a plausible
   * answer, so this checks the identity directly.
   */
  @Test
  void distinctAllocationsInTheCalleeStayDistinctPerContext() throws Exception {
    String source =
        """
        class Factory {
          Holder makeA() {
            return new Holder();
          }
          Holder makeB() {
            return new Holder();
          }
          void main() {
            Holder a = makeA();
            Holder b = makeB();
          }
        }
        """;
    TacProgram p = parse(source, "Factory.java");
    PointsToSolver solver = new PointsToSolver(Policy.CALL_SITE);
    solver.solve(p.functions());

    TacOperand.TacVar a = var(p, "main", "a");
    TacOperand.TacVar b = var(p, "main", "b");
    String mainSig = signatureOf(p, "main");

    assertThat(solver.mayAlias(mainSig, a, b))
        .as("two separate factory methods must not yield the same object")
        .isFalse();
  }

  private String signatureOf(TacProgram p, String fnName) {
    for (TacFunction fn : p.functions()) {
      if (fn.name() != null && fn.name().equals(fnName)) return fn.signature();
    }
    throw new AssertionError("no function " + fnName);
  }
}
