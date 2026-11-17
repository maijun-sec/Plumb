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
package org.plumb.tac.frontend.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.json.Json;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacDrop;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacLabelRef;
import org.plumb.tac.ir.TacMemberKind;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.ir.TacValueDomain;

/**
 * The wire-to-IR mapping of the fields the sidecars now send.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>A field that is filled in on the wire and NOT read on the Java side is the worst kind of loss:
 * the sidecar did the work, the TAC looks complete, and the information is simply gone. Nothing
 * says so. So every field the contract defines gets a test that says "this arrives".
 *
 * <p>One of them was not merely unread but WRONG: a binary operator this build does not know was
 * read as {@code ADD}, so a comparison became an addition. The honest reading of an operator we
 * cannot interpret is "the value is unknown", which is what a {@code HAVOC} says.
 */
class SidecarTacMapperWireV2Test {

  /** Map one wire function; the problems it reports are collected aside. */
  private static TacFunction map(String wire) {
    List<TacProgram.FunctionProblem> problems = new ArrayList<>();
    return SidecarTacMapper.toFunction(
        Json.parseObject(wire), Path.of("Wire.java"), null, SourceLanguage.TYPESCRIPT, problems);
  }

  @Test
  @DisplayName("the member kind says what kind of member the function is")
  void theMemberKindSaysWhatKindOfMemberTheFunctionIs() {
    TacFunction fn =
        map("{\"name\":\"get\",\"memberKind\":\"GETTER\",\"returnType\":{\"kind\":\"STRING\"}}");

    assertThat(fn.memberKind()).isEqualTo(TacMemberKind.GETTER);
  }

  @Test
  @DisplayName("a member kind this build does not know is not mistaken for a method")
  void aMemberKindThisBuildDoesNotKnowIsNotMistakenForAMethod() {
    TacFunction fn = map("{\"name\":\"x\",\"memberKind\":\"SOMETHING_NEW\"}");

    assertThat(fn.memberKind())
        .as("an unknown kind must not be read as an ordinary method")
        .isEqualTo(TacMemberKind.UNKNOWN);
  }

  @Test
  @DisplayName("the exception regions reach the IR")
  void theExceptionRegionsReachTheIr() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"tryRegions\":[{\"tryStart\":3,\"tryEnd\":7,\"catchStart\":9,"
                + "\"catchEnd\":11,\"catchLabels\":[\"L_CATCH\"],\"finallyLabel\":\"L_FINALLY\","
                + "\"finallyStart\":13,\"finallyEnd\":15}]}");

    assertThat(fn.tryRegions()).hasSize(1);
    TacFunction.TryRegion region = fn.tryRegions().get(0);
    assertThat(region.tryStart).isEqualTo(3);
    assertThat(region.tryEnd).isEqualTo(7);
    assertThat(region.catchLabels).containsExactly("L_CATCH");
    assertThat(region.finallyLabel).isEqualTo("L_FINALLY");
    assertThat(region.hasFinally()).isTrue();
  }

  @Test
  @DisplayName("the five annotation channels reach the IR")
  void theFiveAnnotationChannelsReachTheIr() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"classAnnotations\":[\"RestController\"],"
                + "\"methodAnnotations\":[\"GetMapping\"],"
                + "\"fieldAnnotations\":{\"secret\":[\"Value\"]},"
                + "\"paramAnnotations\":{\"id\":[\"PathVariable\"]},"
                + "\"annotationValues\":{\"Value\":\"${app.token}\"}}");

    assertThat(fn.classAnnotations()).containsExactly("RestController");
    assertThat(fn.methodAnnotations()).containsExactly("GetMapping");
    assertThat(fn.fieldAnnotations()).containsKey("secret");
    assertThat(fn.paramAnnotations()).containsKey("id");
    assertThat(fn.annotationValues()).containsKey("Value");
  }

  @Test
  @DisplayName("a drop lowers the precision and is kept as a receipt")
  void aDropLowersThePrecisionAndIsKeptAsAReceipt() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"precision\":\"SOURCE_FULL\",\"drops\":"
                + "[{\"construct\":\"ts.decorator\",\"detail\":\"not read\",\"impact\":\"SEMANTICS\","
                + "\"line\":12,\"column\":3,\"scope\":\"FUNCTION\"}]}");

    assertThat(fn.drops()).hasSize(1);
    assertThat(fn.drops().get(0).construct()).isEqualTo("ts.decorator");
    assertThat(fn.drops().get(0).impact()).isEqualTo(TacDrop.Impact.SEMANTICS);
    assertThat(fn.irPrecision())
        .as("a sidecar that claims full precision while recording a loss is not believed")
        .isEqualTo(IrPrecision.SOURCE_DEGRADED);
  }

  @Test
  @DisplayName("a type carries its nullability, its union members and its value domain")
  void aTypeCarriesItsNullabilityItsUnionMembersAndItsValueDomain() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"returnType\":{\"kind\":\"STRING\",\"nullable\":true,"
                + "\"unionMembers\":[{\"kind\":\"STRING\"},{\"kind\":\"NULL\"}],"
                + "\"domain\":{\"domain\":\"FLOATING\",\"bits\":53,\"exponentBits\":11}}}");

    TacType type = fn.returnType();
    assertThat(type.isNullable()).isTrue();
    assertThat(type.unionMembers()).hasSize(2);
    assertThat(type.domain()).isNotNull();
    assertThat(type.domain().kind()).isEqualTo(TacValueDomain.Kind.FLOATING);
  }

  @Test
  @DisplayName("a loose comparison stays a loose comparison")
  void aLooseComparisonStaysALooseComparison() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":[{\"kind\":\"BINOP\",\"binop\":\"LOOSE_EQ\","
                + "\"dest\":{\"kind\":\"VAR\",\"name\":\"t1\"}}]}");

    assertThat(fn.instructions()).hasSize(1);
    assertThat(fn.instructions().get(0).binop).isEqualTo(BinaryOperator.LOOSE_EQ);
  }

  @Test
  @DisplayName("an operator this build does not know makes the value unknown, not an addition")
  void anOperatorThisBuildDoesNotKnowMakesTheValueUnknownNotAnAddition() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":[{\"kind\":\"BINOP\",\"binop\":\"SPACESHIP\","
                + "\"dest\":{\"kind\":\"VAR\",\"name\":\"t1\"}}]}");

    assertThat(fn.instructions()).hasSize(1);
    assertThat(fn.instructions().get(0).kind)
        .as("reading an unknown operator as ADD turns a comparison into an addition")
        .isEqualTo(TacInstruction.Kind.HAVOC);
  }

  @Test
  @DisplayName("the field's owner, the guard and the label reference reach the IR")
  void theFieldsOwnerTheGuardAndTheLabelReferenceReachTheIr() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":["
                + "{\"kind\":\"FIELDLOAD\",\"fieldName\":\"secret\",\"ownerType\":\"com.acme.User\","
                + "\"dest\":{\"kind\":\"VAR\",\"name\":\"t1\"}},"
                + "{\"kind\":\"BRANCH\",\"guard\":{\"kind\":\"COMPARE\",\"op\":\"EQ\","
                + "\"subject\":{\"kind\":\"VAR\",\"name\":\"t1\"},\"rhs\":{\"kind\":\"CONST\","
                + "\"constKind\":\"INT\",\"value\":\"3\"}},\"trueLabel\":\"L_A\",\"falseLabel\":\"L_B\"},"
                + "{\"kind\":\"JUMP\",\"labelRef\":{\"name\":\"L_LOOP\",\"kind\":\"LOOP_HEAD\","
                + "\"scopeId\":\"f1\"},\"label\":\"L_LOOP\"}]}");

    assertThat(fn.instructions()).hasSize(3);
    assertThat(fn.instructions().get(0).ownerType).isEqualTo("com.acme.User");
    assertThat(fn.instructions().get(1).guard).isNotNull();
    assertThat(fn.instructions().get(1).guard.kind())
        .isEqualTo(org.plumb.tac.ir.TacGuard.Kind.COMPARE);
    assertThat(fn.instructions().get(1).guard.op()).isEqualTo(BinaryOperator.EQ);
    TacLabelRef ref = fn.instructions().get(2).labelRef;
    assertThat(ref).isNotNull();
    assertThat(ref.kind()).isEqualTo(TacLabelRef.Kind.LOOP_HEAD);
    assertThat(ref.name()).isEqualTo("L_LOOP");
  }

  @Test
  @DisplayName("a regular expression literal keeps its text")
  void aRegularExpressionLiteralKeepsItsText() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":[{\"kind\":\"MOVE\",\"dest\":{\"kind\":\"VAR\","
                + "\"name\":\"t1\"},\"lhs1\":{\"kind\":\"CONST\",\"constKind\":\"REGEX\","
                + "\"value\":\"[0-9]+\"}}]}");

    assertThat(fn.instructions()).hasSize(1);
    assertThat(fn.instructions().get(0).lhs1)
        .as("the pattern must not be dropped")
        .isEqualTo(org.plumb.tac.ir.TacOperand.Const.ofString("[0-9]+"));
  }

  @Test
  @DisplayName("an argument that cannot be read does not move the arguments after it")
  void anArgumentThatCannotBeReadDoesNotMoveTheArgumentsAfterIt() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":[{\"kind\":\"CALL\",\"calleeSig\":\"a.b/2\","
                + "\"args\":[{\"kind\":\"VAR\",\"name\":\"recv\"},{\"kind\":\"VAR\"},"
                + "{\"kind\":\"VAR\",\"name\":\"payload\"}]}]}");

    assertThat(fn.instructions()).hasSize(1);
    List<org.plumb.tac.ir.TacOperand> args = fn.instructions().get(0).args;
    assertThat(args)
        .as("an unreadable argument must not shorten the list and shift the rest")
        .hasSize(3);
    assertThat(args.get(0)).isEqualTo(new org.plumb.tac.ir.TacOperand.TacVar("recv", null, false));
    assertThat(args.get(2))
        .as("the last argument must still be the last one")
        .isEqualTo(new org.plumb.tac.ir.TacOperand.TacVar("payload", null, false));
  }

  @Test
  @DisplayName("a guard written under the sidecar's own key names its subject")
  void aGuardWrittenUnderTheSidecarsOwnKeyNamesItsSubject() {
    TacFunction fn =
        map(
            "{\"name\":\"x\",\"instructions\":[{\"kind\":\"BRANCH\",\"guard\":{\"kind\":\"TRUTHINESS\","
                + "\"operand\":{\"kind\":\"VAR\",\"name\":\"t1\"}},\"trueLabel\":\"L_A\","
                + "\"falseLabel\":\"L_B\"}]}");

    assertThat(fn.instructions()).hasSize(1);
    org.plumb.tac.ir.TacGuard guard = fn.instructions().get(0).guard;
    assertThat(guard).as("the guard must reach the IR").isNotNull();
    assertThat(guard.subject())
        .as("a guard whose subject was dropped says nothing about any value")
        .isEqualTo(new org.plumb.tac.ir.TacOperand.TacVar("t1", null, false));
  }
}
