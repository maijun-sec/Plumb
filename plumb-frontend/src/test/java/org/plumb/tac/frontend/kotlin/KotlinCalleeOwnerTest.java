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
package org.plumb.tac.frontend.kotlin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The callee signature's OWNER -- the thing that makes a sink reachable.
 *
 * <p><b>Why this test exists.</b> The sink families in {@code sink-families.yaml} are anchored to
 * a fully qualified name with a dot separator and an arity suffix:
 * {@code '^java\.sql\.Statement\.execute(/\d+)?'}. The DFA matches a call against them by its
 * callee signature.
 *
 * <p>The Kotlin frontend used to emit {@code "execute/1"} -- no owner at all. That signature
 * matches <b>not one pattern in the file</b>: every pattern is anchored with {@code ^} to a
 * qualified name. So every taint sink rule was silently inert on Kotlin code. The analysis ran,
 * produced zero findings, and looked like clean code.
 *
 * <p>The Scala frontend emitted {@code "java.sql.Statement#execute"} -- an owner, but separated by
 * {@code #} (dotc's convention) and with no arity. Also no match.
 *
 * <p>This test pins the SHAPE, because the shape is what the matching depends on. A signature that
 * is "obviously right" but formatted differently is still invisible.
 */
class KotlinCalleeOwnerTest {

  /** Every CALL's callee signature, across every function. */
  private static List<String> calleeSignatures(String source) {
    KotlinTestSupport support = new KotlinTestSupport();
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

            class Owner {
              fun run(stmt: Statement, sql: String) {
                stmt.execute(sql)
              }
            }
            """);

    assertThat(sigs)
        .as("the call's signature must name the declaring class")
        .anyMatch(s -> s.startsWith("java.sql.Statement.execute/"));
  }

  @Test
  @DisplayName("the owner's separator is a DOT, not a hash")
  void ownerSeparatorIsADot() {
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner {
              fun run(stmt: Statement, sql: String) {
                stmt.execute(sql)
              }
            }
            """);

    for (String sig : sigs) {
      assertThat(sig)
          .as("the sink patterns are anchored to a dotted qualified name")
          .doesNotContain("#");
    }
  }

  @Test
  @DisplayName("the signature carries the argument count as a suffix")
  void signatureCarriesTheArity() {
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner {
              fun run(stmt: Statement, sql: String) {
                stmt.execute(sql)
                stmt.executeQuery(sql)
                stmt.executeUpdate(sql, 1)
              }
            }
            """);

    assertThat(sigs)
        .as("the arity is what the pattern's trailing (/\\d+)? group matches")
        .anyMatch(s -> s.equals("java.sql.Statement.execute/1"))
        .anyMatch(s -> s.equals("java.sql.Statement.executeQuery/1"))
        .anyMatch(s -> s.equals("java.sql.Statement.executeUpdate/2"));
  }

  @Test
  @DisplayName("a Kotlin property stays a field read, because it is not a method")
  void kotlinPropertyStaysAFieldRead() {
    // `line.length` on a Kotlin String is a KOTLIN property -- the Kotlin
    // standard library declares it as `val length: Int`, not as a method. So it
    // must stay a FIELDLOAD.
    //
    // Turning it into a CALL would invent a method that does not exist, and it
    // would make the Kotlin frontend disagree with the Kotlin language. The
    // distinction is decided by the symbol's origin: a Java origin means the
    // property syntax is a Java method; a Kotlin origin means it is a property.
    List<TacInstruction> instructions =
        new KotlinTestSupport()
            .parse(
                """
                class Owner {
                  fun run(line: String): Int = line.length
                }
                """)
            .functions()
            .stream()
            .flatMap(fn -> fn.instructions().stream())
            .toList();

    assertThat(instructions)
        .as("a Kotlin property must not be invented as a method call")
        .noneMatch(i -> i.kind == TacInstruction.Kind.CALL && i.calleeSig != null
            && i.calleeSig.contains("length"));
  }

  @Test
  @DisplayName("a Java method written as a Kotlin property becomes a call to the Java method")
  void javaPropertySyntaxBecomesAJavaCall() {
    // `sb.length` where sb is a java.lang.StringBuilder: Kotlin exposes the
    // Java method `length()` as a property. Lowering it as a FIELDLOAD would
    // name a field that does not exist, and it would never reach a sink family
    // -- the families are anchored to METHOD names.
    List<String> sigs =
        calleeSignatures(
            """
            import java.lang.StringBuilder

            class Owner {
              fun run(sb: StringBuilder): Int = sb.length
            }
            """);

    assertThat(sigs)
        .as("a Java method written as a Kotlin property must reach the Java method")
        .anyMatch(s -> s.startsWith("java.lang.StringBuilder.length/"));
  }

  @Test
  @DisplayName("a call on a RECEIVER still names the DECLARING class")
  void receiverCallNamesTheDeclaringClass() {
    // The receiver is what the call is made ON; the owner is what the method is
    // DECLARED in. It is the declaration that the sink families name, so a call
    // through a variable of a subtype must still resolve to the supertype's
    // method -- which is what a virtual dispatch does.
    List<String> sigs =
        calleeSignatures(
            """
            import java.sql.Statement

            class Owner {
              fun run(stmt: Statement, sql: String) {
                val other = stmt
                other.execute(sql)
              }
            }
            """);

    assertThat(sigs)
        .as("the owner comes from the resolved symbol, not the receiver's syntax")
        .anyMatch(s -> s.startsWith("java.sql.Statement.execute/"));
  }

  @Test
  @DisplayName("a local function call keeps a bare name, so the call graph edge survives")
  void localCallKeepsABareName() {
    // A local function has no declaring class, so there is no owner to name. The
    // bare name is still what the call graph's bare-name map keys on, so the edge
    // must not be lost.
    List<String> sigs =
        calleeSignatures(
            """
            class Owner {
              fun helper(): Int = 1

              fun run(): Int {
                fun local(): Int = 2
                return local() + helper()
              }
            }
            """);

    assertThat(sigs)
        .as("a local call must still be visible to the call graph")
        .anyMatch(s -> s.startsWith("local/"));
  }

  @Test
  @DisplayName("the receiver is still args[0], as the TAC convention requires")
  void receiverIsStillTheFirstArgument() {
    // The owner fix must not disturb the CALL's argument layout. The TAC
    // convention is that the receiver is args[0]; the null-dereference checker
    // reads it from there.
    KotlinTestSupport support = new KotlinTestSupport();
    List<TacFunction> functions =
        support
            .parse(
                """
                class Owner {
                  fun run(receiver: StringBuilder) {
                    receiver.append("x")
                  }
                }
                """)
            .functions();

    boolean sawCall = false;
    for (TacFunction fn : functions) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind != TacInstruction.Kind.CALL) continue;
        sawCall = true;
        assertThat(ins.args)
            .as("the receiver must be the first argument")
            .isNotEmpty();
        assertThat(ins.args.get(0))
            .as("the receiver must be a variable, not a null constant")
            .isNotInstanceOf(TacOperand.Const.class);
      }
    }
    assertThat(sawCall).as("the source must produce a CALL").isTrue();
  }
}
