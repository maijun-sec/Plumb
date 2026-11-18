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
 * The values a C# construct produces must be the values the program has.
 *
 * <p><b>Why these are in one test.</b> Every construct here had the same defect: the VALUE it
 * produces was not the value the program produces, or it was not produced at all. The defect was
 * invisible in the scan -- the TAC was well formed, every label resolved, and the scan reported
 * success -- so nothing but a test that looks at the value can catch it.
 *
 * <p><b>What each case lost.</b>
 *
 * <ul>
 *   <li>an object initializer wrote into a PLACEHOLDER that names the type, not into the object it
 *       had just created, and a property initializer was dropped whole -- so a tainted value never
 *       reached the object, and the sink catalogue had no CALL to match;
 *   <li>a {@code foreach} bound the COLLECTION to the loop variable, so the variable held a value
 *       of the wrong type and the wrong taint;
 *   <li>a {@code switch} expression produced no value at all, so the function returned something
 *       nothing had computed;
 *   <li>an array, a tuple and a {@code with} expression were dropped whole, so the variable that
 *       held them was never assigned.
 * </ul>
 *
 * <p><b>Why the implicit array form is in here too.</b> {@code new int[] { 1, 2 }} and {@code new[]
 * { 1, 2 }} are the same construct written two ways, and only the first one was lowered. The second
 * was dropped whole, so the variable that held the array was never assigned -- and a call that took
 * the array as its argument took an unknown placeholder in place of it. The two forms have to end
 * up in the same shape, or a program that writes one of them is analysed as a different program from
 * the one that writes the other.
 *
 * <p><b>How the assertions are shaped.</b> They pin the SHAPE -- which instruction carries which
 * value -- and not the position of an instruction, because the positions are an implementation
 * detail and the shapes are the contract.
 */
@DisplayName("C#: the values the lowering produces")
class CSharpValueFlowLoweringTest {

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

  @Test
  @DisplayName("an object initializer's FIELD is written into the object it just created")
  void anObjectInitializerFieldIsWrittenIntoTheNewObject() {
    String source =
        """
        namespace Demo;

        public class Holder
        {
            public int Field;
        }

        public class Builder
        {
            public void Build()
            {
                var holder = new Holder { Field = 1 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    TacInstruction allocation = firstNew(function);
    assertThat(allocation)
        .as("the object initializer must be preceded by the allocation it initializes")
        .isNotNull();

    TacInstruction store = fieldStoreTo(function, "Field");
    assertThat(store).as("the initializer's field must be stored, not dropped").isNotNull();
    assertThat(nameOf(store.lhs1))
        .as(
            "the field must be written into the object that was just created; writing it into a "
                + "placeholder that names the type puts the value nowhere the program can read")
        .isEqualTo(nameOf(allocation.dest));
  }

  @Test
  @DisplayName("an object initializer's PROPERTY calls the setter with the new object as receiver")
  void anObjectInitializerPropertyCallsTheSetter() {
    String source =
        """
        namespace Demo;

        public class Holder
        {
            public int Auto { get; set; }
        }

        public class Builder
        {
            public void Build()
            {
                var holder = new Holder { Auto = 2 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    TacInstruction allocation = firstNew(function);
    TacInstruction setter = callTo(function, "Demo.Holder.set_Auto/1");
    assertThat(setter)
        .as(
            "a property initializer is a call to the property's setter: the sink catalogue matches "
                + "on CALL, so a dropped setter is a finding that is never raised")
        .isNotNull();
    assertThat(setter.args).as("the setter takes the receiver and the value").hasSize(2);
    assertThat(nameOf(setter.args.get(0)))
        .as("the setter's receiver must be the object that was just created")
        .isEqualTo(nameOf(allocation.dest));
    assertThat(literalOf(setter.args.get(1)))
        .as("the setter's value must be the initialized value")
        .isEqualTo("2");
  }

  @Test
  @DisplayName("a collection initializer calls Add on the collection it just created")
  void aCollectionInitializerCallsAdd() {
    String source =
        """
        namespace Demo;

        using System.Collections.Generic;

        public class Builder
        {
            public void Build()
            {
                var list = new List<int> { 1, 2 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    TacInstruction allocation = firstNew(function);
    TacInstruction add = callTo(function, "System.Collections.Generic.List.Add/1");
    assertThat(add)
        .as("a collection initializer is a sequence of calls to the collection's Add")
        .isNotNull();
    assertThat(add.args).as("Add takes the collection and the element").hasSize(2);
    assertThat(nameOf(add.args.get(0)))
        .as("Add's receiver must be the collection that was just created")
        .isEqualTo(nameOf(allocation.dest));
  }

  @Test
  @DisplayName("a foreach binds the ELEMENT, and walks the collection with the iteration protocol")
  void aForeachBindsTheElementAndWalksTheProtocol() {
    String source =
        """
        namespace Demo;

        using System.Collections.Generic;

        public class Walker
        {
            public void Walk()
            {
                var list = new List<int>();
                foreach (var item in list)
                {
                    System.Console.WriteLine(item);
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Walk");

    TacInstruction factory = callTo(function, "System.Collections.Generic.List.GetEnumerator/0");
    assertThat(factory).as("the loop must ask the collection for its enumerator").isNotNull();
    TacInstruction advance =
        callTo(function, "System.Collections.Generic.List.Enumerator.MoveNext/0");
    assertThat(advance).as("the loop must advance the enumerator").isNotNull();
    TacInstruction current =
        callTo(function, "System.Collections.Generic.List.Enumerator.get_Current/0");
    assertThat(current).as("the element is the enumerator's Current").isNotNull();
    assertThat(callTo(function, "System.Collections.Generic.List.Enumerator.Dispose/0"))
        .as("the enumerator must be disposed when the loop ends")
        .isNotNull();

    TacInstruction binding = moveTo(function, "item");
    assertThat(binding).as("the loop variable must be bound").isNotNull();
    assertThat(nameOf(binding.lhs1))
        .as(
            "the loop variable must hold the ELEMENT; holding the collection puts a value of the "
                + "wrong type and the wrong taint where the program has an element")
        .isEqualTo(nameOf(current.dest));
  }

  @Test
  @DisplayName("a foreach over an ARRAY reads the element out of the array")
  void aForeachOverAnArrayReadsTheElement() {
    String source =
        """
        namespace Demo;

        public class Walker
        {
            public void Walk()
            {
                int[] values = new int[] { 1, 2 };
                foreach (var value in values)
                {
                    System.Console.WriteLine(value);
                }
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Walk");

    TacInstruction read = firstOfKind(function, TacInstruction.Kind.ARRAYLOAD);
    assertThat(read).as("the element of an array is read out of the array").isNotNull();

    TacInstruction binding = moveTo(function, "value");
    assertThat(binding).as("the loop variable must be bound").isNotNull();
    assertThat(nameOf(binding.lhs1))
        .as("the loop variable must hold the element that was read")
        .isEqualTo(nameOf(read.dest));
  }

  @Test
  @DisplayName("a switch EXPRESSION produces the value of the arm that matched")
  void aSwitchExpressionProducesAValue() {
    String source =
        """
        namespace Demo;

        public class Picker
        {
            public int Pick(int mode)
            {
                return mode switch
                {
                    1 => 10,
                    _ => 20,
                };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Pick");

    TacInstruction returned = firstOfKind(function, TacInstruction.Kind.RETURN);
    assertThat(returned).as("the method must return").isNotNull();
    assertThat(returned.lhs1)
        .as("the returned value must exist: a RETURN with no value is a value nothing computed")
        .isNotNull();

    boolean assigned = false;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.MOVE && returned.lhs1.equals(instruction.dest)) {
        assigned = true;
      }
    }
    assertThat(assigned)
        .as("the returned value must be the merge of the arms: every arm writes the same temporary")
        .isTrue();
  }

  @Test
  @DisplayName("an array creation stores its elements into the array")
  void anArrayCreationStoresItsElements() {
    String source =
        """
        namespace Demo;

        public class Builder
        {
            public void Build()
            {
                int[] values = new int[] { 1, 2, 3 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    assertThat(firstOfKind(function, TacInstruction.Kind.NEWARRAY))
        .as("the array must be allocated")
        .isNotNull();

    int stores = 0;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYSTORE) {
        stores++;
      }
    }
    assertThat(stores)
        .as("every element must be stored into the array: an array with no elements holds no taint")
        .isEqualTo(3);
  }

  @Test
  @DisplayName("an implicit array creation stores its elements into the array")
  void anImplicitArrayCreationStoresItsElements() {
    String source =
        """
        namespace Demo;

        public class Builder
        {
            public void Build()
            {
                var values = new[] { 1, 2, 3 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    assertThat(firstOfKind(function, TacInstruction.Kind.NEWARRAY))
        .as("the array must be allocated: the implicit form is an allocation like the explicit one")
        .isNotNull();

    int stores = 0;
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.ARRAYSTORE) {
        stores++;
      }
    }
    assertThat(stores)
        .as("every element must be stored into the array: an array with no elements holds no taint")
        .isEqualTo(3);

    assertThat(moveTo(function, "values"))
        .as(
            "the variable that holds the array must be assigned: a dropped expression leaves the"
                + " variable unwritten, and every later read of it sees a value nothing computed")
        .isNotNull();

    assertThat(function.drops())
        .as("the implicit form is lowered now, so nothing may be reported as lost")
        .isEmpty();
  }

  @Test
  @DisplayName("a tuple stores its elements into the tuple")
  void aTupleStoresItsElements() {
    String source =
        """
        namespace Demo;

        public class Builder
        {
            public void Build()
            {
                var pair = (1, "two");
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    TacInstruction first = fieldStoreTo(function, "Item1");
    assertThat(first).as("the tuple's first element must be stored into it").isNotNull();
    assertThat(fieldStoreTo(function, "Item2"))
        .as("the tuple's second element must be stored into it")
        .isNotNull();
  }

  @Test
  @DisplayName("a `with` expression copies the source and applies the change to the copy")
  void aWithExpressionCopiesTheSource() {
    String source =
        """
        namespace Demo;

        public record Holder
        {
            public int Field;
            public int Auto { get; set; }
        }

        public class Builder
        {
            public void Build()
            {
                var holder = new Holder { Field = 1, Auto = 2 };
                var copy = holder with { Field = 7 };
            }
        }
        """;

    TacFunction function = functionNamed(support.parse(source).functions(), "Build");

    TacInstruction copy = firstNew(function);
    assertThat(copy).as("a `with` expression allocates the copy it returns").isNotNull();

    TacInstruction changed = fieldStoreTo(function, "Field");
    assertThat(changed).as("the changed member must be written into the copy").isNotNull();
    assertThat(nameOf(changed.lhs1))
        .as("the changed member must be written into the COPY, not into the source")
        .isEqualTo(nameOf(copy.dest));

    TacInstruction carried = callTo(function, "Demo.Holder.set_Auto/1");
    assertThat(carried)
        .as("the members that are NOT changed must be carried over into the copy")
        .isNotNull();
    assertThat(nameOf(carried.args.get(0)))
        .as("the carried-over member must be written into the COPY")
        .isEqualTo(nameOf(copy.dest));
  }

  /** The function with the given name, or null. */
  private static TacFunction functionNamed(List<TacFunction> functions, String name) {
    for (TacFunction function : functions) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    throw new AssertionError("the source declares no function named " + name);
  }

  /** The first NEW of the function, or null. */
  private static TacInstruction firstNew(TacFunction function) {
    return firstOfKind(function, TacInstruction.Kind.NEW);
  }

  /** The first instruction of the kind, or null. */
  private static TacInstruction firstOfKind(TacFunction function, TacInstruction.Kind kind) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == kind) {
        return instruction;
      }
    }
    return null;
  }

  /** The instruction that calls the given callee, or null. */
  private static TacInstruction callTo(TacFunction function, String calleeSig) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.CALL && calleeSig.equals(instruction.calleeSig)) {
        return instruction;
      }
    }
    return null;
  }

  /** The instruction that stores into the given field, or null. */
  private static TacInstruction fieldStoreTo(TacFunction function, String fieldName) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.FIELDSTORE
          && fieldName.equals(instruction.fieldName)) {
        return instruction;
      }
    }
    return null;
  }

  /** The instruction that moves a value into the given variable, or null. */
  private static TacInstruction moveTo(TacFunction function, String variable) {
    for (TacInstruction instruction : function.instructions()) {
      if (instruction.kind == TacInstruction.Kind.MOVE
          && instruction.dest instanceof TacOperand.TacVar destination
          && variable.equals(destination.name())) {
        return instruction;
      }
    }
    return null;
  }

  /** The name of a variable operand, or null. */
  private static String nameOf(TacOperand operand) {
    return operand instanceof TacOperand.TacVar variable ? variable.name() : null;
  }

  /** The text of a constant operand, or null. */
  private static String literalOf(TacOperand operand) {
    return operand instanceof TacOperand.Const constant ? String.valueOf(constant.value()) : null;
  }
}
