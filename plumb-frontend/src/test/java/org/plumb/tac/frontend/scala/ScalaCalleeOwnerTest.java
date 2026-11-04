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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;

/**
 * The callee signature's OWNER -- the thing that makes a sink reachable.
 *
 * <p><b>Why this test exists.</b> The sink families in {@code sink-families.yaml} are anchored to
 * a fully qualified name with a DOT separator and an optional arity suffix:
 * {@code '^java\.sql\.Statement\.execute(/\d+)?'}. The DFA matches a call against them by its
 * callee signature, so the signature's SHAPE is the whole contract.
 *
 * <p>The Scala frontend used to emit {@code "java.sql.Statement#execute"} -- dotc's own
 * convention: a hash separator and no arity. Every pattern in the file is anchored with
 * {@code ^} to a dotted qualified name, so <b>not one of them could ever match</b>. Every taint
 * sink rule was silently inert on Scala code: the analysis ran, produced zero findings, and
 * looked like clean code.
 *
 * <p>This is the Scala counterpart of {@code KotlinCalleeOwnerTest}. Both languages are held to
 * the same contract, because they share one sink file.
 */
class ScalaCalleeOwnerTest {

  /** Every CALL's callee signature, across every function. */
  private static List<String> calleeSignatures(String source) {
    ScalaTestSupport support = new ScalaTestSupport();
    List<TacFunction> functions = support.parse(source).functions();
    List<String> sigs = new ArrayList<>();
    for (TacFunction fn : functions) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL && ins.calleeSig != null) {
          sigs.add(ins.calleeSig);
        }
      }
    }
    return sigs;
  }

  @Test
  @DisplayName("a call to a Java method carries the Java class's fully qualified owner")
  void javaCallCarriesTheJavaOwner() {
    // `stmt` is a java.sql.Statement. The call must resolve to a method DECLARED in
    // java.sql.Statement, so the owner is that class -- which is exactly what the
    // shared sink family names.
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner:
              def run(stmt: Statement, sql: String): Unit =
                stmt.execute(sql)
            """);

    assertThat(sigs)
        .as("the call's signature must name the declaring class")
        .anyMatch(s -> s.startsWith("java.sql.Statement.execute/"));
  }

  @Test
  @DisplayName("the owner's separator is a DOT, not a hash")
  void ownerSeparatorIsADot() {
    // dotc's own convention is `owner#member`. The sink families are anchored to
    // `owner.member`, so a hash makes every pattern miss.
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner:
              def run(stmt: Statement, sql: String): Unit =
                stmt.execute(sql)
            """);

    assertThat(sigs).as("no signature may use dotc's hash separator").noneMatch(s -> s.contains("#"));
  }

  @Test
  @DisplayName("the arity is the argument count")
  void arityIsTheArgumentCount() {
    // The sink patterns' trailing `(/\d+)?` group matches against the arity, so it
    // has to be the real argument count -- and the receiver is NOT an argument.
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner:
              def run(stmt: Statement, sql: String): Unit =
                stmt.execute(sql)
            """);

    assertThat(sigs).as("one argument means arity 1").anyMatch(s -> s.startsWith("java.sql.Statement.execute/1"));
  }

  @Test
  @DisplayName("a call on a RECEIVER still names the DECLARING class")
  void receiverCallNamesTheDeclaringClass() {
    // The receiver is what the call is made ON; the owner is what the method is
    // DECLARED in. It is the declaration that the sink families name, so a call
    // through a variable of a subtype must still resolve to the supertype's method
    // -- which is what a virtual dispatch does.
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner:
              def run(stmt: Statement, sql: String): Unit =
                val other = stmt
                other.execute(sql)
            """);

    assertThat(sigs)
        .as("the call's signature must name the declaring class")
        .anyMatch(s -> s.startsWith("java.sql.Statement.execute/"));
  }

  @Test
  @DisplayName("a call to a Scala library method carries the Scala class's owner")
  void scalaLibraryCallCarriesTheOwner() {
    // The language-specific sink families (`scala-io.sinks` and friends) name Scala
    // classes, so a Scala-to-Scala call must be owned too -- not left bare.
    List<String> sigs =
        calleeSignatures(
            """
            object Owner:
              def run(): Unit =
                val builder = new StringBuilder()
                builder.append("x")
            """);

    assertThat(sigs).as("a Scala-to-Scala call must still name its owner").anyMatch(s -> s.contains("."));
  }
}
