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
package org.plumb.tac.frontend.scala;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The Scala frontend must lower a PARAMETERLESS CALL.
 *
 * <p><b>Why this test exists.</b> A Scala {@code s.trim} is not an {@code Apply} -- there are no
 * arguments to apply -- so dotc represents it as a {@code Select}. The frontend treated every
 * method selection as either a field read or an unlowerable "method reference used as a value",
 * and a parameterless call on a value fell into the second case. Three things went wrong at once:
 *
 * <ol>
 *   <li>The call produced NO instruction, so the operation's effect on taint was unknown.
 *   <li>The call was reported as an unsupported construct, so an ordinary file was flagged as
 *       degraded -- and the reported reason ("method reference") was wrong: {@code s.trim} is a
 *       call, not a reference.
 *   <li>The operation could never reach a rule: no callee key meant no sink pattern matched and no
 *       semantics table entry applied.
 * </ol>
 *
 * <p>Measured before the fix: 24 of the standard library's String operations were dropped this
 * way. After the fix, they are ordinary CALLs with a resolvable owner.
 */
class ScalaParameterlessCallTest {

  /** The shared scaffolding. */
  private static ScalaTestSupport support;

  @BeforeAll
  static void setUp() {
    support = new ScalaTestSupport();
  }

  /** Every CALL's callee signature, across every function. */
  private static List<String> calleeSignatures(String source) {
    List<String> sigs = new ArrayList<>();
    for (TacFunction fn : support.parse(source).functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL && ins.calleeSig != null) {
          sigs.add(ins.calleeSig);
        }
      }
    }
    return sigs;
  }

  @Test
  @DisplayName("a parameterless call on a value lowers to a CALL with a resolvable owner")
  void parameterlessCallLowersToACall() {
    // "s.trim" is a Select in dotc, not an Apply. It must still become a CALL.
    List<String> sigs =
        calleeSignatures(
            """
            package demo

            class Owner {
              def run(s: String): String =
                s.trim
            }
            """);

    assertThat(sigs)
        .as("the call must be lowered, and the owner must be the declaring class")
        .anyMatch(s -> s.startsWith("java.lang.String.trim/"));
  }

  @Test
  @DisplayName("a parameterless call is not reported as an unsupported construct")
  void parameterlessCallIsNotReported() {
    // The old behaviour reported "method reference 'trim' was not lowered" and downgraded the
    // function's precision. That is a lie: the call is lowered now, and the reported reason was
    // wrong even when it was reported.
    TacFrontend.ParseResult result =
        support.parse(
            """
            package demo

            class Owner {
              def run(s: String): String =
                s.trim
            }
            """);

    assertThat(result.problems()).as("problems: %s", result.problems()).isEmpty();
    assertThat(result.precision())
        .as("a fully lowered function keeps its full precision")
        .isEqualTo(org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
  }

  @Test
  @DisplayName("the StringOps-only operations lower to a CALL too")
  void stringOpsOperationsLowerToACall() {
    // These resolve to scala.collection.StringOps, not java.lang.String: they are the
    // operations a Scala String has that a Java String does not.
    List<String> sigs =
        calleeSignatures(
            """
            package demo

            class Owner {
              def run(s: String): String =
                s.take(2)
            }
            """);

    assertThat(sigs)
        .as("a StringOps operation must name the class that declares it")
        .anyMatch(s -> s.startsWith("scala.collection.StringOps.take/"));
  }

  @Test
  @DisplayName("a call with a DEFAULT argument names its method")
  void aCallWithADefaultArgumentNamesItsMethod() {
    // "sortBy(f)" also takes an implicit Ordering, so dotc wraps the call in an outer
    // Apply. The wrapper hides the method's name, and the emitted key was
    // "scala.collection.StringOps.<unknown>/1" -- a key that matches no sink pattern
    // and no semantics table, so the call was effectively invisible.
    List<String> sigs =
        calleeSignatures(
            """
            package demo

            class Owner {
              def run(s: String): String =
                s.sortBy(identity)
            }
            """);

    assertThat(sigs)
        .as("the method's name must survive the default-argument wrapper")
        .anyMatch(s -> s.startsWith("scala.collection.StringOps.sortBy/"));
    assertThat(sigs).as("no call may be left unnamed").noneMatch(s -> s.contains("<unknown>"));
  }

  @Test
  @DisplayName("a character literal does not abort the lowering")
  void aCharacterLiteralDoesNotAbortTheLowering() {
    // A Scala Char is boxed as java.lang.Character, which is NOT a java.lang.Number. The
    // literal lowering cast it to Number, the cast threw, and the throw aborted the whole
    // function -- so a file containing a single 'x' lost every instruction after it.
    List<String> sigs =
        calleeSignatures(
            """
            package demo

            class Owner {
              def run(s: String): String =
                val c = 'x'
                s.trim
            }
            """);

    assertThat(sigs)
        .as("the call after the literal must survive")
        .anyMatch(s -> s.startsWith("java.lang.String.trim/"));
  }

  @Test
  @DisplayName("the standard library's String operations all lower to a named call")
  void theStandardLibraryStringOperationsAllLower() {
    // The measured catalogue. Every one of these was dropped before the fix.
    String source =
        """
        package demo

        class Owner {
          def run(s: String, b: String): Unit = {
            val a1 = s.trim
            val a2 = s.substring(1)
            val a3 = s.toLowerCase
            val a4 = s.toUpperCase
            val a5 = s.replace("x", "y")
            val a6 = s.split(",")
            val a7 = s.length
            val a8 = s.isEmpty
            val a9 = s.startsWith("a")
            val a10 = s.indexOf("a")
            val a11 = s.matches(".*")
            val a12 = s.hashCode
            val a13 = s.getBytes()
            val a14 = s.concat(b)
            val a15 = s.take(2)
            val a16 = s.drop(1)
            val a17 = s.stripPrefix("x")
            val a18 = s.toInt
            val a19 = s.reverse
            val a20 = s.capitalize
            val a21 = s.format("%s", s)
            val a22 = s.padTo(3, 'x')
            val a23 = s.appended('x')
            val a24 = s.mkString
          }
        }
        """;

    TacFrontend.ParseResult result = support.parse(source);
    TreeSet<String> sigs = new TreeSet<>(calleeSignatures(source));

    // Every operation named above must appear as a call, named after the class that
    // declares it. A single one that is missing took a whole category of rules with it.
    List<String> expected =
        List.of(
            "java.lang.String.trim/0",
            "java.lang.String.substring/1",
            "java.lang.String.toLowerCase/0",
            "java.lang.String.toUpperCase/0",
            "java.lang.String.replace/2",
            "java.lang.String.split/1",
            "java.lang.String.length/0",
            "java.lang.String.isEmpty/0",
            "java.lang.String.startsWith/1",
            "java.lang.String.indexOf/1",
            "java.lang.String.matches/1",
            "java.lang.String.hashCode/0",
            "java.lang.String.getBytes/0",
            "java.lang.String.concat/1",
            "scala.collection.StringOps.take/1",
            "scala.collection.StringOps.drop/1",
            "scala.collection.StringOps.stripPrefix/1",
            "scala.collection.StringOps.toInt/0",
            "scala.collection.StringOps.reverse/0",
            "scala.collection.StringOps.capitalize/0",
            "scala.collection.StringOps.format/1",
            "scala.collection.StringOps.padTo/2",
            "scala.collection.StringOps.appended/1",
            "scala.collection.StringOps.mkString/0");

    List<String> missing = new ArrayList<>();
    for (String want : expected) {
      if (!sigs.contains(want)) {
        missing.add(want);
      }
    }

    assertThat(missing).as("operations that produced no named call. All were: %s", sigs).isEmpty();
    assertThat(result.problems())
        .as("these operations are lowered, so nothing is reported: %s", result.problems())
        .isEmpty();
  }
}
