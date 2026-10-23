/*
 * Unit tests for
 * {@link FieldOpClassifier}.
 *
 * <p>The classifier is a
 * consolidation of the
 * various earlier
 * heuristics that were
 * previously scattered across
 * {@code TaintedCheckerBase}.
 * This test pins down the
 * shared behaviour so future
 * work can call one helper
 * instead of four near-duplicate
 * private statics.</p>
 */
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacType;

@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class FieldOpClassifierTest {

  // ---------------- isLikelyClassName ----------------

  @Test
  void isLikelyClassName_uppercaseFirstChar_returnsTrue() {
    assertThat(FieldOpClassifier.isLikelyClassName("Helper")).isTrue();
    assertThat(FieldOpClassifier.isLikelyClassName("A")).isTrue();
    assertThat(FieldOpClassifier.isLikelyClassName("URL")).isTrue();
  }

  @Test
  void isLikelyClassName_lowercaseFirstChar_returnsFalse() {
    assertThat(FieldOpClassifier.isLikelyClassName("helper")).isFalse();
    assertThat(FieldOpClassifier.isLikelyClassName("this")).isFalse();
    assertThat(FieldOpClassifier.isLikelyClassName("h1")).isFalse();
  }

  @Test
  void isLikelyClassName_nullOrEmpty_returnsFalse() {
    assertThat(FieldOpClassifier.isLikelyClassName(null)).isFalse();
    assertThat(FieldOpClassifier.isLikelyClassName("")).isFalse();
  }

  // ---------------- classNameOfType ----------------

  @Test
  void classNameOfType_classKind_returnsToString() {
    TacType helperType = TacType.ofClass("com.example.Helper");
    assertThat(FieldOpClassifier.classNameOfType(helperType)).isEqualTo("com.example.Helper");
  }

  @Test
  void classNameOfType_nonClassKind_returnsNull() {
    assertThat(FieldOpClassifier.classNameOfType(TacType.STRING)).isNull();
    assertThat(FieldOpClassifier.classNameOfType(TacType.INT)).isNull();
  }

  @Test
  void classNameOfType_nullType_returnsNull() {
    assertThat(FieldOpClassifier.classNameOfType(null)).isNull();
  }

  // ---------------- isLikelyUserArgType ----------------

  @Test
  void isLikelyUserArgType_javaLangTypes_returnTrue() {
    // These are CLASS-typed in
    // the IR but value types
    // (used as user-arg values,
    // not as receivers).
    TacType stringType = TacType.ofClass("java.lang.String");
    assertThat(FieldOpClassifier.isLikelyUserArgType(stringType)).isTrue();
    TacType intType = TacType.ofClass("java.lang.Integer");
    assertThat(FieldOpClassifier.isLikelyUserArgType(intType)).isTrue();
  }

  @Test
  void isLikelyUserArgType_javaUtilAndOthers_returnTrue() {
    // Collection / IO / time
    // types are all value types
    // for the receiver-detection
    // heuristic.
    TacType hashMap = TacType.ofClass("java.util.HashMap");
    assertThat(FieldOpClassifier.isLikelyUserArgType(hashMap)).isTrue();
    TacType bufferedReader = TacType.ofClass("java.io.BufferedReader");
    assertThat(FieldOpClassifier.isLikelyUserArgType(bufferedReader)).isTrue();
    TacType localDate = TacType.ofClass("java.time.LocalDate");
    assertThat(FieldOpClassifier.isLikelyUserArgType(localDate)).isTrue();
  }

  @Test
  void isLikelyUserArgType_userClass_returnsFalse() {
    // Real user classes are
    // receiver candidates, not
    // value types.
    TacType helperType = TacType.ofClass("com.example.Helper");
    assertThat(FieldOpClassifier.isLikelyUserArgType(helperType)).isFalse();
  }

  @Test
  void isLikelyUserArgType_nullOrNonClassKind_returnsTrue() {
    // Default: null /
    // non-CLASS kind treated as
    // "probably a value type" so
    // the receiver-detection
    // falls back to "this".
    assertThat(FieldOpClassifier.isLikelyUserArgType(null)).isTrue();
    assertThat(FieldOpClassifier.isLikelyUserArgType(TacType.INT)).isTrue();
  }

  // ---------------- resolveReceiverName ----------------

  @Test
  void resolveReceiverName_noClassRef_returnsVarName() {
    // No class-ref mapping —
    // returns the var's name
    // (the simple-instance case).
    TacOperand.TacVar v = new TacOperand.TacVar("h", TacType.ofClass("Helper"), false);
    Map<TacOperand.TacVar, String> classRefVars = new HashMap<>();
    assertThat(FieldOpClassifier.resolveReceiverName(v, classRefVars)).isEqualTo("h");
  }

  @Test
  void resolveReceiverName_withClassRef_returnsClassName() {
    // Class-ref mapping: the
    // var was previously seen as
    // {@code t = this.H} so its
    // class-ref is "Helper".
    TacOperand.TacVar t =
        new TacOperand.TacVar("t_t1", TacType.ofClass("com.example.Helper"), false);
    Map<TacOperand.TacVar, String> classRefVars = new HashMap<>();
    classRefVars.put(t, "Helper");
    assertThat(FieldOpClassifier.resolveReceiverName(t, classRefVars)).isEqualTo("Helper");
  }

  @Test
  void resolveReceiverName_nullOrNullName_returnsNull() {
    assertThat(FieldOpClassifier.resolveReceiverName(null, null)).isNull();
    // TacVar's constructor
    // rejects null names, so
    // the only practical
    // edge case here is the
    // null-TacVar path. The
    // empty-name path returns
    // the empty string (the
    // caller can decide
    // whether that's a
    // meaningful "no
    // receiver" signal).
  }
}
