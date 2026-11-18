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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.arkts.ArktsSidecarClient;
import org.plumb.tac.frontend.arkts.ArktsTacFrontend;
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
 * A TypeScript {@code for ... of} loop hands out the container's tainted elements.
 *
 * <p><b>Why this needed its own test.</b> The iteration protocol has a different NAME in every
 * language that has one. Java and C# write {@code iterator} / {@code next}; Python writes {@code
 * __iter__} / {@code __next__}; TypeScript writes a COMPUTED property name, {@code
 * Iterator[Symbol.iterator]}, and reaches the element through the {@code value} member of the
 * iterator result. The engine's iterator propagation recognises the protocol by name, so a name it
 * does not know is a loop whose elements come out clean -- and a value that arrived from outside
 * the program never reaches the sink.
 *
 * <p><b>Why the programs are written in TypeScript and run through the side car.</b> The point of
 * these tests is the ENGINE's answer, not the engine's input shape. A hand-written TAC would be the
 * author's idea of what the front end emits, and a wrong idea would make the test pass for the
 * wrong reason. So the source goes through the real front end, and the TAC it produces is the one
 * the engine is asked about.
 *
 * <p><b>Why the subscript control is here.</b> It reads the SAME container's element without any
 * protocol at all. If the subscript reports and the loop does not, then the container's taint is on
 * record and the break is in the protocol -- which is the difference between two quite different
 * bugs, and the difference between fixing the engine and fixing nothing.
 *
 * <p><b>Why the Java control is here.</b> The engine's iterator propagation is shared by every
 * language. A change to it that fixed TypeScript and broke Java would pass a TypeScript-only test.
 *
 * <p><b>Why the clean case is here.</b> A propagation that treats every container as tainted passes
 * the first three tests and is useless. So the control -- a container whose element is clean -- is
 * asserted to stay clean. If it ever goes red, the tracker has become "everything is tainted", and
 * the other three tests prove nothing.
 */
@DisplayName("Taint: a TypeScript for..of hands out the container's tainted elements")
class TypeScriptIteratorTaintTest {

  /** The sink: a shared family, so it applies to every language. */
  private static final String CODE_INJECTION_RULE = "dfa-taint-code-injection";

  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  /** The element was written into the container, so the container holds a tainted entry. */
  private static final String TS_ELEMENT_TAINTED =
      """
      export function forOfElementTainted(request: any): any {
        const cmd = request["cmd"];
        const items = [cmd];
        for (const item of items) {
          eval(item);
        }
        return items;
      }
      """;

  /** The container itself came from outside, so everything inside it did too. */
  private static final String TS_CONTAINER_TAINTED =
      """
      export function forOfContainerTainted(request: any): any {
        const items = request["cmds"];
        for (const item of items) {
          eval(item);
        }
        return items;
      }
      """;

  /** The same container, read by subscript: no protocol is involved at all. */
  private static final String TS_SUBSCRIPT_CONTROL =
      """
      export function forOfSubscriptControl(request: any): any {
        const items = request["cmds"];
        eval(items[0]);
        return items;
      }
      """;

  /** Nothing came from outside the program, so the evaluated value is not a finding. */
  private static final String TS_CLEAN_CONTROL =
      """
      export function forOfCleanControl(): any {
        const items = ["safe"];
        for (const item of items) {
          eval(item);
        }
        return items;
      }
      """;

  /** The same loop in ArkTS, which lowers it the same way. */
  private static final String ARKTS_CONTAINER_TAINTED =
      """
      export function arktsForOfContainerTainted(request: any): any {
        const items = request["cmds"];
        for (const item of items) {
          eval(item);
        }
        return items;
      }
      """;

  @Test
  @DisplayName("the taint of an element crosses the TypeScript iteration protocol")
  void theTaintOfAnElementCrossesTheTypeScriptIterationProtocol(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    List<Finding> findings = scanTs(TS_ELEMENT_TAINTED, directory.resolve("element.ts"));

    assertThat(hasFinding(findings, CODE_INJECTION_RULE, "eval.eval/1"))
        .as(
            "cmd = request[\"cmd\"]; items = [cmd]; for (const item of items) eval(item) -- the"
                + " element is the value member of the iterator result, so the taint has to"
                + " survive the protocol")
        .isTrue();
  }

  @Test
  @DisplayName("a container that is itself tainted hands out tainted values")
  void aContainerThatIsItselfTaintedHandsOutTaintedValues(@TempDir Path directory)
      throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    List<Finding> findings = scanTs(TS_CONTAINER_TAINTED, directory.resolve("container.ts"));

    assertThat(hasFinding(findings, CODE_INJECTION_RULE, "eval.eval/1"))
        .as(
            "items = request[\"cmds\"]; for (const item of items) eval(item) -- the container came"
                + " from outside the program, so everything inside it did too")
        .isTrue();
  }

  @Test
  @DisplayName("the same container read by subscript reaches the sink")
  void theSameContainerReadBySubscriptReachesTheSink(@TempDir Path directory) throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    List<Finding> findings = scanTs(TS_SUBSCRIPT_CONTROL, directory.resolve("subscript.ts"));

    assertThat(hasFinding(findings, CODE_INJECTION_RULE, "eval.eval/1"))
        .as(
            "items = request[\"cmds\"]; eval(items[0]) -- the container's taint is on record, so"
                + " a loop over the same container must reach the sink as well")
        .isTrue();
  }

  @Test
  @DisplayName("a clean container hands out clean values")
  void aCleanContainerHandsOutCleanValues(@TempDir Path directory) throws IOException {
    assumeTrue(
        tsAvailable(),
        "the TypeScript front end needs Node and its compiler dependency: run npm ci in"
            + " plumb-frontend/ts-sidecar");

    List<Finding> findings = scanTs(TS_CLEAN_CONTROL, directory.resolve("clean.ts"));

    assertThat(findings)
        .as(
            "items = [\"safe\"]; for (const item of items) eval(item) -- nothing came from outside"
                + " the program, so the evaluated value is not a finding")
        .isEmpty();
  }

  @Test
  @DisplayName("the Java spelling of the protocol still crosses the taint")
  void theJavaSpellingOfTheProtocolStillCrossesTheTaint() {
    List<Finding> findings = scanJava();

    assertThat(hasFinding(findings, COMMAND_RULE, "java.lang.Runtime.exec/1"))
        .as(
            "arr[0] = tainted; it = arr.iterator(); s = it.next() -- the engine's iterator"
                + " propagation is shared, so the Java spelling must keep working")
        .isTrue();
  }

  @Test
  @DisplayName("the ArkTS dialect of the same loop crosses the taint too")
  void theArkTsDialectOfTheSameLoopCrossesTheTaintToo(@TempDir Path directory) throws IOException {
    assumeTrue(
        arktsAvailable(),
        "the ArkTS front end needs Node and the sidecar's own compiler dependency: run npm ci in"
            + " plumb-frontend/arkts-sidecar");

    Path file = directory.resolve("arkts.ts");
    Files.writeString(file, ARKTS_CONTAINER_TAINTED);
    TacFrontend.ParseResult result;
    try (ArktsTacFrontend frontend = new ArktsTacFrontend(SourceLanguage.ARKTS)) {
      result = frontend.parse(ARKTS_CONTAINER_TAINTED, file, null);
    }
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    List<Finding> findings = scan(result, file, new CodeInjectionChecker());

    assertThat(hasFinding(findings, CODE_INJECTION_RULE, "eval.eval/1"))
        .as(
            "ArkTS lowers the same loop the same way, so the same protocol has to be recognised"
                + " there too")
        .isTrue();
  }

  // ---- the harness ---------------------------------------------------------------------------

  /**
   * Lower the TypeScript source with the real front end, then ask the engine about it.
   *
   * @param source the TypeScript source
   * @param file the file the source is written to (the front end reads it from disk)
   * @return the findings the engine reported
   */
  private static List<Finding> scanTs(String source, Path file) throws IOException {
    Files.writeString(file, source);
    TacFrontend.ParseResult result =
        new TsTacFrontend(SourceLanguage.TYPESCRIPT).parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();
    return scan(result, file, new CodeInjectionChecker());
  }

  /**
   * {@code arr[0] = tainted; it = arr.iterator(); s = it.next(); Runtime.exec(s)}
   *
   * <p>The TAC is written out by hand because the Java front end lives in another module, and this
   * control is about the ENGINE's shared iterator propagation rather than about the Java front end.
   */
  private static List<Finding> scanJava() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar array = var("arr");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar temporary = temp("t_array");
    out.add(newArray(temporary, 11));
    out.add(arrayStore(temporary, i(0), request, 11));
    out.add(move(array, temporary, 11));

    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("java.util.List.iterator/0", iterator, List.of(array), 12));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("java.util.Iterator.next/0", element, List.of(iterator), 12));
    out.add(move(x, element, 12));
    out.add(command("java.lang.Runtime.exec/1", x, 13));

    TacFunction function =
        new TacFunction(
            "handle",
            // The signature is part of the OBJECT LABEL the points-to solver hands out, and the
            // keyed taint store is keyed by that label. Two programs in the same JVM that allocate
            // the same type at the same site under the same signature therefore share the store's
            // keys -- so a test that reuses a common signature can be broken by a test that ran
            // before it. This one names itself.
            "app.ts_iterator_java_control(any):any",
            TacType.UNKNOWN,
            List.of(request),
            List.of(array, x),
            out,
            List.of(),
            List.of(),
            Map.of(),
            Map.of(),
            null,
            SourceOrigin.SOURCE,
            List.of(),
            SourceLanguage.JAVA,
            IrPrecision.SOURCE_FULL);

    TacProgram program = new TacProgram(Path.of("app.ts"), "app");
    program.addFunction(function);
    return run(program, new CommandInjectionChecker());
  }

  private static List<Finding> scan(TacFrontend.ParseResult result, Path file, DfaChecker checker) {
    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }
    return run(program, checker);
  }

  private static List<Finding> run(TacProgram program, DfaChecker checker) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        out.add(finding);
      }
    }
    return out;
  }

  private static boolean hasFinding(List<Finding> findings, String rule, String callee) {
    for (Finding finding : findings) {
      if (!rule.equals(finding.ruleId())) {
        continue;
      }
      if (finding.message().contains(callee)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the TypeScript front end can run here. */
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

  /** Whether the ArkTS front end can run here. */
  private static boolean arktsAvailable() {
    Path node = ArktsSidecarClient.locateNode();
    Path script = ArktsSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null
        && Files.isDirectory(root.resolve("node_modules").resolve("ohos-typescript"));
  }

  // ---- the instruction builders ----------------------------------------------------------------

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, false);
  }

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacOperand.Const i(int value) {
    return TacOperand.Const.ofInt(value);
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

  /** The command sink: the receiver slot holds the object, the value is the first argument. */
  private static TacInstruction command(String callee, TacOperand value, int line) {
    return call(callee, temp("t_result"), List.of(str("runtime"), value), line);
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction arrayStore(
      TacOperand receiver, TacOperand key, TacOperand value, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
        .lhs1(receiver)
        .lhs2(key)
        .dest(value)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction newArray(TacOperand dest, int line) {
    return TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
        .dest(dest)
        .ctorType(TacType.arrayOf(TacType.UNKNOWN))
        .line(line)
        .column(1)
        .build();
  }
}
