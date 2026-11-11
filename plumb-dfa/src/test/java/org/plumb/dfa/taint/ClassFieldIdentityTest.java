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
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The class-level (cross-function) field tracker keys its entries on {@code "<class>.<field>"}, so
 * the class it names has to be a class and not a spelling of one.
 *
 * <p><b>The defect this records.</b> The tracker used to take the class from the receiver's NAME on
 * the write side and from the receiver's TYPE on the read side. The two agree only as long as the
 * IR names a class the same way in both places -- and the IR states a class by its SIMPLE name. Two
 * classes with the same simple name in different packages ({@code pkg.a.Helper} and {@code
 * pkg.b.Helper}) are then one key, so a static field tainted in one of them is read as tainted in
 * the other. That is a false positive, and it is a false positive about a field the reader never
 * wrote.
 *
 * <p><b>Why the fixture is synthetic.</b> The IR states the class identity as the receiver's type,
 * and the Java front end states that type with the simple name -- so a real Java program cannot
 * produce the shape this test needs. The fixture therefore builds the IR the front end will produce
 * once it states class identities fully qualified, which is exactly the contract the engine has to
 * honour. The control group in the same test method proves the fixture is not vacuous: the very
 * same shape DOES carry the taint from one class to another read of THAT class.
 *
 * <p><b>What the engine must do.</b> Name the class by the receiver's type whenever the IR states
 * one. A name is a spelling: it is shared by every class that happens to be called {@code Helper}.
 * A type is an identity.
 */
@DisplayName("Taint: the class-level field key names the class, not its spelling")
class ClassFieldIdentityTest {

  /** The sink the tainted value is passed to. */
  private static final String SINK = "java.sql.Statement.executeQuery/1";

  /** The rule the sink belongs to. */
  private static final String RULE = "dfa-taint-sql-injection";

  /**
   * A function that writes a tainted value into the static field of the class it names.
   *
   * <p>The shape is the IR's own: {@code t = this.Helper} binds the class identity off {@code
   * this}, and the field store then goes through that binding. The class identity is the receiver's
   * TYPE -- the FQN of the class -- while the field name of the binding instruction stays the
   * simple name, which is the marker the engine recognises a class reference by.
   */
  private static TacFunction writer(String classFqn) {
    TacOperand.TacVar self = new TacOperand.TacVar("this", TacType.ofClass(classFqn), false);
    TacOperand.TacVar classRef = new TacOperand.TacVar("t_class", TacType.ofClass(classFqn), false);
    TacOperand.TacVar value = new TacOperand.TacVar("u", TacType.STRING, false);
    TacInstruction bindClass =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(classRef)
            .lhs1(self)
            .fieldName("Helper")
            .line(10)
            .build();
    TacInstruction store =
        TacInstruction.builder(TacInstruction.Kind.FIELDSTORE)
            .dest(value)
            .lhs1(classRef)
            .fieldName("config")
            .line(11)
            .build();
    return new TacFunction(
        "write",
        "write(String):void",
        TacType.VOID,
        List.of(value),
        List.of(self, classRef),
        List.of(bindClass, store),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        classFqn);
  }

  /** A function that reads the static field of the class it names and passes it to a sink. */
  private static TacFunction reader(String classFqn) {
    TacOperand.TacVar self = new TacOperand.TacVar("this", TacType.ofClass(classFqn), false);
    TacOperand.TacVar classRef = new TacOperand.TacVar("t_class", TacType.ofClass(classFqn), false);
    TacOperand.TacVar read = new TacOperand.TacVar("t_value", TacType.UNKNOWN, false);
    TacOperand.TacVar stmt =
        new TacOperand.TacVar("st", TacType.ofClass("java.sql.Statement"), false);
    TacInstruction bindClass =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(classRef)
            .lhs1(self)
            .fieldName("Helper")
            .line(20)
            .build();
    TacInstruction load =
        TacInstruction.builder(TacInstruction.Kind.FIELDLOAD)
            .dest(read)
            .lhs1(classRef)
            .fieldName("config")
            .line(21)
            .build();
    TacInstruction sink =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig(SINK)
            .args(List.of(stmt, read))
            .line(22)
            .build();
    return new TacFunction(
        "read",
        "read():String",
        TacType.STRING,
        List.of(),
        List.of(self, classRef, read, stmt),
        List.of(bindClass, load, sink),
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        classFqn);
  }

  /** Runs the taint engine over the given functions and returns the SQL findings. */
  private static List<Finding> scan(TacFunction... functions) {
    TacProgram program = new TacProgram(Path.of("Helper.java"), "Helper");
    for (TacFunction function : functions) {
      program.addFunction(function);
    }
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new SqlInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (RULE.equals(finding.ruleId())) findings.add(finding);
    }
    return findings;
  }

  @Test
  @DisplayName("two classes with the same simple name do not share a static field")
  void twoClassesWithTheSameSimpleNameDoNotShareAStaticField() {
    // The CONTROL: the reader of the class that wrote the field sees the taint. Without
    // this half, the negative half below would also pass on a tracker that carries no
    // taint at all.
    List<Finding> sameClass = scan(writer("pkg.a.Helper"), reader("pkg.a.Helper"));
    assertThat(sameClass)
        .as(
            "the static field pkg.a.Helper.config was written with a tainted value, so the"
                + " reader of that same class must see it -- the control that says the fixture"
                + " carries a taint at all")
        .hasSize(1);

    // The DEFECT: a DIFFERENT class that happens to have the same simple name. Its
    // static field is a different field, and the tainted value was never written to it.
    List<Finding> otherClass = scan(writer("pkg.a.Helper"), reader("pkg.b.Helper"));
    assertThat(otherClass)
        .as(
            "pkg.b.Helper is a different class from pkg.a.Helper even though both are called"
                + " Helper: the tainted static field of one is not the static field of the other")
        .isEmpty();
  }

  @Test
  @DisplayName("a class reads its own static field across functions")
  void aClassReadsItsOwnStaticFieldAcrossFunctions() {
    List<Finding> findings = scan(writer("pkg.a.Helper"), reader("pkg.a.Helper"));

    assertThat(findings)
        .as(
            "the write and the read are in different functions, and the class-level tracker is"
                + " what carries the taint between them")
        .hasSize(1);
  }
}
