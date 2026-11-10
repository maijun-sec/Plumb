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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
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
 * The sources that are an ENTRY of a container: the script languages' process-level inputs.
 *
 * <p><b>Why the key is enough.</b> The catalogue names callee signatures, so every source it
 * declares is a call -- until a language reads its untrusted input out of a container. Measured
 * against the side cars, {@code process.env.HOME} lowers to an {@code ARRAYLOAD} whose base is the
 * variable {@code process} and whose index is the constant {@code "env"}, and {@code sys.argv[1]}
 * lowers to the same shape with the key {@code "argv"}. The key is right there in the IR, so a rule
 * can name the entry and the front end does not have to synthesise a call.
 *
 * <p><b>Why the entry and not the container.</b> {@code process.pid}, {@code process.platform} and
 * {@code process.cwd} are facts about the machine, not about the attacker. Marking the whole {@code
 * process} object as a source would claim them, so the declaration names the entry.
 *
 * <p><b>Why this closes an inconsistency.</b> The same catalogue already treats {@code os.Getenv}
 * and {@code os.Args} as sources for Go, {@code System.getenv} for C# and {@code
 * System.getProperty} for Java. The script languages were the only ones with no equivalent, and
 * they are the ones whose inputs are read out of a container.
 *
 * <p><b>What the negative controls are for.</b> Two of them, and both matter: an ordinary entry of
 * the same container ({@code process.pid}) must report nothing, and the same program in another
 * language must report nothing either -- {@code process.env} means the environment in a script and
 * nothing at all in a Java file.
 */
@DisplayName("Taint: the sources that are an entry of a container")
class ScriptSourceEntryTest {

  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacOperand.TacVar local(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, false);
  }

  private static TacInstruction arrayLoad(TacOperand dest, TacOperand base, String key, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
        .dest(dest)
        .lhs1(base)
        .lhs2(str(key))
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

  /**
   * The handler, as the front end lowers it.
   *
   * <p>The value is read out of a container, and the container's entry is the one the catalogue
   * names:
   *
   * <pre>
   *   const home = process.env.HOME;
   *   exec(home);
   * </pre>
   *
   * @param language the language the function is written in
   * @param key the entry of the container the value is read from
   * @return the function under analysis
   */
  private static TacFunction handler(SourceLanguage language, String key) {
    TacOperand.TacVar cp = local("cp");
    TacOperand.TacVar home = local("home");

    List<TacInstruction> instructions = new ArrayList<>();
    // const home = process.env.HOME;
    instructions.add(arrayLoad(temp("t_t1"), local("process"), key, 3));
    instructions.add(arrayLoad(temp("t_t2"), temp("t_t1"), "HOME", 3));
    instructions.add(move(home, temp("t_t2"), 3));
    // exec(home);
    instructions.add(call("child_process.exec/2", temp("t_t3"), List.of(cp, home), 5));
    return new TacFunction(
        "handler",
        "app.handler(string):void",
        TacType.VOID,
        List.of(),
        List.of(cp, home),
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

  private List<Finding> scan(SourceLanguage language, String key) {
    TacProgram program = new TacProgram(Path.of("app.ts"), "app");
    program.addFunction(handler(language, key));
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  // ------------------------------------------------------------------ catalogue

  @Test
  @DisplayName("the catalogue names the entries the script languages read")
  void theCatalogueNamesTheEntriesTheScriptLanguagesRead() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(config.sinkFamilies().sourceVariableNames(SourceLanguage.TYPESCRIPT))
        .as("the environment and the command line of a Node process")
        .contains("process.env", "process.argv", "process.stdin");
    assertThat(config.sinkFamilies().sourceVariableNames(SourceLanguage.PYTHON3))
        .as("the environment and the command line of a Python process")
        .contains("os.environ", "sys.argv");
    assertThat(config.sinkFamilies().sourceVariableNames(SourceLanguage.JAVA))
        .as("a Java program has no process")
        .isEmpty();
  }

  // ------------------------------------------------------------------ end to end

  @Test
  @DisplayName("a value read out of the environment reaches the command")
  void aValueReadOutOfTheEnvironmentReachesTheCommand() {
    List<Finding> findings = scan(SourceLanguage.TYPESCRIPT, "env");

    assertThat(hasFinding(findings, COMMAND_RULE, "child_process.exec/2"))
        .as("the environment is the program's environment, and the attacker chose some of it")
        .isTrue();
  }

  @Test
  @DisplayName("a value read out of an ordinary entry of the same container reaches nothing")
  void aValueReadOutOfAnOrdinaryEntryOfTheSameContainerReachesNothing() {
    List<Finding> findings = scan(SourceLanguage.TYPESCRIPT, "pid");

    assertThat(hasFinding(findings, COMMAND_RULE, "child_process.exec/2"))
        .as("the process id is a fact about the machine, not about the attacker")
        .isFalse();
  }

  @Test
  @DisplayName("the same program in Java reports nothing, because the name is the script's")
  void theSameProgramInJavaReportsNothingBecauseTheNameIsTheScripts() {
    List<Finding> findings = scan(SourceLanguage.JAVA, "env");

    assertThat(hasFinding(findings, COMMAND_RULE, "child_process.exec/2"))
        .as("a Java variable called process is not Node's process")
        .isFalse();
  }
}
