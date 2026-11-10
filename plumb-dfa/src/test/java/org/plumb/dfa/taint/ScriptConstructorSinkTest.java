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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CodeInjectionChecker;
import org.plumb.dfa.checkers.taint.RegexInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.ts.TsSidecarClient;
import org.plumb.tac.frontend.ts.TsTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The script languages' constructors: what the catalogue names, and what the engine reports.
 *
 * <p><b>Why the constructors matter.</b> A script language's library is reached through
 * constructors far more often than through methods: {@code new Function(body)} evaluates a string
 * as code, and {@code new RegExp(pattern)} compiles a pattern the caller chose. Neither had an
 * entry in the catalogue, so a program that built a function from the request's own text scanned
 * clean.
 *
 * <p><b>Why the keys are measured, not inferred.</b> The catalogue matches a callee SIGNATURE, and
 * a signature the front end never emits is a pattern that never fires. The keys below are the ones
 * the TypeScript, JavaScript and ArkTS side cars produced for the corresponding source, read back
 * out of the TAC: a construction is a {@code NEW} whose callee signature is {@code
 * <Class>.<init>/<arity>}, where the class is the SYNTACTIC name of the constructed expression and
 * the arity is the number of arguments PASSED. All three side cars emit the same keys -- {@code
 * Function.<init>/1}, {@code RegExp.<init>/1}, {@code RegExp.<init>/2}, {@code Function.<init>/2}
 * -- and an anonymous class expression emits no key at all rather than a made-up one.
 *
 * <p><b>Why the SYNTACTIC name and not the type.</b> The front end also reports a construction's
 * type, and the type is the wrong thing to key on: a value can be given another type by its
 * context, so the type can name a class that was never constructed. An anonymous class expression
 * shows it plainly -- for {@code new (class Inner{})()} the side car reports the type {@code Inner}
 * and emits NO callee signature at all, because the syntax has no name to give. A key built from
 * the type would be {@code Inner.<init>/0}: a class the program never names, so no catalogue entry
 * could ever be written for it.
 *
 * <p><b>Why the Function constructor needs its own position.</b> Its arguments are the parameter
 * NAMES first and the BODY last, so the code is the last argument and a parameter name is not code
 * at all. A fixed index would either miss every body but the first or report a tainted parameter
 * name as code injection, so the catalogue names the last argument instead -- and this test pins
 * BOTH halves: a tainted body is a finding, and a tainted parameter name is not.
 *
 * <p><b>Why a construction resolves differently from a call.</b> A call keeps its receiver in the
 * first argument slot and a construction does not, so the same declared position lands on a
 * different slot in the two. The engine resolves the position against the call's own argument list,
 * and the two shapes are asserted separately.
 */
@DisplayName("Taint: the script languages' constructors")
class ScriptConstructorSinkTest {

  private static final String CODE_RULE = "dfa-taint-code-injection";
  private static final String REGEX_RULE = "dfa-taint-regex-injection";

  /**
   * A TypeScript program that builds a function from the request's own text.
   *
   * <pre>
   * export function handler(body: string): void {
   *   const fn = new Function(body);
   * }
   * </pre>
   */
  private static final String TS_FUNCTION_SOURCE =
      """
      export function handler(body: string): void {
        const fn = new Function(body);
      }
      """;

  /**
   * A TypeScript program that compiles a pattern the request chose.
   *
   * <pre>
   * export function handler(pattern: string): void {
   *   const re = new RegExp(pattern, "gi");
   * }
   * </pre>
   */
  private static final String TS_REGEX_SOURCE =
      """
      export function handler(pattern: string): void {
        const re = new RegExp(pattern, "gi");
      }
      """;

  // ------------------------------------------------------------------ the catalogue

  @Test
  @DisplayName("the catalogue names the Function constructor")
  void theCatalogueNamesTheFunctionConstructor() {
    SinkFamily family = family("code-injection.sinks");

    assertThat(family.matches("Function.<init>/1", SourceLanguage.TYPESCRIPT))
        .as("a construction must be nameable by the catalogue that the engine queries")
        .isTrue();
    assertThat(family.matches("Function.<init>/2", SourceLanguage.TYPESCRIPT))
        .as("the arity is part of the key the front end emits")
        .isTrue();
  }

  @Test
  @DisplayName("the Function constructor names the LAST argument, because that is the body")
  void theFunctionConstructorNamesTheLastArgument() {
    SinkFamily family = family("code-injection.sinks");

    assertThat(family.argIndexesFor("Function.<init>/1", SourceLanguage.TYPESCRIPT))
        .as("the body is the only argument of a one-argument construction")
        .containsExactly(SinkFamily.LAST_ARG_INDEX);
    assertThat(family.argIndexesFor("Function.<init>/2", SourceLanguage.TYPESCRIPT))
        .as("the body is the last argument when the parameter names come first")
        .containsExactly(SinkFamily.LAST_ARG_INDEX);
  }

  @Test
  @DisplayName("the catalogue names the RegExp constructor and the pattern's position")
  void theCatalogueNamesTheRegexConstructor() {
    SinkFamily family = family("regex.sinks");

    assertThat(family.matches("RegExp.<init>/1", SourceLanguage.TYPESCRIPT)).isTrue();
    assertThat(family.matches("RegExp.<init>/2", SourceLanguage.TYPESCRIPT)).isTrue();
    assertThat(family.argIndexesFor("RegExp.<init>/2", SourceLanguage.TYPESCRIPT))
        .as("the pattern is the first argument and the flags are the second")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a script language's constructor is not a Java constructor")
  void theConstructorIsScopedToTheScriptLanguages() {
    SinkFamily family = family("code-injection.sinks");

    assertThat(family.matches("Function.<init>/1", SourceLanguage.JAVA))
        .as("the entry names a JavaScript global, and a Java program cannot reach it")
        .isFalse();
    assertThat(family.matches("RegExp.<init>/1", SourceLanguage.JAVA)).isFalse();
  }

  @Test
  @DisplayName("a constructor belongs to the one family that describes its defect class")
  void aConstructorBelongsToOneFamily() {
    assertThat(familiesFor("Function.<init>/1"))
        .as("the construction is evaluated as code, so only the code injection family may claim it")
        .containsExactly("code-injection.sinks");
    assertThat(familiesFor("RegExp.<init>/1"))
        .as("the construction compiles a pattern, so only the regex family may claim it")
        .containsExactly("regex.sinks");
  }

  /**
   * The families that claim a callee, by the engine's own matcher.
   *
   * <p>Not by pattern text: the patterns carry alternation groups and optional package prefixes, so
   * a text comparison confuses two patterns for different methods that share a class prefix.
   */
  private static List<String> familiesFor(String calleeSig) {
    List<String> names = new ArrayList<>();
    for (SinkFamily family :
        ConfigLoader.loadFromClasspath()
            .sinkFamilies()
            .lookupAll(calleeSig, SourceLanguage.TYPESCRIPT)) {
      names.add(family.name());
    }
    return names;
  }

  // ------------------------------------------------------------------ the position

  @Test
  @DisplayName("the last argument of a call is the last user argument, and the receiver is not one")
  void theLastArgumentResolvesAgainstTheCallItsOwnSlots() {
    assertThat(SinkFamily.resolveUserArgIndex(SinkFamily.LAST_ARG_INDEX, 3, true))
        .as("a call keeps the receiver in the first slot, so the last user argument is the second")
        .isEqualTo(1);
    assertThat(SinkFamily.resolveUserArgIndex(SinkFamily.LAST_ARG_INDEX, 3, false))
        .as("a construction's slots ARE the user arguments")
        .isEqualTo(2);
    assertThat(SinkFamily.resolveUserArgIndex(SinkFamily.LAST_ARG_INDEX, 1, true))
        .as("a call with only a receiver has no user argument at all")
        .isEqualTo(-1);
    assertThat(SinkFamily.resolveUserArgIndex(SinkFamily.RECEIVER_ARG_INDEX, 3, true))
        .as("the receiver sentinel is resolved by the caller, not here")
        .isEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
    assertThat(SinkFamily.resolveUserArgIndex(1, 3, true))
        .as("a fixed index is itself")
        .isEqualTo(1);
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a tainted function body is a code injection finding")
  void aTaintedFunctionBodyIsACodeInjectionFinding() {
    List<Finding> tainted = scan(functionConstructor(true), new CodeInjectionChecker());
    List<Finding> written = scan(functionConstructor(false), new CodeInjectionChecker());

    assertThat(tainted).as("the body is evaluated as code").isNotEmpty();
    assertThat(tainted.get(0).cwe())
        .as("the finding carries the CWE the family declares")
        .contains("CWE-94");
    assertThat(written).as("a body the program wrote itself is not the attacker's").isEmpty();
  }

  @Test
  @DisplayName("a tainted parameter NAME is not code, and must not be reported")
  void aTaintedParameterNameIsNotCode() {
    List<Finding> findings =
        scan(functionConstructorWithTaintedParameterName(), new CodeInjectionChecker());

    assertThat(findings)
        .as("the tainted value is the name of a parameter, not the body the constructor evaluates")
        .isEmpty();
  }

  @Test
  @DisplayName("a tainted pattern is a regex finding")
  void aTaintedPatternIsARegexFinding() {
    List<Finding> tainted = scan(regexConstructor(true), new RegexInjectionChecker());
    List<Finding> written = scan(regexConstructor(false), new RegexInjectionChecker());

    assertThat(tainted).as("the caller chose the pattern the engine will run").isNotEmpty();
    assertThat(tainted.get(0).cwe()).as("the finding carries CWE-1333").isEqualTo("CWE-1333");
    assertThat(written).as("a pattern the program wrote itself is the caller's own").isEmpty();
  }

  @Test
  @DisplayName("the flags of a pattern are not the pattern")
  void theFlagsOfAPatternAreNotThePattern() {
    List<Finding> findings = scan(regexConstructorWithTaintedFlags(), new RegexInjectionChecker());

    assertThat(findings)
        .as("the tainted value is the flags, and a flag string is not a pattern")
        .isEmpty();
  }

  // ------------------------------------------------------------------ the chain

  @Test
  @DisplayName("a TypeScript function constructor reaches the code injection rule")
  void aTypeScriptFunctionConstructorReachesTheCodeInjectionRule(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_FUNCTION_SOURCE);

    List<Finding> findings =
        scanThrough(
            new TsTacFrontend(SourceLanguage.TYPESCRIPT),
            TS_FUNCTION_SOURCE,
            file,
            new CodeInjectionChecker());

    assertThat(findings)
        .as(
            "the whole chain has to agree: the front end has to name the construction, the"
                + " catalogue has to claim that name and the rule has to report it -- and a"
                + " break anywhere in that chain reads as a clean program")
        .isNotEmpty();
    assertThat(findings.get(0).cwe()).as("the finding carries CWE-94").contains("CWE-94");
  }

  @Test
  @DisplayName("a TypeScript pattern constructor reaches the regex rule")
  void aTypeScriptPatternConstructorReachesTheRegexRule(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    Path file = directory.resolve("app.ts");
    Files.writeString(file, TS_REGEX_SOURCE);

    List<Finding> findings =
        scanThrough(
            new TsTacFrontend(SourceLanguage.TYPESCRIPT),
            TS_REGEX_SOURCE,
            file,
            new RegexInjectionChecker());

    assertThat(findings)
        .as("the pattern the request chose is compiled by the program")
        .isNotEmpty();
    assertThat(findings.get(0).cwe()).as("the finding carries CWE-1333").isEqualTo("CWE-1333");
  }

  /**
   * Run the source through the real front end, then through the real engine and the real catalogue.
   *
   * <p>This is the half a hand-written TAC cannot prove: that the front end emits the name the
   * catalogue matches on.
   */
  private static List<Finding> scanThrough(
      TacFrontend frontend, String source, Path file, DfaChecker checker) {
    TacFrontend.ParseResult result = frontend.parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().equals(ruleIdOf(checker))) out.add(finding);
    }
    return out;
  }

  /**
   * Whether the TypeScript front end can run here.
   *
   * <p>Node, the sidecar's script and the package the script imports all have to be there. The
   * third one is the one that is easy to miss, and its absence would make the sidecar die on its
   * first request -- which reads like a broken product rather than a missing dependency.
   */
  private static boolean tsAvailable() {
    Path node = TsSidecarClient.locateNode();
    Path script = TsSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("typescript"));
  }

  // ------------------------------------------------------------------ the TAC

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacInstruction construct(
      String callee, String className, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.NEW)
        .calleeSig(callee)
        .dest(dest)
        .ctorType(TacType.ofClass(className))
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

  /**
   * The body of a function reaches the Function constructor.
   *
   * <pre>
   * const fn = new Function(request.body);
   * </pre>
   *
   * <p>The construction is a NEW whose argument list is the constructor's own: the body is the
   * first argument, and there is no receiver slot in front of it.
   */
  private static TacFunction functionConstructor(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar body = new TacOperand.TacVar("body", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(body, req, 10) : constant(body, "return 1", 10));
    instructions.add(construct("Function.<init>/1", "Function", temp("fn"), List.of(body), 11));
    return function(SourceLanguage.TYPESCRIPT, req, instructions);
  }

  /**
   * The request chooses the NAME of a parameter, and the body is the program's own.
   *
   * <pre>
   * const fn = new Function(request.paramName, "return 1");
   * </pre>
   *
   * <p>The tainted value is the first argument and the body is the second, so the position the
   * catalogue names -- the last argument -- holds a constant.
   */
  private static TacFunction functionConstructorWithTaintedParameterName() {
    TacOperand.TacVar req = request();
    TacOperand.TacVar name = new TacOperand.TacVar("name", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(name, req, 10));
    instructions.add(
        construct(
            "Function.<init>/2",
            "Function",
            temp("fn"),
            List.of(name, TacOperand.Const.ofString("return 1")),
            11));
    return function(SourceLanguage.TYPESCRIPT, req, instructions);
  }

  /**
   * The pattern reaches the RegExp constructor.
   *
   * <pre>
   * const re = new RegExp(request.pattern, "gi");
   * </pre>
   *
   * <p>The pattern is the first argument of the constructor and the flags are the second.
   */
  private static TacFunction regexConstructor(boolean tainted) {
    TacOperand.TacVar req = request();
    TacOperand.TacVar pattern = new TacOperand.TacVar("pattern", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(tainted ? move(pattern, req, 10) : constant(pattern, "^a+$", 10));
    instructions.add(
        construct(
            "RegExp.<init>/2",
            "RegExp",
            temp("re"),
            List.of(pattern, TacOperand.Const.ofString("gi")),
            11));
    return function(SourceLanguage.TYPESCRIPT, req, instructions);
  }

  /**
   * The flags are the attacker's, and the pattern is the program's own.
   *
   * <pre>
   * const re = new RegExp("^a+$", request.flags);
   * </pre>
   */
  private static TacFunction regexConstructorWithTaintedFlags() {
    TacOperand.TacVar req = request();
    TacOperand.TacVar flags = new TacOperand.TacVar("flags", TacType.STRING, false);
    List<TacInstruction> instructions = new ArrayList<>();
    instructions.add(move(flags, req, 10));
    instructions.add(
        construct(
            "RegExp.<init>/2",
            "RegExp",
            temp("re"),
            List.of(TacOperand.Const.ofString("^a+$"), flags),
            11));
    return function(SourceLanguage.TYPESCRIPT, req, instructions);
  }

  // ------------------------------------------------------------------ the run

  private List<Finding> scan(TacFunction function, DfaChecker checker) {
    TacProgram program = new TacProgram(Path.of("App.ts"), "app");
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
    if (checker instanceof CodeInjectionChecker) return CODE_RULE;
    if (checker instanceof RegexInjectionChecker) return REGEX_RULE;
    throw new IllegalArgumentException("no rule id for " + checker.getClass().getName());
  }

  private static SinkFamily family(String name) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    return config.sinkFamilies().byName(name).orElseThrow();
  }
}
