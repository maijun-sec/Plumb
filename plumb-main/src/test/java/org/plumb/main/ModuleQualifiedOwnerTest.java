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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.cg.CallGraph;
import org.plumb.dfa.cg.CallGraphBuilder;
import org.plumb.dfa.cg.FunctionNode;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.summary.SummaryKeyOwnership;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * Two classes of the same name in two modules: the engine has to keep them apart.
 *
 * <h2>Why this is worth pinning</h2>
 *
 * <p>A script language's class identity is its MODULE plus its name, and the engine has to be able
 * to say which of two classes it means. Three places carry that identity, and each of them used to
 * drop it:
 *
 * <ol>
 *   <li><b>The owner index of the call graph.</b> It was keyed by the owner's SIMPLE name, so two
 *       classes of that name shared one key, and the resolution answered with whichever of them the
 *       index happened to hold -- "the first writer wins", decided by the engine's schedule rather
 *       than by the program. The index now carries the qualified owner as well, and the lookup asks
 *       for it FIRST.
 *   <li><b>The keys the summary is published under.</b> The write end published the class-qualified
 *       key only when the signature itself carried no owner, so a signature that named its owner
 *       left the declaring class out of the key space entirely -- and two classes of that name then
 *       contended for one key that neither of them owned.
 *   <li><b>The sink catalogue.</b> The catalogue names a class by its own name, so a receiver that
 *       carries a module would not be recognised. The engine drops the module and reads the
 *       receiver the way a receiver with no module is read.
 * </ol>
 *
 * <p><b>What is pinned here is the SHAPE, not the wording.</b> The assertions say which of the two
 * classes a call reaches, and they say it through the public API -- the call graph that is built,
 * the keys that are published -- so a change in the spelling that keeps the meaning would still
 * pass, and a change that keeps the spelling but loses the meaning would not.
 */
@DisplayName("The engine: two classes of the same name in two modules stay apart")
class ModuleQualifiedOwnerTest {

  /** The class of the first module: it is the one the call reaches. */
  private static final String MODULE_A = "modA/store";

  /** The class of the second module: it is the one the call must NOT reach. */
  private static final String MODULE_B = "modB/store";

  @Test
  @DisplayName("the owner index knows a class by the name that tells the two apart")
  void theOwnerIndexKnowsAClassByTheNameThatTellsTheTwoApart() {
    TacFunction function =
        method(MODULE_A + ".UserStore", MODULE_A + ".UserStore.run(string):string");

    assertThat(CallGraphBuilder.ownerKeysOf(function))
        .as(
            "the index has to carry the qualified owner, because the simple name addresses EVERY"
                + " class of that name -- in every module")
        .containsExactly(MODULE_A + ".UserStore.run/1", "UserStore.run/1");
  }

  @Test
  @DisplayName("a call that names the module reaches the class of that module")
  void aCallThatNamesTheModuleReachesTheClassOfThatModule() {
    TacProgram program = programWithTwoModules();
    CallGraph graph = new CallGraphBuilder(program).build();

    assertThat(calleesOf(graph, "caller.toFirst(string):void"))
        .as(
            "the call names the class of ONE module, so the edge has to reach that class and not"
                + " the other one")
        .containsExactly(MODULE_A + ".UserStore.run(string):string");
    assertThat(calleesOf(graph, "caller.toSecond(string):void"))
        .as(
            "and the OTHER module's class has to be reached by its own call: an index that holds"
                + " both classes under one key answers with whichever of them it holds first, and"
                + " which one that is would be decided by the engine's schedule")
        .containsExactly(MODULE_B + ".UserStore.run(string):string");
  }

  @Test
  @DisplayName("the summary of a class is published under the name that tells the two apart")
  void theSummaryOfAClassIsPublishedUnderTheNameThatTellsTheTwoApart() {
    TacFunction function =
        method(MODULE_A + ".UserStore", MODULE_A + ".UserStore.run(string):string");

    assertThat(SummaryKeyOwnership.publishedKeys(function))
        .as("the declaring class is what tells two same-named classes apart")
        .contains(MODULE_A + ".UserStore.run/1");
  }

  @Test
  @DisplayName("the summary of a class is also published under the simple name it is known by")
  void theSummaryOfAClassIsAlsoPublishedUnderTheSimpleNameItIsKnownBy() {
    TacFunction function =
        method(MODULE_A + ".UserStore", MODULE_A + ".UserStore.run(string):string");

    assertThat(SummaryKeyOwnership.publishedKeys(function))
        .as(
            "a call site that cannot resolve the module asks under the SIMPLE name, and that call"
                + " site has to find the summary -- otherwise qualifying the owner would quietly"
                + " take the summary away from the calls that could not name the module")
        .contains("UserStore.run/1");
  }

  @Test
  @DisplayName("the name two classes share is published by neither of them")
  void theNameTwoClassesShareIsPublishedByNeitherOfThem() {
    TacProgram program = programWithTwoModules();
    SummaryKeyOwnership ownership = SummaryKeyOwnership.of(program);

    String shared = "UserStore.run/1";
    assertThat(ownership.claimantsOf(shared))
        .as("both classes claim the name they share")
        .hasSize(2);
    for (TacFunction function : program.functions()) {
      if (!function.signature().contains(".run(")) continue;
      assertThat(ownership.isExclusivelyClaimedBy(shared, SummaryKeyOwnership.identityOf(function)))
          .as(
              "a key two classes claim is published by NEITHER of them: the call site then finds"
                  + " nothing there and falls through to the specific keys -- silence rather than"
                  + " another class's facts")
          .isFalse();
    }
  }

  @Test
  @DisplayName("a receiver that carries a module still reaches the catalogue entry that names it")
  void aReceiverThatCarriesAModuleStillReachesTheCatalogueEntryThatNamesIt() {
    SinkFamily family = family("format-string.sinks");

    // The catalogue names the class by its own name, so the entry is found by the bare receiver.
    assertThat(family.matches("String.format/1", SourceLanguage.TYPESCRIPT))
        .as("the bare receiver is what the catalogue names")
        .isTrue();
    assertThat(family.matches(MODULE_A + ".String.format/1", SourceLanguage.TYPESCRIPT))
        .as(
            "the module is part of the class's identity and it stays in the key space, but the"
                + " catalogue names a class by its own name -- so the module is dropped and the"
                + " receiver is read the way a receiver with no module is read.  Without that, the"
                + " qualification would SILENTLY take the claim away")
        .isTrue();
  }

  @Test
  @DisplayName("the positions of the tainted argument are the same with and without the module")
  void thePositionsOfTheTaintedArgumentAreTheSameWithAndWithoutTheModule() {
    SinkFamily family = family("format-string.sinks");

    assertThat(family.argIndexesFor(MODULE_A + ".String.format/1", SourceLanguage.TYPESCRIPT))
        .as(
            "the positions have to come from the SAME pattern that the match settles on, or the two"
                + " would answer different questions about one call")
        .isEqualTo(family.argIndexesFor("String.format/1", SourceLanguage.TYPESCRIPT));
  }

  @Test
  @DisplayName("a package that is written with dots is not a module and is not dropped")
  void aPackageThatIsWrittenWithDotsIsNotAModuleAndIsNotDropped() {
    SinkFamily family = family("format-string.sinks");

    // A Java receiver carries its package with DOTS, so the module-stripping fallback never
    // fires for it: there is no "/" in the receiver to drop.
    assertThat(family.matches("java.lang.String.format/1", SourceLanguage.JAVA))
        .as("the fully qualified Java receiver is still matched by the strict form")
        .isTrue();
    assertThat(family.matches("com.acme.String.format/1", SourceLanguage.JAVA))
        .as(
            "and a package that is NOT the catalogue's own is not one either: the relaxed form is"
                + " not consulted for a qualified receiver, so this one is not a sink")
        .isFalse();
  }

  @Test
  @DisplayName("a Go package path is matched by the entry that spells the whole path")
  void aGoPackagePathIsMatchedByTheEntryThatSpellsTheWholePath() {
    SinkFamily family = family("sql.sinks");

    // The Go entries spell the whole package path ("database/sql.DB"), so the strict form
    // matches and the module-stripping fallback never runs for them.
    assertThat(family.matches("database/sql.DB.Exec/1", SourceLanguage.GO))
        .as("the Go entry names the package path, so the receiver matches it as it is")
        .isTrue();
  }

  // ------------------------------------------------------------------ the fixtures

  /**
   * A program with two modules that each declare a class of the same name, and TWO callers: one
   * reaches the class of the first module, the other the class of the second.
   *
   * <p>Two callers, not one: with a single caller the engine's schedule decides which of the two
   * classes the index holds first, and the first one happens to be the right one half the time.
   */
  private static TacProgram programWithTwoModules() {
    TacProgram program = new TacProgram(Path.of("demo.ts"), "demo");
    program.addFunction(
        method(MODULE_A + ".UserStore", MODULE_A + ".UserStore.run(string):string"));
    program.addFunction(
        method(MODULE_B + ".UserStore", MODULE_B + ".UserStore.run(string):string"));
    program.addFunction(caller("toFirst", MODULE_A + ".UserStore.run/1"));
    program.addFunction(caller("toSecond", MODULE_B + ".UserStore.run/1"));
    return program;
  }

  /** A method of a class, with the class it is declared in. */
  private static TacFunction method(String enclosingClass, String signature) {
    return new TacFunction(
        "run",
        signature,
        TacType.STRING,
        List.of(new TacOperand.TacVar("s", TacType.STRING, false)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        enclosingClass);
  }

  /** A caller whose one call names the class of one module. */
  private static TacFunction caller(String name, String calleeSig) {
    TacInstruction call =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig(calleeSig)
            .args(
                List.of(
                    new TacOperand.TacVar("store", TacType.STRING, false),
                    new TacOperand.TacVar("userInput", TacType.STRING, false)))
            .dest(new TacOperand.TacVar("t_t1", TacType.STRING, true))
            .build();
    return new TacFunction(
        name,
        "caller." + name + "(string):void",
        TacType.VOID,
        List.of(new TacOperand.TacVar("userInput", TacType.STRING, false)),
        List.of(),
        List.of(call),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null);
  }

  /** The signatures of the functions the given caller calls. */
  private static List<String> calleesOf(CallGraph graph, String callerSignature) {
    FunctionNode caller =
        graph.nodes().stream()
            .filter(node -> node.signature().equals(callerSignature))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the caller is not in the graph"));
    List<String> out = new ArrayList<>();
    graph.callsFrom(caller).forEach(site -> out.add(site.callee().signature()));
    return out;
  }

  /** The sink family of the given name, from the catalogue the scan uses. */
  private static SinkFamily family(String name) {
    return ConfigLoader.loadFromClasspath().sinkFamilies().byName(name).orElseThrow();
  }
}
