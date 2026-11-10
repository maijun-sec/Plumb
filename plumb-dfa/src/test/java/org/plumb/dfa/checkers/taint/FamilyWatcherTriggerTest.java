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
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
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
 * The five families that had a checker but no way to fire: this test says the checker fires.
 *
 * <p><b>Why this class needed to exist.</b> A checker that was only REGISTERED proves nothing. It
 * can be assembled into the scan, it can sit in the configuration, and it can still never report
 * anything, because the family it watches names APIs whose argument positions do not exist, or
 * names the wrong argument, or belongs to another defect class. The registration says the rule is
 * installed; only a finding says the rule works.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and the engine reads the argument at the position the catalogue names. The TAC below is the one
 * the front end produces for the corresponding source, including the convention that a CALL carries
 * its receiver in {@code args[0]} -- so the catalogue's argument index N is the TAC's argument N+1.
 *
 * <p><b>What each case proves.</b> A tainted value at the named position is a finding with the
 * rule's own CWE, and the same call with a value the program wrote itself is not a finding. The
 * second half is what makes the first half mean something: a checker that fired on every call of
 * that shape would pass the first assertion alone.
 */
@DisplayName("Taint: the families that got a checker fire")
class FamilyWatcherTriggerTest {

  // ------------------------------------------------------------------ the catalogue

  @Test
  @DisplayName("every family that got a checker names an API with an argument to carry the taint")
  void everyFamilyNamesAnApiWithAnArgument() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("weak-crypto.sinks")
                .orElseThrow()
                .matches("java.security.MessageDigest.getInstance/1", SourceLanguage.JAVA))
        .as("the algorithm is the first argument of the factory")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("xxe.sinks")
                .orElseThrow()
                .matches("javax.xml.parsers.DocumentBuilder.parse/1", SourceLanguage.JAVA))
        .as("the document is the first argument of the parser")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("cors.sinks")
                .orElseThrow()
                .matches("System.Net.Http.Headers.HttpHeaders.Add/2", SourceLanguage.CSHARP))
        .as("the origin is the second argument of the header")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("unencrypted-credential.sinks")
                .orElseThrow()
                .matches(
                    "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.addAccessToken/2",
                    SourceLanguage.JAVA))
        .as("the token is the second argument of the registration")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("android-implicit-intent.sinks")
                .orElseThrow()
                .matches("android.content.Intent.putExtra/2", SourceLanguage.JAVA))
        .as("the payload is the second argument of the intent")
        .isTrue();
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted algorithm name is a weak cryptography finding")
  void aTaintedAlgorithmNameIsAWeakCryptographyFinding() {
    List<Finding> tainted = scan(SourceLanguage.JAVA, weakCrypto(true), new WeakCryptoChecker());
    List<Finding> written = scan(SourceLanguage.JAVA, weakCrypto(false), new WeakCryptoChecker());

    assertThat(tainted).as("the attacker chooses the digest").isNotEmpty();
    assertThat(tainted.get(0).cwe())
        .as("the finding carries the CWE the family declares")
        .contains("CWE-261");
    assertThat(written).as("a constant algorithm is not attacker-chosen").isEmpty();
  }

  @Test
  @DisplayName("an untrusted document that reaches a parser is an XXE finding")
  void anUntrustedDocumentThatReachesAParserIsAnXxeFinding() {
    List<Finding> tainted = scan(SourceLanguage.JAVA, xxe(true), new TaintedXmlParserChecker());
    List<Finding> written = scan(SourceLanguage.JAVA, xxe(false), new TaintedXmlParserChecker());

    assertThat(tainted).as("the parser resolves the entities the document names").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-611").isEqualTo("CWE-611");
    assertThat(written).as("a document the program wrote itself is not untrusted").isEmpty();
  }

  @Test
  @DisplayName("an origin the request chose is a CORS finding")
  void anOriginTheRequestChoseIsACorsFinding() {
    List<Finding> tainted = scan(SourceLanguage.CSHARP, cors(true), new CorsChecker());
    List<Finding> written = scan(SourceLanguage.CSHARP, cors(false), new CorsChecker());

    assertThat(tainted).as("the origin header names whatever the request asked for").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-942").isEqualTo("CWE-942");
    assertThat(written).as("a fixed origin is not attacker-chosen").isEmpty();
  }

  @Test
  @DisplayName("a token the request chose is a cleartext credential finding")
  void aTokenTheRequestChoseIsACleartextCredentialFinding() {
    List<Finding> tainted =
        scan(SourceLanguage.JAVA, credential(true), new CleartextCredentialChecker());
    List<Finding> written =
        scan(SourceLanguage.JAVA, credential(false), new CleartextCredentialChecker());

    assertThat(tainted).as("the credential comes from the request").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-311").isEqualTo("CWE-311");
    assertThat(written).as("a credential the program wrote itself is not the attacker's").isEmpty();
  }

  @Test
  @DisplayName("a payload the request chose, carried by an intent, is an implicit intent finding")
  void aPayloadCarriedByAnIntentIsAnImplicitIntentFinding() {
    List<Finding> tainted =
        scan(SourceLanguage.JAVA, implicitIntent(true), new ImplicitIntentChecker());
    List<Finding> written =
        scan(SourceLanguage.JAVA, implicitIntent(false), new ImplicitIntentChecker());

    assertThat(tainted).as("the intent hands the payload to whoever answers it").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-927").isEqualTo("CWE-927");
    assertThat(written).as("a payload the program wrote itself is not sensitive").isEmpty();
  }

  // ------------------------------------------------------------------ the TAC

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
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
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(TacOperand.Const.ofString(value))
        .line(line)
        .column(1)
        .build();
  }

  /** The parameter every case starts from: the value the request supplied. */
  private static TacOperand.TacVar request() {
    return new TacOperand.TacVar("req", TacType.UNKNOWN, false);
  }

  private static TacFunction function(
      SourceLanguage language, TacOperand.TacVar request, List<TacInstruction> instructions) {
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(request),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        language,
        IrPrecision.SOURCE_FULL);
  }

  private static String fileName(SourceLanguage language) {
    return language == SourceLanguage.CSHARP ? "App.cs" : "App.java";
  }

  /**
   * The algorithm name reaches the digest factory.
   *
   * <pre>
   * String algorithm = request.getParameter("algorithm");
   * MessageDigest.getInstance(algorithm);
   * </pre>
   *
   * <p>The factory is static, so the receiver slot holds the class name and the algorithm is the
   * first user argument -- the TAC's argument 1.
   */
  private static TacFunction weakCrypto(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar algorithm = new TacOperand.TacVar("algorithm", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(algorithm, req, 10) : constant(algorithm, "SHA-256", 10));
    instructions.add(
        call(
            "java.security.MessageDigest.getInstance/1",
            temp("digest"),
            List.of(TacOperand.Const.ofString("java.security.MessageDigest"), algorithm),
            12));
    return function(SourceLanguage.JAVA, req, instructions);
  }

  /**
   * An untrusted document reaches the parser.
   *
   * <pre>
   * InputStream body = request.getInputStream();
   * builder.parse(body);
   * </pre>
   *
   * <p>The stream is derived from the request, so it carries the request's taint; the document is
   * the first user argument of the parser -- the TAC's argument 1.
   */
  private static TacFunction xxe(boolean tainted) {
    TacOperand.TacVar req = request();
    List<TacInstruction> instructions = new ArrayList<>();
    if (tainted) {
      instructions.add(
          call(
              "javax.servlet.http.HttpServletRequest.getInputStream/0",
              temp("body"),
              List.of(req),
              10));
    } else {
      instructions.add(constant(temp("body"), "<orders/>", 10));
    }
    instructions.add(
        call(
            "javax.xml.parsers.DocumentBuilder.parse/1",
            temp("document"),
            List.of(temp("builder"), temp("body")),
            12));
    return function(SourceLanguage.JAVA, req, instructions);
  }

  /**
   * The origin the request chose reaches the CORS header.
   *
   * <pre>
   * headers.Add("Access-Control-Allow-Origin", request.getParameter("origin"));
   * </pre>
   *
   * <p>The header value is the second user argument -- the TAC's argument 2.
   */
  private static TacFunction cors(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar origin = new TacOperand.TacVar("origin", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(
        tainted ? move(origin, req, 10) : constant(origin, "https://example.invalid", 10));
    instructions.add(
        call(
            "System.Net.Http.Headers.HttpHeaders.Add/2",
            temp("added"),
            List.of(
                temp("headers"), TacOperand.Const.ofString("Access-Control-Allow-Origin"), origin),
            12));
    return function(SourceLanguage.CSHARP, req, instructions);
  }

  /**
   * The token the request chose reaches the credential registration.
   *
   * <pre>
   * provider.addAccessToken("consumer", request.getParameter("token"));
   * </pre>
   *
   * <p>The token is the second user argument -- the TAC's argument 2.
   */
  private static TacFunction credential(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar token = new TacOperand.TacVar("token", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(token, req, 10) : constant(token, "s3cret", 10));
    instructions.add(
        call(
            "org.glassfish.jersey.server.oauth1.DefaultOAuth1Provider.addAccessToken/2",
            temp("registered"),
            List.of(temp("provider"), TacOperand.Const.ofString("consumer"), token),
            12));
    return function(SourceLanguage.JAVA, req, instructions);
  }

  /**
   * The payload the request chose is carried by an intent.
   *
   * <pre>
   * intent.putExtra("token", request.getParameter("token"));
   * </pre>
   *
   * <p>The payload is the second user argument -- the TAC's argument 2. The intent itself is the
   * receiver, and the receiver of a launch call carries no payload of its own, so the intent's own
   * entry points are where the taint can be.
   */
  private static TacFunction implicitIntent(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar value = new TacOperand.TacVar("value", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(value, req, 10) : constant(value, "s3cret", 10));
    instructions.add(
        call(
            "android.content.Intent.putExtra/2",
            temp("put"),
            List.of(temp("intent"), TacOperand.Const.ofString("token"), value),
            12));
    return function(SourceLanguage.JAVA, req, instructions);
  }

  // ------------------------------------------------------------------ the run

  private List<Finding> scan(SourceLanguage language, TacFunction function, DfaChecker checker) {
    TacProgram program = new TacProgram(Path.of(fileName(language)), "app");
    program.addFunction(function);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().equals(ruleIdOf(checker))) out.add(f);
    }
    return out;
  }

  private static String ruleIdOf(DfaChecker checker) {
    if (checker instanceof WeakCryptoChecker) return "dfa-taint-weak-crypto";
    if (checker instanceof TaintedXmlParserChecker) return "dfa-taint-xxe";
    if (checker instanceof CorsChecker) return "dfa-taint-cors";
    if (checker instanceof CleartextCredentialChecker) return "dfa-taint-cleartext-credential";
    if (checker instanceof ImplicitIntentChecker) return "dfa-taint-implicit-intent";
    throw new IllegalArgumentException("no rule id for " + checker.getClass().getName());
  }
}
