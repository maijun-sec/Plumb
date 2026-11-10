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
 * The server-side request forgery defect class: the catalogue that names the sinks, and the checker
 * that watches them.
 *
 * <p><b>Why this class needed a checker at all.</b> A sink family is a pattern set; a checker is
 * what looks at it. The SSRF family carried a hundred patterns -- one per API that makes a server
 * reach out -- and no checker named it, so a tainted URL reached any of them and nothing was
 * reported. The patterns matched; the match led nowhere.
 *
 * <p><b>Why the family was curated at the same time.</b> The family also carried patterns that name
 * APIs of OTHER defect classes: a write to a stream is a log write, a formatted write is a format
 * string, a Kotlin file read names a path, a URL turned into a process is a command, an XML
 * document handed to a parser is an XXE, and the Netscape LDAP calls talk to a directory. Each of
 * them was reported with the SSRF number, which is the wrong number. They live in the family that
 * describes them now.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and a signature the front end never emits is a pattern that never fires. The instruction below is
 * the TAC the JVM front end produced for the corresponding Java source: a method call keeps the
 * receiver in args[0], and the URL of an SSRF sink is the RECEIVER of the call -- which is why the
 * catalogue's argument index for it is the receiver and not a position.
 */
@DisplayName("Taint: server-side request forgery")
class SsrfCheckerTest {

  private static final String SSRF_RULE = "dfa-taint-ssrf";

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

  /**
   * The handler that opens a URL, as the front end lowers it.
   *
   * @param language the language the function is written in
   * @param tainted whether the URL comes from the request or is a constant
   * @return the function under analysis
   */
  private static TacFunction opener(SourceLanguage language, boolean tainted) {
    TacOperand.TacVar req = new TacOperand.TacVar("req", TacType.UNKNOWN, false);
    TacOperand.TacVar url = new TacOperand.TacVar("url", TacType.STRING, false);

    List<TacInstruction> instructions = new ArrayList<>();
    if (tainted) {
      // String url = req.query.url;
      instructions.add(move(url, req, 10));
    } else {
      // String url = "https://example.invalid/report";
      instructions.add(
          TacInstruction.builder(TacInstruction.Kind.MOVE)
              .dest(url)
              .lhs1(TacOperand.Const.ofString("https://example.invalid/report"))
              .line(10)
              .column(1)
              .build());
    }
    // url.openConnection();
    instructions.add(call("java.net.URL.openConnection/1", temp("t9"), List.of(url), 12));
    return new TacFunction(
        "handler",
        "app.handler(string):string",
        TacType.STRING,
        List.of(req),
        List.of(url),
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

  private List<Finding> scan(SourceLanguage language, TacFunction function) {
    TacProgram program = new TacProgram(Path.of("App.java"), "app");
    program.addFunction(function);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SsrfChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().equals(SSRF_RULE)) out.add(f);
    }
    return out;
  }

  // ------------------------------------------------------------------ catalogue

  @Test
  @DisplayName("the catalogue names the sinks of every language the front ends emit")
  void theCatalogueNamesTheSinksOfEveryLanguage() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("java.net.URL.openConnection/1", SourceLanguage.JAVA))
        .as("the JVM opens a URL on the receiver")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("net/http.Get/1", SourceLanguage.GO))
        .as("the Go client takes the URL as its argument")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("urllib.request.urlopen/1", SourceLanguage.PYTHON3))
        .as("the Python client takes the URL as its argument")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("<unknown>.fetch/2", SourceLanguage.TYPESCRIPT))
        .as("the global fetch sends its argument to a URL")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("\\curl_init/1", SourceLanguage.PHP))
        .as("the PHP client takes the URL as its argument")
        .isTrue();
  }

  @Test
  @DisplayName("the SSRF family no longer claims the APIs of other defect classes")
  void theSsrfFamilyNoLongerClaimsOtherDefectClasses() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("java.io.Writer.println/1", SourceLanguage.JAVA))
        .as("a write to a stream is a log write, not a request")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("netscape.ldap.LDAPConnection.connect/2", SourceLanguage.JAVA))
        .as("a directory server is CWE-90, not CWE-918")
        .isFalse();
    assertThat(
            config
                .sinkFamilies()
                .byName("ssrf.sinks")
                .orElseThrow()
                .matches("org.apache.commons.digester.Digester.parse/1", SourceLanguage.JAVA))
        .as("an XML document is an XXE, not a request")
        .isFalse();
  }

  @Test
  @DisplayName("the patterns that left the SSRF family live in the family that describes them")
  void thePatternsThatLeftLiveInTheirOwnFamily() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("java.io.Writer.println/1", SourceLanguage.JAVA))
        .as("a write to a stream is a log write")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("ldap.sinks")
                .orElseThrow()
                .matches("netscape.ldap.LDAPConnection.connect/2", SourceLanguage.JAVA))
        .as("a directory connection is an LDAP sink")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("xxe.sinks")
                .orElseThrow()
                .matches("org.apache.commons.digester.Digester.parse/1", SourceLanguage.JAVA))
        .as("an XML document is an XXE")
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-path.sinks")
                .orElseThrow()
                .matches("kotlin.io.readText/1", SourceLanguage.KOTLIN))
        .as("a Kotlin file read names a path")
        .isTrue();
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted URL that the server opens is a finding")
  void aTaintedUrlThatTheServerOpensIsAFinding() {
    List<Finding> findings = scan(SourceLanguage.JAVA, opener(SourceLanguage.JAVA, true));

    assertThat(findings)
        .as("the rule fires when the tainted value is the destination of the request")
        .isNotEmpty();
    assertThat(findings.get(0).cwe())
        .as("the finding carries the SSRF number")
        .isEqualTo("CWE-918");
  }

  @Test
  @DisplayName("a URL the program wrote itself is not a finding")
  void aUrlTheProgramWroteItselfIsNotAFinding() {
    List<Finding> findings = scan(SourceLanguage.JAVA, opener(SourceLanguage.JAVA, false));

    assertThat(findings).as("a constant destination is not attacker-chosen").isEmpty();
  }
}
