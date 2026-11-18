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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.csharp.CSharpSidecarClient;
import org.plumb.tac.frontend.csharp.CSharpTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * A C# collection hands out the tainted element that was written into it.
 *
 * <p><b>The shape.</b>
 *
 * <pre>
 *   var list = new List&lt;string&gt;();
 *   list.Add(input);
 *   foreach (var item in list) {
 *     Process.Start(item);
 *   }
 * </pre>
 *
 * <p>The value enters the program at {@code input}, is put INTO the collection by a call, and comes
 * back OUT of it by a loop. The taint has to survive both of those steps, and it used to survive
 * neither in a language whose subscripts are array positions.
 *
 * <p><b>Where the taint was lost, and what was NOT the cause.</b> The collection write is NOT
 * missing from the TAC: the C# front end lowers {@code list.Add(x)} to a call that names the real
 * method, {@code System.Collections.Generic.List.Add/1}, with the collection in the receiver slot
 * and the value in the argument slot. {@link
 * #theCollectionWriteIsInTheTacAsACallThatNamesTheRealMethod()} pins that shape, because the
 * obvious reading of the symptom -- "the write never reached the TAC" -- would send the fix to the
 * front end, where nothing is broken.
 *
 * <p>The taint was lost in the ENGINE, in two places:
 *
 * <ul>
 *   <li>the per-element tracker only hears about array STORES ({@code a[i] = v}), and a collection
 *       is filled by a CALL, so the tracker never learned that the collection held a tainted value;
 *   <li>the rule that asks "what does an iterator over this container hand out" read the keyed
 *       store for the languages whose subscripts name entries of a namespace, and read ONLY the
 *       per-element tracker for the languages whose subscripts are array positions -- so the
 *       collection's own accumulated taint was invisible to the loop.
 * </ul>
 *
 * <p>The second one is the interesting one: the same program in TypeScript was reported and the
 * same program in C# was not, and the difference was the language's subscript discipline rather
 * than anything about the program.
 *
 * <p><b>Why the programs are C# and run through the side car.</b> The point of these tests is the
 * ENGINE's answer, not the engine's input. A hand-written TAC would be the author's idea of what
 * the front end emits, and a wrong idea would make the test pass for the wrong reason. So the
 * source goes through the real front end, and the TAC it produces is the one the engine is asked
 * about.
 *
 * <p><b>Why the Java control is here.</b> The rule is the engine's, not the C# front end's, so a
 * C#-only suite would pass even if the rule had been written to spell out C# and nothing else. The
 * Java control runs the same shape through the same engine and has to be reported as well.
 *
 * <p><b>Why the clean case is here.</b> A rule that treats every collection as tainted passes the
 * first tests and is useless. So the control -- a collection whose element is clean -- is asserted
 * to stay clean. If it ever goes red, the collection has become "everything is tainted", and the
 * other tests prove nothing.
 *
 * <p><b>Why the map case is here.</b> A map's write takes a KEY and a VALUE, and only the value is
 * one of the map's values. A rule that taints the map from the key makes every later read of the
 * map look tainted -- including the read of a key whose value was never written from tainted data.
 */
@DisplayName("Taint: a C# collection hands out the tainted element it was given")
class CsharpCollectionTaintTest {

  /** The rule id the command-injection checker reports under. */
  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  /** The frontend under test. */
  private static CSharpTacFrontend frontend;

  /** The directory the frontend is given. */
  private static Path sourceRoot;

  /**
   * Whether the .NET toolchain was found.
   *
   * <p>It is resolved in a STATIC INITIALIZER, not in {@code @BeforeAll}: JUnit evaluates an
   * {@code @EnabledIf} condition BEFORE the lifecycle callbacks run, so a flag set in
   * {@code @BeforeAll} would still read {@code false} and every test would be skipped.
   */
  private static final boolean TOOLCHAIN_AVAILABLE;

  static {
    TOOLCHAIN_AVAILABLE = CSharpSidecarClient.locateExecutable(null) != null;
  }

  @BeforeAll
  static void setUp() throws IOException {
    sourceRoot = Files.createTempDirectory("plumb-csharp-collection-test");
    frontend = new CSharpTacFrontend(CSharpSidecarClient.locateExecutable(null));
  }

  @AfterAll
  static void tearDown() {
    if (frontend != null) {
      frontend.close();
    }
  }

  /** A collection filled with a tainted value, and the value read back out by a loop. */
  private static final String COLLECTION_WRITE =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string>();
              list.Add(input);
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The same collection, built by the collection INITIALIZER rather than by an explicit call. */
  private static final String COLLECTION_INITIALIZER =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string> { input };
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The same collection, read by its INDEXER rather than by a loop. */
  private static final String COLLECTION_INDEX =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string>();
              list.Add(input);
              System.Diagnostics.Process.Start(list[0]);
          }
      }
      """;

  /** A queue, whose write is spelled {@code Enqueue} rather than {@code Add}. */
  private static final String QUEUE_WRITE =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var queue = new System.Collections.Generic.Queue<string>();
              queue.Enqueue(input);
              foreach (var item in queue)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** A stack, whose write is spelled {@code Push}. */
  private static final String STACK_WRITE =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var stack = new System.Collections.Generic.Stack<string>();
              stack.Push(input);
              foreach (var item in stack)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** A list written at a POSITION rather than at the end. */
  private static final String LIST_INSERT =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string>();
              list.Insert(0, input);
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** A list that receives another collection's elements. */
  private static final String LIST_ADD_RANGE =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(System.Collections.Generic.List<string> items)
          {
              var list = new System.Collections.Generic.List<string>();
              list.AddRange(items);
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /**
   * A list that receives another collection's elements, where that collection is written as an
   * ARRAY LITERAL.
   *
   * <p>The literal is the IMPLICIT array form, {@code new[] { input }}: the element type is
   * inferred rather than written down. The tainted value is the array's ELEMENT, not the array
   * reference, so this measures whether the receiver accumulates what the argument CARRIES rather
   * than only what the argument IS.
   */
  private static final String LIST_ADD_RANGE_LITERAL =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string>();
              list.AddRange(new[] { input });
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The same shape, with an element that never came from outside the program. */
  private static final String LIST_ADD_RANGE_LITERAL_CLEAN =
      """
      namespace Demo;

      public class Coll
      {
          public void Run()
          {
              var list = new System.Collections.Generic.List<string>();
              list.AddRange(new[] { "safe" });
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The same shape, with the array written in the EXPLICIT form. */
  private static final String LIST_ADD_RANGE_EXPLICIT =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var list = new System.Collections.Generic.List<string>();
              list.AddRange(new string[] { input });
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The same shape, with a value that never came from outside the program. */
  private static final String COLLECTION_CLEAN =
      """
      namespace Demo;

      public class Coll
      {
          public void Run()
          {
              var list = new System.Collections.Generic.List<string>();
              list.Add("safe");
              foreach (var item in list)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** The collection itself came from outside, so everything inside it did too. */
  private static final String CONTAINER_TAINTED =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(System.Collections.Generic.List<string> items)
          {
              foreach (var item in items)
              {
                  System.Diagnostics.Process.Start(item);
              }
          }
      }
      """;

  /** A map written under a tainted KEY, and read at a DIFFERENT key. */
  private static final String MAP_TAINTED_KEY =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string key)
          {
              var map = new System.Collections.Generic.Dictionary<string, string>();
              map[key] = "safe";
              var value = map["other"];
              System.Diagnostics.Process.Start(value);
          }
      }
      """;

  /** A map written under a tainted VALUE, and read back out by a loop over its pairs. */
  private static final String MAP_WRITE =
      """
      namespace Demo;

      public class Coll
      {
          public void Run(string input)
          {
              var map = new System.Collections.Generic.Dictionary<string, string>();
              map["k"] = input;
              foreach (var pair in map)
              {
                  System.Diagnostics.Process.Start(pair.Value);
              }
          }
      }
      """;

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a list that receives an array literal hands the tainted element back out")
  void aListThatReceivesAnArrayLiteralHandsTheTaintedElementBackOut() {
    // The receiver receives the argument's CONTENTS, so the contents' taint is
    // what it accumulates. Here the argument is an array whose ELEMENT is the
    // tainted one: the array reference itself never was, because nothing ever
    // assigned a tainted value to it.
    List<Finding> implicit = scan(LIST_ADD_RANGE_LITERAL, "AddRangeImplicit.cs");

    assertThat(hasFinding(implicit, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "list.AddRange(new[] { input }) -- the array's element is the tainted one, so the list"
                + " has to accumulate what the array carries")
        .isTrue();

    // The explicit form is measured too, and it has to answer the same way: the
    // two spellings are the same construct, and a rule that answered one of
    // them and not the other would report one program and not its twin.
    List<Finding> explicit = scan(LIST_ADD_RANGE_EXPLICIT, "AddRangeExplicit.cs");

    assertThat(hasFinding(explicit, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "list.AddRange(new string[] { input }) -- the same construct written the other way has"
                + " to answer the same way")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a list that receives a clean array literal stays clean")
  void aListThatReceivesACleanArrayLiteralStaysClean() {
    // The reverse control: if the receiver accumulated something for every
    // array it was handed, this would report, and the test above would prove
    // nothing.
    List<Finding> findings = scan(LIST_ADD_RANGE_LITERAL_CLEAN, "AddRangeClean.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "list.AddRange(new[] { \"safe\" }) -- nothing tainted went into the array, so nothing"
                + " tainted is in the list")
        .isFalse();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the collection write is in the TAC as a call that names the real method")
  void theCollectionWriteIsInTheTacAsACallThatNamesTheRealMethod() {
    // The premise of this whole round: the write is NOT missing from the TAC.
    // The front end lowers `list.Add(x)` to a call whose name is the method the
    // C# program actually calls, with the collection in the receiver slot and
    // the value in the argument slot. A rule that matched any other name would
    // be matching a name no C# program contains.
    List<TacInstruction> instructions = instructionsOf(COLLECTION_WRITE, "Coll.cs");

    TacInstruction write = null;
    for (TacInstruction instruction : instructions) {
      if ("System.Collections.Generic.List.Add/1".equals(instruction.calleeSig)) {
        write = instruction;
        break;
      }
    }

    assertThat(write)
        .as(
            "the C# front end must lower `list.Add(input)` to a call that names the real method,"
                + " or the engine has nothing to recognise")
        .isNotNull();
    assertThat(write.args)
        .as(
            "the call carries the collection in the receiver slot and the value in the argument"
                + " slot")
        .hasSize(2);
    assertThat(write.args.get(0))
        .as("the receiver slot holds the collection")
        .isInstanceOf(TacOperand.TacVar.class);
    assertThat(((TacOperand.TacVar) write.args.get(0)).name()).isEqualTo("list");
    assertThat(((TacOperand.TacVar) write.args.get(1)).name())
        .as("the argument slot holds the value that was added")
        .isEqualTo("input");
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the taint of an element crosses the collection write and the loop")
  void theTaintOfAnElementCrossesTheCollectionWriteAndTheLoop() {
    List<Finding> findings = scan(COLLECTION_WRITE, "Coll.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "list.Add(input); foreach (var item in list) Process.Start(item) -- the value goes into"
                + " the collection and comes back out of it, so the taint has to survive both"
                + " steps")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the collection initializer hands out the tainted element too")
  void theCollectionInitializerHandsOutTheTaintedElementToo() {
    List<Finding> findings = scan(COLLECTION_INITIALIZER, "Init.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "new List<string> { input } -- the initializer is a collection write in another"
                + " spelling, and it must reach the sink the same way")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the same element is handed out when the collection is read by its indexer")
  void theSameElementIsHandedOutWhenTheCollectionIsReadByItsIndexer() {
    List<Finding> findings = scan(COLLECTION_INDEX, "Index.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "list.Add(input); Process.Start(list[0]) -- no loop is involved, so this measures the"
                + " element on its own")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("the other container spellings hand the written value back out too")
  void theOtherContainerSpellingsHandTheWrittenValueBackOutToo() {
    // A collection is a family, and the family spells its write differently in
    // every language: `add`, `Enqueue`, `Push`, `Insert`, `AddRange`. A rule
    // that knew only one of them would report the family's other half as a
    // clean program, and the gap would look like a missing finding rather than
    // like a missing rule.
    Map<String, String> shapes = new LinkedHashMap<>();
    shapes.put("Queue.Enqueue", QUEUE_WRITE);
    shapes.put("Stack.Push", STACK_WRITE);
    shapes.put("List.Insert", LIST_INSERT);
    shapes.put("List.AddRange", LIST_ADD_RANGE);

    for (Map.Entry<String, String> shape : shapes.entrySet()) {
      List<Finding> findings = scan(shape.getValue(), shape.getKey().replace('.', '_') + ".cs");

      assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
          .as(
              "the collection is written through %s, so the value that was written must come back"
                  + " out of the loop",
              shape.getKey())
          .isTrue();
    }
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a collection with a clean element hands out clean values")
  void aCollectionWithACleanElementHandsOutCleanValues() {
    List<Finding> findings = scan(COLLECTION_CLEAN, "Clean.cs");

    assertThat(findings)
        .as(
            "list.Add(\"safe\"); foreach (var item in list) Process.Start(item) -- nothing came"
                + " from outside the program, so the command is not a finding")
        .isEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a collection that came from outside hands out tainted values")
  void aCollectionThatCameFromOutsideHandsOutTaintedValues() {
    List<Finding> findings = scan(CONTAINER_TAINTED, "Container.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "the collection is a parameter -- it came from outside the program, so everything"
                + " inside it did too")
        .isTrue();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a tainted key does not make the values of a map look tainted")
  void aTaintedKeyDoesNotMakeTheValuesOfAMapLookTainted() {
    List<Finding> findings = scan(MAP_TAINTED_KEY, "Map.cs");

    assertThat(findings)
        .as(
            "map[key] = \"safe\"; Process.Start(map[\"other\"]) -- the key is not one of the map's"
                + " values, and the value that is read was never written from tainted data")
        .isEmpty();
  }

  @Test
  @EnabledIf("toolchainAvailable")
  @DisplayName("a map hands out the tainted value it was given")
  void aMapHandsOutTheTaintedValueItWasGiven() {
    List<Finding> findings = scan(MAP_WRITE, "MapWrite.cs");

    assertThat(hasFinding(findings, COMMAND_RULE, "System.Diagnostics.Process.Start"))
        .as(
            "map[\"k\"] = input; foreach (var pair in map) Process.Start(pair.Value) -- a map is a"
                + " collection whose write takes a key and a value, and the value is the one that"
                + " comes back out")
        .isTrue();
  }

  @Test
  @DisplayName("the Java spelling of the same shape crosses the taint too")
  void theJavaSpellingOfTheSameShapeCrossesTheTaintToo() {
    // The rule belongs to the engine, so a C#-only suite would pass even if the
    // rule had been written to spell out C# and nothing else. This is the same
    // shape -- a collection written through a call, read back through an
    // iterator -- in the language that spells the protocol `iterator` / `next`.
    //
    // The TAC is written out by hand because the Java front end needs a compiler
    // and this control is about the ENGINE's shared rule, not about Java's front
    // end. The instructions are the ones the Java front end emits for this
    // shape.
    List<Finding> findings = scanJavaCollection();

    assertThat(hasFinding(findings, COMMAND_RULE, "java.lang.Runtime.exec"))
        .as(
            "list.add(tainted); for (String s : list) Runtime.exec(s) -- the engine's collection"
                + " rule is shared, so the Java spelling must keep working")
        .isTrue();
  }

  // ---- the C# harness -------------------------------------------------------------------------

  /**
   * Lower a C# source with the real front end and ask the engine about it.
   *
   * @param source the C# source
   * @param fileName the name the source is written under
   * @return the findings the command-injection rule produced
   */
  private static List<Finding> scan(String source, String fileName) {
    TacProgram program = new TacProgram(sourceRoot.resolve(fileName), "Coll");
    for (TacFunction function : functionsOf(source, fileName)) {
      program.addFunction(function);
    }
    return run(program);
  }

  /**
   * The instructions of the single function the source lowers to.
   *
   * @param source the C# source
   * @param fileName the name the source is written under
   * @return every instruction of every function the source declares
   */
  private static List<TacInstruction> instructionsOf(String source, String fileName) {
    List<TacInstruction> out = new ArrayList<>();
    for (TacFunction function : functionsOf(source, fileName)) {
      out.addAll(function.instructions());
    }
    return out;
  }

  /**
   * Lower a C# source with the real front end.
   *
   * @param source the C# source
   * @param fileName the name the source is written under
   * @return the functions the source declares
   */
  private static List<TacFunction> functionsOf(String source, String fileName) {
    Path file = sourceRoot.resolve(fileName);
    try {
      Files.writeString(file, source);
    } catch (IOException e) {
      throw new IllegalStateException("could not write the C# source", e);
    }
    List<TacFunction> functions = frontend.parse(source, file).functions();
    assertThat(functions).as("the C# source must lower to at least one function").isNotEmpty();
    return functions;
  }

  // ---- the Java control -----------------------------------------------------------------------

  /**
   * {@code list.add(tainted); it = list.iterator(); s = it.next(); Runtime.exec(s)}
   *
   * @return the findings the command-injection rule produced
   */
  private static List<Finding> scanJavaCollection() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar list = var("list");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar collection = temp("t_list");
    out.add(
        TacInstruction.builder(TacInstruction.Kind.NEW)
            .dest(collection)
            .ctorType(TacType.ofClass("java.util.ArrayList"))
            .line(11)
            .column(1)
            .build());
    out.add(move(list, collection, 11));

    out.add(call("java.util.List.add/1", temp("t_added"), List.of(list, request), 12));
    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("java.util.List.iterator/0", iterator, List.of(list), 13));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("java.util.Iterator.next/0", element, List.of(iterator), 13));
    out.add(move(x, element, 13));
    out.add(
        call(
            "java.lang.Runtime.exec/1",
            temp("t_result"),
            // The receiver slot holds the object the call is made on, and the
            // value is the first ARGUMENT after it.
            List.of(str("java.lang.Runtime"), x),
            14));

    TacFunction function =
        new TacFunction(
            "handle",
            // The signature is part of the OBJECT LABEL the points-to solver hands out, and the
            // keyed taint store is keyed by that label. Two programs in the same JVM that allocate
            // the same type at the same site under the same signature therefore share the store's
            // keys -- so a test that reuses a common signature can be broken by a test that ran
            // before it. This one names itself.
            "app.java_collection_control(any):any",
            TacType.UNKNOWN,
            List.of(request),
            List.of(list, x),
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

    TacProgram program = new TacProgram(Path.of("app.java"), "app");
    program.addFunction(function);
    return run(program);
  }

  // ---- the engine ---------------------------------------------------------------------------

  /**
   * Run the command-injection checker over a program.
   *
   * @param program the program to analyse
   * @return the findings the rule produced
   */
  private static List<Finding> run(TacProgram program) {
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        out.add(finding);
      }
    }
    return out;
  }

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding finding : findings) {
      if (!finding.ruleId().equals(ruleId)) {
        continue;
      }
      if (finding.message().contains(callee)) {
        return true;
      }
    }
    return false;
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

  /** Whether the .NET toolchain is available. */
  static boolean toolchainAvailable() {
    return TOOLCHAIN_AVAILABLE;
  }
}
