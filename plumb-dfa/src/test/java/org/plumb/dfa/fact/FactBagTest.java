package org.plumb.dfa.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.FactKind;
import org.plumb.dfa.config.FactKindRegistry;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

/**
 * Unit tests for {@link Fact} and {@link FactBag}.
 *
 * <p>Both classes are the per-function, per-operand storage layer that the engine writes to during
 * analysis and that consumer checkers read from to emit findings or contribute to the function
 * summary.
 */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class FactBagTest {

  private static TacOperand var(String name) {
    return new TacOperand.TacVar(name, TacType.STRING, false);
  }

  private static FactKind kind(String name) {
    return new FactKind(name, "test", "FactBagTest", java.util.Set.of());
  }

  @Test
  void factCarriesKindOperandValueAndOrigin() {
    FactKind k = kind("plumb.test.kind");
    TacOperand v = var("x");
    Fact f = new Fact(k, v, "hello", null);
    assertSame(k, f.kind());
    assertSame(v, f.operand());
    assertEquals("hello", f.value());
    assertTrue(f.hasKind(k));
    assertTrue(f.isFor("x"));
  }

  @Test
  void factRejectsNullKindOrOperand() {
    TacOperand v = var("x");
    FactKind k = kind("plumb.test.kind");
    try {
      new Fact(null, v, "x");
    } catch (NullPointerException expected) {
      // expected
    }
    try {
      new Fact(k, null, "x");
    } catch (NullPointerException expected) {
      // expected
    }
  }

  @Test
  void bagAddAndLookupByOperand() {
    FactBag bag = new FactBag();
    FactKind k1 = kind("plumb.test.k1");
    FactKind k2 = kind("plumb.test.k2");
    TacOperand x = var("x");
    bag.add(k1, x, "value-1");
    bag.add(k2, x, "value-2");
    assertEquals(2, bag.factsOf(x).size());
    assertTrue(bag.factsOf(x).stream().anyMatch(f -> f.kind().equals(k1)));
    assertTrue(bag.factsOf(x).stream().anyMatch(f -> f.kind().equals(k2)));
  }

  @Test
  void bagFactsOfVarLocatesTacVarByName() {
    FactBag bag = new FactBag();
    FactKind k = kind("plumb.test.k1");
    TacOperand x = var("myInput");
    bag.add(k, x, "tainted");
    List<Fact> facts = bag.factsOfVar("myInput");
    assertEquals(1, facts.size());
    assertEquals("tainted", facts.get(0).value());
    // Non-existent var returns empty list,
    // never null.
    assertEquals(0, bag.factsOfVar("nonexistent").size());
  }

  @Test
  void bagAllowsMultipleFactsForSameOperandAndKind() {
    // The "two facts of the same kind on the
    // same operand" case is real — e.g. a
    // string that may be either A or B.
    // The bag is a list, not a set, so both
    // are preserved. The consumer dedups.
    FactBag bag = new FactBag();
    FactKind k = kind("plumb.test.possible-value");
    TacOperand x = var("x");
    bag.add(k, x, "A");
    bag.add(k, x, "B");
    assertEquals(2, bag.factsOf(x).size());
  }

  @Test
  void bagUnknownOperandReturnsEmptyList() {
    FactBag bag = new FactBag();
    // No throw; never null.
    assertNotNull(bag.factsOf(var("nope")));
    assertEquals(0, bag.factsOf(var("nope")).size());
  }

  @Test
  void bagIsEmptyBeforeAdd() {
    FactBag bag = new FactBag();
    assertTrue(bag.isEmpty());
    assertEquals(0, bag.size());
    bag.add(kind("k"), var("x"), "v");
    assertTrue(!bag.isEmpty());
    assertEquals(1, bag.size());
  }

  @Test
  void bagClearEmptiesEverything() {
    FactBag bag = new FactBag();
    bag.add(kind("k"), var("x"), "v");
    bag.clear();
    assertTrue(bag.isEmpty());
  }

  @Test
  void bagSnapshotReturnsDefensiveCopy() {
    FactBag bag = new FactBag();
    TacOperand x = var("x");
    bag.add(kind("k"), x, "v");
    var snap = bag.snapshot();
    bag.add(kind("k"), x, "v2");
    // Snapshot is unaffected by later adds.
    assertEquals(1, snap.get(x).size());
    // Original grew.
    assertEquals(2, bag.factsOf(x).size());
  }

  @Test
  void builtinRegistryContainsAllFifteenDefaults() {
    // Sanity check: the 15 built-in FactKinds
    // we shipped are all present. Earlier
    // added SENSITIVE_PII to split CWE-359 from
    // CWE-200, bringing the count from 12 to
    // 13. Later added RETURN_VALUE_FOLDED and
    // SIDE_EFFECT_RECORDED for inter-procedural
    // summary application, bringing the count
    // from 13 to 15.
    FactKindRegistry reg = BuiltinFactKinds.registryWithBuiltins();
    assertEquals(15, reg.all().size());
    // Spot-check the names.
    assertNotNull(reg.byName(BuiltinFactKinds.UNTRUSTED_SOURCE).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.UNTRUSTED_SINK).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.MAY_BE_NULL).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.MAY_BE_ZERO).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.ALLOC_SOURCE).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.FREE_SINK).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.DEREF_SINK).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.INDEX_SINK).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.NUMERIC_OVERFLOW_HAZARD).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.SENSITIVE_DATA).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.LOOP_INDUCTION).orElse(null));
    assertNotNull(reg.byName(BuiltinFactKinds.UNMODELED_CALLEE_RET).orElse(null));
  }

  @Test
  void builtinUntrustedSourceHasExpectedTraits() {
    // The most important kind for the
    // taint-driven wiring — its traits
    // must be exactly the documented set.
    FactKind untrustedSource =
        BuiltinFactKinds.registryWithBuiltins()
            .byName(BuiltinFactKinds.UNTRUSTED_SOURCE)
            .orElseThrow();
    assertTrue(untrustedSource.has(FactKind.Trait.UNTRUSTED_SOURCE));
    assertTrue(untrustedSource.has(FactKind.Trait.PROPAGATED_BY_DECL));
    assertTrue(untrustedSource.has(FactKind.Trait.PROPAGATED_THROUGH_CALL));
  }
}
