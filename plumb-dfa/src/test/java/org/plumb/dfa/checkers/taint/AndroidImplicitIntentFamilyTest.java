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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The implicit intent family names two kinds of entry point, and each kind answers a different
 * question.
 *
 * <h2>Why this class needed to exist</h2>
 *
 * <p>The family carries a payload's own entry points ({@code putExtra} and its relatives) AND the
 * launch sites ({@code startActivity} and its relatives). The two look like a copy of each other,
 * and a reader who cannot tell them apart concludes that one of them is dead -- that the launch
 * sites can never fire, because the payload is the thing that carries the taint and the intent is
 * only the envelope.
 *
 * <p>That conclusion is wrong, and it is wrong in a way that loses a finding. The two kinds answer
 * different questions:
 *
 * <ul>
 *   <li>a PAYLOAD entry point fires when a tainted value is put INTO an intent;
 *   <li>a LAUNCH site fires when the INTENT ITSELF carries the taint -- when the intent came out of
 *       a call whose result may carry the caller's argument.
 * </ul>
 *
 * <p>The engine's summary path is what connects them. A method that builds an intent returns a
 * value the summary cannot prove is constant, so the result inherits the caller's taint: the intent
 * that reaches {@code startActivity} carries it, and the launch site names the argument that holds
 * it. The launch sites are the only report point in that shape.
 *
 * <p><b>What each test proves.</b> A tainted value at the position the catalogue names is a finding
 * with the rule's own CWE, and the same call with a value the program wrote itself is not a
 * finding. The second half is what makes the first half mean something: a rule that fired on every
 * call of that shape would pass the first assertion alone.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and the engine reads the argument at the position the catalogue names. The TAC below is the one
 * the front end produces for the corresponding source, including the convention that a CALL carries
 * its receiver in {@code args[0]} -- so the catalogue's argument index N is the TAC's argument N+1.
 */
@DisplayName("Taint: the implicit intent family reports the payload and the launch")
class AndroidImplicitIntentFamilyTest {

  /** The payload entry point the catalogue has always named. */
  private static final String PUT_EXTRA = "android.content.Intent.putExtra/2";

  /** The payload entry point that carries a string. */
  private static final String PUT_STRING_EXTRA = "android.content.Intent.putStringExtra/2";

  /** The plain launch: the intent is the only argument. */
  private static final String START_ACTIVITY = "android.app.Activity.startActivity/1";

  /** The launch that asks for a result: the intent, then a request code. */
  private static final String START_ACTIVITY_FOR_RESULT =
      "android.app.Activity.startActivityForResult/2";

  /** The call that builds the intent, and the one the launch site receives. */
  private static final String BUILD_INTENT = "com.example.Builder.buildIntent/1";

  // ------------------------------------------------------------------ the payload

  @Test
  @DisplayName("a payload the request chose is an implicit intent finding")
  void aPayloadTheRequestChoseIsAnImplicitIntentFinding() {
    List<Finding> tainted = scan(payload(PUT_EXTRA, true));
    List<Finding> written = scan(payload(PUT_EXTRA, false));

    assertThat(tainted)
        .as("the payload reaches the intent the app hands to the system")
        .isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-927").isEqualTo("CWE-927");
    assertThat(written).as("a payload the program wrote itself is not the attacker's").isEmpty();
  }

  @Test
  @DisplayName("a string payload the request chose is an implicit intent finding")
  void aStringPayloadTheRequestChoseIsAnImplicitIntentFinding() {
    List<Finding> tainted = scan(payload(PUT_STRING_EXTRA, true));
    List<Finding> written = scan(payload(PUT_STRING_EXTRA, false));

    assertThat(tainted).as("the string payload reaches the intent as well").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-927").isEqualTo("CWE-927");
    assertThat(written).as("a payload the program wrote itself is not the attacker's").isEmpty();
  }

  // ------------------------------------------------------------------ the launch site

  @Test
  @DisplayName("an intent that carries the taint is a finding when it is launched")
  void anIntentThatCarriesTheTaintIsAFindingWhenItIsLaunched() {
    List<Finding> tainted = scan(launch(START_ACTIVITY, true));
    List<Finding> written = scan(launch(START_ACTIVITY, false));

    assertThat(mentions(tainted, START_ACTIVITY))
        .as("the launch site names the argument that holds the taint")
        .isTrue();
    assertThat(mentions(written, START_ACTIVITY))
        .as("an intent the program wrote itself is not the attacker's")
        .isFalse();
  }

  @Test
  @DisplayName("the launch that asks for a result is a finding too")
  void theLaunchThatAsksForAResultIsAFindingToo() {
    List<Finding> tainted = scan(launch(START_ACTIVITY_FOR_RESULT, true));
    List<Finding> written = scan(launch(START_ACTIVITY_FOR_RESULT, false));

    assertThat(mentions(tainted, START_ACTIVITY_FOR_RESULT))
        .as("the request code that follows the intent is a number, not a payload")
        .isTrue();
    assertThat(mentions(written, START_ACTIVITY_FOR_RESULT))
        .as("an intent the program wrote itself is not the attacker's")
        .isFalse();
  }

  @Test
  @DisplayName("an intent built by a call carries the caller's taint to the launch site")
  void anIntentBuiltByACallCarriesTheCallersTaintToTheLaunchSite() {
    List<Finding> tainted = scan(intentBuilder(false), launchingCaller());
    List<Finding> written = scan(intentBuilder(true), launchingCaller());

    assertThat(mentions(tainted, START_ACTIVITY_FOR_RESULT))
        .as("the intent came out of a call whose result may carry the caller's argument")
        .isTrue();
    assertThat(mentions(written, START_ACTIVITY_FOR_RESULT))
        .as("an intent the builder filled with a constant carries nothing of the caller's")
        .isFalse();
  }

  // ------------------------------------------------------------------ the TAC

  /**
   * A payload the request chose is put into an intent.
   *
   * <pre>
   * intent.putExtra("token", request.getParameter("token"));
   * </pre>
   *
   * <p>The payload is the second user argument -- the TAC's argument 2. The intent is the receiver,
   * and the receiver of a payload call carries no payload of its own.
   */
  private static TacFunction payload(String callee, boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar value = new TacOperand.TacVar("value", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(value, req, 10) : constant(value, "s3cret", 10));
    instructions.add(
        call(
            callee,
            temp("put"),
            List.of(temp("intent"), TacOperand.Const.ofString("token"), value),
            12));
    return function("app.handler(string):string", List.of(req), List.of(value), instructions, null);
  }

  /**
   * An intent that carries the taint is handed to the system.
   *
   * <pre>
   * startActivity(intent);
   * </pre>
   *
   * <p>The intent is the first user argument -- the TAC's argument 1. The MOVE into the intent
   * variable is the statement "the intent carries what the request chose": the intent is the
   * envelope, and the envelope is what the launch site reads.
   */
  private static TacFunction launch(String callee, boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar intent = new TacOperand.TacVar("intent", TacType.UNKNOWN, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(intent, req, 10) : constant(intent, "plain", 10));
    List<TacOperand> args = new ArrayList<>();
    args.add(temp("activity"));
    args.add(intent);
    if (callee.endsWith("/2")) {
      // The request code that follows the intent: a number, not a payload.
      args.add(TacOperand.Const.ofInt(1));
    }
    instructions.add(call(callee, temp("started"), args, 12));
    return function(
        "app.handler(string):string", List.of(req), List.of(intent), instructions, null);
  }

  /**
   * The method that builds the intent.
   *
   * <p>Two shapes, and the difference between them is the point of the test:
   *
   * <ul>
   *   <li>the intent it returns is built in the method and carries the caller's value, so the
   *       summary cannot prove that the result is free of the caller's argument -- the answer is
   *       "may depend on it";
   *   <li>the intent it returns is a constant the method wrote, so the result provably carries
   *       nothing the caller passed.
   * </ul>
   */
  private static TacFunction intentBuilder(boolean constant) {
    TacOperand.TacVar payload = new TacOperand.TacVar("payload", TacType.STRING, false);
    TacOperand.TacVar built = new TacOperand.TacVar("built", TacType.UNKNOWN, false);
    List<TacInstruction> instructions = new ArrayList<>();
    if (constant) {
      instructions.add(constant(built, "plain", 8));
    } else {
      instructions.add(
          TacInstruction.builder(TacInstruction.Kind.NEW)
              .dest(built)
              .ctorType(TacType.ofClass("android.content.Intent"))
              .args(List.of())
              .line(8)
              .column(1)
              .build());
      instructions.add(
          call(
              PUT_EXTRA,
              temp("put"),
              List.of(built, TacOperand.Const.ofString("token"), payload),
              9));
    }
    instructions.add(
        TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(built).line(10).column(1).build());
    return function(
        "buildIntent(java.lang.String):android.content.Intent",
        List.of(payload),
        List.of(built),
        instructions,
        "com.example.Builder");
  }

  /**
   * The caller: it asks for an intent and launches it.
   *
   * <pre>
   * Intent intent = buildIntent(token);
   * startActivityForResult(intent, 1);
   * </pre>
   */
  private static TacFunction launchingCaller() {
    TacOperand.TacVar req = new TacOperand.TacVar("token", TacType.STRING, false);
    TacOperand.TacVar intent = new TacOperand.TacVar("intent", TacType.UNKNOWN, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(call(BUILD_INTENT, intent, List.of(temp("builder"), req), 12));
    instructions.add(
        call(
            START_ACTIVITY_FOR_RESULT,
            temp("started"),
            List.of(temp("activity"), intent, TacOperand.Const.ofInt(1)),
            14));
    return function(
        "launch(java.lang.String):void",
        List.of(req),
        List.of(intent),
        instructions,
        "com.example.Helper");
  }

  // ------------------------------------------------------------------ the run

  private List<Finding> scan(TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("App.java"), "app");
    for (TacFunction fn : functions) {
      program.addFunction(fn);
    }
    FindingReporter reporter =
        new DfaEngine(
                ConfigLoader.loadFromClasspath(),
                List.of(new ImplicitIntentChecker()),
                "global",
                null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().equals("dfa-taint-implicit-intent")) out.add(f);
    }
    return out;
  }

  /** Whether any of the findings names the callee. */
  private static boolean mentions(List<Finding> findings, String callee) {
    for (Finding f : findings) {
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  /** The parameter every case starts from: the value the request supplied. */
  private static TacOperand.TacVar request() {
    return new TacOperand.TacVar("req", TacType.UNKNOWN, false);
  }

  private static TacInstruction call(
      String callee, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(dest)
        .args(args)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction constant(TacOperand dest, String value, int line) {
    return move(dest, TacOperand.Const.ofString(value), line);
  }

  private static TacFunction function(
      String signature,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      List<TacInstruction> instructions,
      String enclosingClass) {
    return new TacFunction(
        "handler",
        signature,
        TacType.VOID,
        params,
        locals,
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        enclosingClass,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.JAVA,
        IrPrecision.SOURCE_FULL);
  }
}
