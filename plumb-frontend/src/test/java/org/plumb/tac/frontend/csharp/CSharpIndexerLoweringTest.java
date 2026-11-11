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
package org.plumb.tac.frontend.csharp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The lowering of an INDEXER, which is the second thing that looks like a field but is not one.
 *
 * <p><b>The question.</b> In C#, {@code builder["Password"] = value} and {@code array[0] = value}
 * are written the same way. They are not the same thing. The first is a call to an indexer's setter
 * -- a method named {@code set_Item} -- and the second is a store into an array.
 *
 * <p><b>Why the distinction decides whether findings exist.</b> The sink catalogue is queried on
 * {@code CALL} instructions. A {@code FIELDSTORE} and an {@code ARRAYSTORE} carry no callee
 * signature at all, so a sink that is reached through an indexer is not "less precisely matched" --
 * it is <i>structurally unreachable</i>, and the scan reports success while the finding is absent.
 * The catalogue already carries the C# symbols: the {@code credential.sinks} family names {@code
 * System.Data.Common.DbConnectionStringBuilder.set_Item}. The catalogue was right; the TAC was not.
 *
 * <p><b>What decides the distinction.</b> The resolved symbol, and nothing else. An indexer is an
 * {@code IIndexerSymbol} with a {@code set_Item} accessor; an array is an {@code IArrayTypeSymbol}.
 * The spelling of the expression is the same in both cases, so the spelling cannot decide it.
 *
 * <p><b>Why the receiver still comes first.</b> The TAC's call convention is "args[0] is the
 * receiver, args[1..] are the actual arguments". An indexer's setter takes the key and then the
 * value, so the value is the SECOND user argument -- and it lands at args[2]. The catalogue names
 * user arguments, and the engine applies the receiver offset itself, so the catalogue writes {@code
 * arg_index: 1} for the value of a {@code set_Item} and the TAC puts it at args[2]. A sidecar that
 * put the value at args[1] would produce TAC that matches the catalogue and reports nothing.
 */
@DisplayName("C#: the TAC lowering of an indexer")
class CSharpIndexerLoweringTest {

  private static CSharpTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new CSharpTestSupport();
  }

  @AfterAll
  static void tearDown() {
    if (support != null) {
      support.close();
    }
  }

  /**
   * A source with an indexer and an array, written the same way.
   *
   * <p>The indexer is a custom one so the test does not depend on a framework's reference
   * assemblies; the catalogue's real C# symbols are checked separately in {@code
   * CSharpSinkMatchingTest}.
   */
  private static final String SOURCE =
      """
      namespace Demo;

      public class Sink
      {
          public void Run(string input)
          {
              var holder = new Holder();
              holder["key"] = input;

              var array = new string[1];
              array[0] = input;
          }
      }

      public class Holder
      {
          public string this[string key]
          {
              get { return key; }
              set { }
          }
      }
      """;

  @Test
  @DisplayName("an indexer assignment becomes a CALL to set_Item, not an ARRAYSTORE")
  void indexerAssignmentBecomesACall() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction setterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("set_Item")) {
        setterCall = instruction;
      }
    }

    assertThat(setterCall)
        .as(
            "an indexer assignment must lower to a CALL to the indexer's setter: "
                + "an ARRAYSTORE carries no callee signature, so the sink catalogue "
                + "cannot match it and the finding is structurally unreachable")
        .isNotNull();
    assertThat(setterCall.calleeSig)
        .as("the callee signature must be the canonical owner.method/arity form")
        .isEqualTo("Demo.Holder.set_Item/2");
  }

  @Test
  @DisplayName("the indexer call carries the receiver, then the key, then the value")
  void theIndexerCallCarriesTheReceiverThenTheKeyThenTheValue() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction setterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("set_Item")) {
        setterCall = instruction;
      }
    }

    assertThat(setterCall).isNotNull();
    assertThat(setterCall.args).as("the receiver, the key, and the assigned value").hasSize(3);
    assertThat(((TacOperand.TacVar) setterCall.args.get(0)).name())
        .as("args[0] is the RECEIVER, by the TAC's call convention")
        .isEqualTo("holder");
    assertThat(((TacOperand.TacVar) setterCall.args.get(2)).name())
        .as(
            "the assigned value is the SECOND user argument -- the indexer's key comes first -- "
                + "so it lands at args[2], which is what the catalogue's arg_index 1 names")
        .isEqualTo("input");
  }

  @Test
  @DisplayName("an array assignment stays an ARRAYSTORE")
  void arrayAssignmentStaysAnArrayStore() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    long arrayStores =
        function.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
            .count();

    assertThat(arrayStores)
        .as("an array really is an array: only the indexer becomes a call")
        .isEqualTo(1);
  }

  @Test
  @DisplayName("the two are distinguished by the resolved symbol, not the spelling")
  void indexerAndArrayAreDistinguishedBySymbol() {
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    // Both are written `x[...] = value`. The shapes differ. The only thing
    // that can decide this is the resolved symbol: an indexer has a setter,
    // an array does not.
    long setterCalls =
        function.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .filter(i -> i.calleeSig != null && i.calleeSig.contains("set_Item"))
            .count();

    assertThat(setterCalls).as("the indexer assignment is a CALL").isEqualTo(1);
  }

  @Test
  @DisplayName("an indexer READ becomes a CALL to get_Item, not an ARRAYLOAD")
  void indexerReadBecomesACall() {
    // The read side matters for the same reason the write side does: a
    // value read through an indexer and then passed to a sink is a finding,
    // and an ARRAYLOAD carries no callee signature for the catalogue to match.
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                var holder = new Holder();
                var value = holder["key"];
                System.Console.WriteLine(value);
            }
        }

        public class Holder
        {
            public string this[string key]
            {
                get { return key; }
                set { }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    TacInstruction getterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("get_Item")) {
        getterCall = instruction;
      }
    }

    assertThat(getterCall)
        .as(
            "an indexer read must lower to a CALL to the indexer's getter: "
                + "an ARRAYLOAD carries no callee signature")
        .isNotNull();
    assertThat(getterCall.calleeSig)
        .as("the callee signature must be the canonical owner.method/arity form")
        .isEqualTo("Demo.Holder.get_Item/1");
    assertThat(getterCall.args).as("the receiver and the key").hasSize(2);
  }

  @Test
  @DisplayName("an array READ stays an ARRAYLOAD")
  void arrayReadStaysAnArrayLoad() {
    String source =
        """
        namespace Demo;

        public class Sink
        {
            public void Run(string input)
            {
                var array = new string[1];
                array[0] = input;
                var value = array[0];
                System.Console.WriteLine(value);
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Run");

    long arrayLoads =
        function.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
            .count();

    assertThat(arrayLoads).as("an array really is an array").isEqualTo(1);
  }

  @Test
  @DisplayName("the indexer assignment does not clobber the value it assigns")
  void theIndexerAssignmentDoesNotClobberTheValue() {
    // The array lowering put the ASSIGNED VALUE in the store's destination --
    // so `holder["key"] = input` wrote the parameter into itself, and the
    // value never reached the sink. The call shape has no destination at all:
    // the value is an argument, and nothing is overwritten.
    TacFunction function = functionNamed(support.parse(SOURCE).functions(), "Run");

    TacInstruction setterCall = null;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL
          && instruction.calleeSig != null
          && instruction.calleeSig.contains("set_Item")) {
        setterCall = instruction;
      }
    }

    assertThat(setterCall).isNotNull();
    assertThat(setterCall.dest)
        .as(
            "a call has no destination: the assigned value is an argument, not a written-to"
                + " variable")
        .isNull();
  }

  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError(
        "no function named '"
            + name
            + "' in "
            + functions.stream().map(TacFunction::name).toList());
  }
}
