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
package org.plumb.dfa.checkers.controlflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaChecker;
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

/**
 * Go's {@code sync.Mutex}, and the two lock checks that never saw it.
 *
 * <p><b>Why the test builds the TAC by hand.</b> The point is the RECOGNITION: that Go's UPPER case
 * {@code .Lock()} / {@code .Unlock()} are the lock primitives, and that Java's lower case ones
 * still are. Building the TAC by hand puts the callee signature and the receiver under the test's
 * control, so the test measures the rule rather than the front end. A test that went through the Go
 * front end would also skip itself whenever the Go toolchain is not on the path -- which is exactly
 * when a rule like this needs to be measured.
 *
 * <p><b>Why the Java cases are here.</b> The recognition is widened rather than replaced, so the
 * Java behaviour has to be pinned: the lower case spelling still fires, and the UPPER case one is
 * Go's alone.
 */
@DisplayName("Go's sync.Mutex: a lock is taken before it is released")
class GoLockPairingTest {

  private static final String UNLOCK_RULE = "plumb.dfa-controlflow-unlock-not-locked";
  private static final String DEADLOCK_RULE = "plumb.dfa-controlflow-deadlock-ab-ba";

  // ---------------------------------------------------------------- CWE-832

  @Test
  @DisplayName("a Go unlock without a lock is reported")
  void aGoUnlockWithoutALockIsReported() {
    List<Finding> findings =
        unlockFindings(
            goFunction(
                "release",
                "example.com/release",
                List.of(call("sync.Mutex.Unlock/0", var("mu"), 11), returnInstruction())));

    assertThat(findings)
        .as("Go panics with 'sync: unlock of unlocked mutex' when the lock was not taken")
        .hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-832");
    assertThat(findings.get(0).message())
        .as("the text names Go's failure, not Java's")
        .contains("sync: unlock of unlocked mutex");
  }

  @Test
  @DisplayName("a Go unlock after a lock is not reported")
  void aGoUnlockAfterALockIsNotReported() {
    List<Finding> findings =
        unlockFindings(
            goFunction(
                "use",
                "example.com/use",
                List.of(
                    call("sync.Mutex.Lock/0", var("mu"), 11),
                    call("sync.Mutex.Unlock/0", var("mu"), 12),
                    returnInstruction())));

    assertThat(findings).as("the lock was taken first").isEmpty();
  }

  @Test
  @DisplayName("a Go lock and unlock through one field are the same lock")
  void aGoLockAndUnlockThroughOneFieldAreTheSameLock() {
    List<Finding> findings =
        unlockFindings(
            goFunction(
                "counter",
                "example.com/counter",
                List.of(
                    fieldLoad("t1", "c", "mu"),
                    call("sync.Mutex.Lock/0", var("t1"), 11),
                    fieldLoad("t3", "c", "mu"),
                    call("sync.Mutex.Unlock/0", var("t3"), 12),
                    returnInstruction())));

    assertThat(findings)
        .as("the two reads of c.mu are the same lock, so the unlock has a lock")
        .isEmpty();
  }

  @Test
  @DisplayName("a Go unlock through a field with no lock is reported")
  void aGoUnlockThroughAFieldWithNoLockIsReported() {
    List<Finding> findings =
        unlockFindings(
            goFunction(
                "counter",
                "example.com/counterbad",
                List.of(
                    fieldLoad("t1", "c", "mu"),
                    call("sync.Mutex.Unlock/0", var("t1"), 11),
                    returnInstruction())));

    assertThat(findings).hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-832");
  }

  @Test
  @DisplayName("a Java unlock without a lock is still reported")
  void aJavaUnlockWithoutALockIsStillReported() {
    List<Finding> findings =
        unlockFindings(
            javaFunction(
                "release",
                List.of(
                    call("java.util.concurrent.locks.ReentrantLock.unlock/0", var("lock"), 11),
                    returnInstruction())));

    assertThat(findings).as("Java's lower case spelling still fires").hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-832");
    assertThat(findings.get(0).message())
        .as("the text names Java's failure")
        .contains("IllegalMonitorStateException");
  }

  @Test
  @DisplayName("a Java lock and unlock are the same lock")
  void aJavaLockAndUnlockAreTheSameLock() {
    List<Finding> findings =
        unlockFindings(
            javaFunction(
                "use",
                List.of(
                    call("java.util.concurrent.locks.ReentrantLock.lock/0", var("lock"), 11),
                    call("java.util.concurrent.locks.ReentrantLock.unlock/0", var("lock"), 12),
                    returnInstruction())));

    assertThat(findings).as("the lock was taken first").isEmpty();
  }

  @Test
  @DisplayName("the UPPER case spelling is Go's alone")
  void theUpperCaseSpellingIsGoAlone() {
    List<Finding> findings =
        unlockFindings(
            javaFunction(
                "release",
                List.of(call("sync.Mutex.Unlock/0", var("mu"), 11), returnInstruction())));

    assertThat(findings)
        .as("Java has no upper-case lock method, so this is not a Java unlock")
        .isEmpty();
  }

  // ---------------------------------------------------------------- CWE-833

  @Test
  @DisplayName("two Go functions that lock in opposite orders are reported")
  void twoGoFunctionsThatLockInOppositeOrdersAreReported() {
    List<Finding> findings =
        deadlockFindings(
            goFunction(
                "first",
                "example.com/abba",
                List.of(
                    call("sync.Mutex.Lock/0", var("mu"), 11),
                    call("sync.Mutex.Lock/0", var("other"), 12),
                    returnInstruction())),
            goFunction(
                "second",
                "example.com/abba",
                List.of(
                    call("sync.Mutex.Lock/0", var("other"), 21),
                    call("sync.Mutex.Lock/0", var("mu"), 22),
                    returnInstruction())));

    assertThat(findings).as("the two orders are opposite, which is the AB-BA shape").hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-833");
  }

  @Test
  @DisplayName("two Go functions that lock in the same order are not reported")
  void twoGoFunctionsThatLockInTheSameOrderAreNotReported() {
    List<Finding> findings =
        deadlockFindings(
            goFunction(
                "first",
                "example.com/same",
                List.of(
                    call("sync.Mutex.Lock/0", var("mu"), 11),
                    call("sync.Mutex.Lock/0", var("other"), 12),
                    returnInstruction())),
            goFunction(
                "second",
                "example.com/same",
                List.of(
                    call("sync.Mutex.Lock/0", var("mu"), 21),
                    call("sync.Mutex.Lock/0", var("other"), 22),
                    returnInstruction())));

    assertThat(findings).as("the same order cannot deadlock against itself").isEmpty();
  }

  @Test
  @DisplayName("two Java functions that lock in opposite orders are still reported")
  void twoJavaFunctionsThatLockInOppositeOrdersAreStillReported() {
    List<Finding> findings =
        deadlockFindings(
            javaFunction(
                "first",
                List.of(
                    call("java.util.concurrent.locks.ReentrantLock.lock/0", var("lock1"), 11),
                    call("java.util.concurrent.locks.ReentrantLock.lock/0", var("lock2"), 12),
                    returnInstruction())),
            javaFunction(
                "second",
                List.of(
                    call("java.util.concurrent.locks.ReentrantLock.lock/0", var("lock2"), 21),
                    call("java.util.concurrent.locks.ReentrantLock.lock/0", var("lock1"), 22),
                    returnInstruction())));

    assertThat(findings).as("Java's behaviour is unchanged").hasSize(1);
    assertThat(findings.get(0).cwe()).isEqualTo("CWE-833");
  }

  // ------------------------------------------------------------- the harness

  /** Runs the unlock checker over the given functions and returns its findings. */
  private List<Finding> unlockFindings(TacFunction... functions) {
    return findings(new UnlockNotLockedChecker(), UNLOCK_RULE, functions);
  }

  /** Runs the deadlock checker over the given functions and returns its findings. */
  private List<Finding> deadlockFindings(TacFunction... functions) {
    return findings(new DeadlockChecker(), DEADLOCK_RULE, functions);
  }

  /** Analyses the functions with one checker and returns the findings of one rule. */
  private List<Finding> findings(DfaChecker checker, String ruleId, TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("Locks.java"), "Locks");
    for (TacFunction function : functions) {
      program.addFunction(function);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(checker), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (ruleId.equals(f.ruleId())) {
        out.add(f);
      }
    }
    return out;
  }

  /** A Go function: the language marker is what makes the UPPER case spelling count. */
  private static TacFunction goFunction(
      String name, String enclosing, List<TacInstruction> instructions) {
    return new TacFunction(
        name,
        name + "()",
        null,
        List.of(),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        enclosing,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.GO,
        IrPrecision.SOURCE_FULL);
  }

  /**
   * A Java function.
   *
   * <p>The enclosing scope is set on purpose: the deadlock checker only compares two functions that
   * live in the SAME scope, so a function with no enclosing scope would silently never be compared
   * with anything.
   */
  private static TacFunction javaFunction(String name, List<TacInstruction> instructions) {
    return new TacFunction(
        name,
        name + "()",
        null,
        List.of(),
        List.of(),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        "example.com/java",
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.JAVA,
        IrPrecision.SOURCE_FULL);
  }

  private static TacOperand.TacVar var(String name) {
    return new TacOperand.TacVar(name, null, false);
  }

  /** A call on a receiver. The receiver is the first argument, per the TAC convention. */
  private static TacInstruction call(String calleeSig, TacOperand receiver, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .dest(new TacOperand.TacVar("t_t" + line, null, true))
        .calleeSig(calleeSig)
        .args(List.of(receiver))
        .line(line)
        .build();
  }

  /** A field read: {@code base.field} into a temporary. */
  private static TacInstruction fieldLoad(String dest, String base, String field) {
    return TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
        .dest(var(dest))
        .lhs1(var(base))
        .fieldName(field)
        .build();
  }

  private static TacInstruction returnInstruction() {
    return TacInstruction.builder(TacInstruction.Kind.RETURN).build();
  }
}
