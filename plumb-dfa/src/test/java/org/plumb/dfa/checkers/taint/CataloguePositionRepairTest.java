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
import org.plumb.dfa.config.SinkFamily;
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
 * The catalogue positions that no call could supply, and the ones that were repaired.
 *
 * <p><b>Why this class exists.</b> The catalogue names, for every sink, the argument that carries
 * the taint. When that argument is one no overload of the named API can supply, the rule is
 * registered, the family matches, and the finding never appears -- a rule that reads as if it were
 * in charge and is not. The position gate in {@code CataloguePositionReachabilityTest} measures
 * that; this class pins what the repaired entries now name, and shows one of them firing.
 *
 * <p><b>What each test proves.</b> The first three say what the catalogue says: the position is the
 * RECEIVER for the APIs that take no argument, and the first argument for the one that names a
 * type. The last two say what that means: a generator the request seeded is a finding, and a
 * generator the program seeded is not.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and the engine reads the argument at the position the catalogue names. The TAC below is the one
 * the Java front end produces for the corresponding source: a CALL keeps its receiver in {@code
 * args[0]}, and a construction takes its arguments without a receiver.
 */
@DisplayName("Taint: the catalogue's positions that no call could supply")
class CataloguePositionRepairTest {

  /** The rule that reads the random family. */
  private static final String RANDOM_RULE = "dfa-taint-insecure-random";

  /** The generator call the last two tests report at. */
  private static final String NEXT_LONG = "java.util.Random.nextLong/0";

  @Test
  @DisplayName("the generators that take no argument name the receiver")
  void theGeneratorsThatTakeNoArgumentNameTheReceiver() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    for (String api :
        new String[] {
          "java.util.Random.nextLong/0",
          "java.util.Random.nextDouble/0",
          "java.util.Random.nextFloat/0",
          "java.util.Random.nextBoolean/0"
        }) {
      assertThat(
              config
                  .sinkFamilies()
                  .byName("random.sinks")
                  .orElseThrow()
                  .argIndexesFor(api, SourceLanguage.JAVA))
          .as(
              "%s takes no argument: what is weak is the generator, so the position is its"
                  + " receiver",
              api)
          .contains(SinkFamily.RECEIVER_ARG_INDEX);
    }
  }

  @Test
  @DisplayName("the file that is made read-only names the receiver")
  void theFileThatIsMadeReadOnlyNamesTheReceiver() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("file-permission.sinks")
                .orElseThrow()
                .argIndexesFor("java.io.File.setReadOnly/0", SourceLanguage.JAVA))
        .as("setReadOnly takes no argument: the path is in the File the program built")
        .contains(SinkFamily.RECEIVER_ARG_INDEX);
  }

  @Test
  @DisplayName("the reflection names the component type")
  void theReflectionNamesTheComponentType() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    assertThat(
            config
                .sinkFamilies()
                .byName("reflection.sinks")
                .orElseThrow()
                .argIndexesFor("java.lang.reflect.Array.newInstance/2", SourceLanguage.JAVA))
        .as("newInstance takes the component type and the length: the first is the type")
        .containsExactly(0);
  }

  @Test
  @DisplayName("a generator the request seeded is a finding")
  void aGeneratorTheRequestSeededIsAFinding() {
    List<Finding> findings = scan(seededGenerator(true));

    assertThat(mentions(findings, NEXT_LONG))
        .as("the seed reached the generator, so the values it produces are the request's")
        .isTrue();
  }

  @Test
  @DisplayName("a generator the program seeded is not a finding")
  void aGeneratorTheProgramSeededIsNotAFinding() {
    List<Finding> findings = scan(seededGenerator(false));

    assertThat(mentions(findings, NEXT_LONG))
        .as("a seed the program chose is not the attacker's")
        .isFalse();
  }

  // ------------------------------------------------------------------ the TAC

  /**
   * The handler that builds a generator and draws from it.
   *
   * <pre>
   * Random generator = new Random(request.getParameter("seed"));
   * long value = generator.nextLong();
   * </pre>
   *
   * @param taintedSeed whether the seed comes from the request
   * @return the function under analysis
   */
  private static TacFunction seededGenerator(boolean taintedSeed) {
    TacOperand.TacVar request = new TacOperand.TacVar("request", TacType.UNKNOWN, false);
    TacOperand.TacVar seed = new TacOperand.TacVar("seed", TacType.LONG, false);
    TacOperand.TacVar generator = new TacOperand.TacVar("generator", TacType.UNKNOWN, false);

    List<TacInstruction> instructions = new ArrayList<>();
    // seed = request
    instructions.add(taintedSeed ? move(seed, request, 8) : constant(seed, 42L, 8));
    // Random generator = new Random(seed)
    instructions.add(
        TacInstruction.builder(TacInstruction.Kind.NEW)
            .dest(generator)
            .ctorType(TacType.ofClass("java.util.Random"))
            .args(List.of(seed))
            .line(9)
            .column(1)
            .build());
    // generator.nextLong()
    instructions.add(call(NEXT_LONG, temp("value"), List.of(generator), 11));
    return new TacFunction(
        "handler",
        "app.handler(string):long",
        TacType.LONG,
        List.of(request),
        List.of(seed, generator),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.JAVA,
        IrPrecision.SOURCE_FULL);
  }

  // ------------------------------------------------------------------ the run

  private List<Finding> scan(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("App.java"), "app");
    program.addFunction(function);
    FindingReporter reporter =
        new DfaEngine(
                ConfigLoader.loadFromClasspath(),
                List.of(new InsecureRandomChecker()),
                "global",
                null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().equals(RANDOM_RULE)) out.add(f);
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

  private static TacInstruction constant(TacOperand dest, long value, int line) {
    return move(dest, TacOperand.Const.ofLong(value), line);
  }
}
