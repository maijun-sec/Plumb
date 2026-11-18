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
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
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
 * A Python iterator hands out the container's tainted elements.
 *
 * <p><b>Why this needed its own test.</b> A Python {@code for} loop is lowered through the
 * iteration protocol -- {@code __iter__} and {@code __next__} -- and the element is the RESULT of
 * {@code __next__}. The engine's iterator propagation knew the protocol's Java and C# spelling
 * ({@code iterator} / {@code next}) and not Python's, so the element came out of the loop clean and
 * a value that arrived from outside the program never reached the sink.
 *
 * <p><b>Why the instructions are written out by hand.</b> The point of these tests is the ENGINE's
 * answer, not the engine's input shape. The TAC below is the one the Python side car produces for
 * the corresponding Python source, read back out of the side car's own output.
 *
 * <p><b>Why the Java control is here.</b> The engine's iterator propagation is shared by every
 * language. A change to it that fixed Python and broke Java would pass a Python-only test, so the
 * control pins the other spelling too.
 *
 * <p><b>Why the clean case is here.</b> A propagation that treats every container as tainted passes
 * the first three tests and is useless. So the control -- a container whose element is clean -- is
 * asserted to stay clean. If it ever goes red, the tracker has become "everything is tainted", and
 * the other three tests prove nothing.
 */
@DisplayName("Taint: a Python iterator hands out the container's tainted elements")
class PythonIteratorTaintTest {

  /** The sink: a shared family, so it applies to every language. */
  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  @Test
  @DisplayName("the taint of an element crosses the Python iteration protocol")
  void theTaintOfAnElementCrossesThePythonIterationProtocol() {
    List<Finding> findings = scan(pythonOverAContainerWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as(
            "items = [cmd]; for x in items: os.system(x) -- the element is the result of"
                + " __next__, so the taint has to survive the protocol")
        .isTrue();
  }

  @Test
  @DisplayName("a container that is itself tainted hands out tainted values")
  void aContainerThatIsItselfTaintedHandsOutTaintedValues() {
    List<Finding> findings = scan(pythonOverATaintedContainer());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as(
            "cmds = request[\"cmds\"]; for x in cmds: os.system(x) -- the container came from"
                + " outside the program, so everything inside it did too")
        .isTrue();
  }

  @Test
  @DisplayName("the Java spelling of the protocol still crosses the taint")
  void theJavaSpellingOfTheProtocolStillCrossesTheTaint() {
    List<Finding> findings = scan(javaOverAnArrayWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "java.lang.Runtime.exec/1"))
        .as(
            "arr[0] = tainted; it = arr.iterator(); s = it.next() -- the engine's iterator"
                + " propagation is shared, so the Java spelling must keep working")
        .isTrue();
  }

  @Test
  @DisplayName("a clean container hands out clean values")
  void aCleanContainerHandsOutCleanValues() {
    List<Finding> findings = scan(pythonOverAContainerWithACleanElement());

    assertThat(findings)
        .as(
            "items = [\"safe\"]; for x in items: os.system(x) -- nothing came from outside the"
                + " program, so the command is not a finding")
        .isEmpty();
  }

  @Test
  @DisplayName("the element written with append crosses the protocol")
  void theElementWrittenWithAppendCrossesTheProtocol() {
    // A Python list is filled by a CALL, not by a subscript write: `append`
    // is the spelling of the same act that Java and C# spell `add`. The
    // catalogue has to know the spelling, or the container never learns that
    // it holds a tainted value and the loop hands out clean values.
    List<Finding> findings = scan(pythonOverAContainerFilledWithAppend());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as(
            "items = []; items.append(cmd); for x in items: os.system(x) -- the value is put into"
                + " the list by a call, so the container has to learn that it holds a tainted"
                + " element")
        .isTrue();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * {@code cmd = request["cmd"]; items = [cmd]; for x in items: os.system(x)}
   *
   * <p>The element of the list is written, so the container holds a tainted entry.
   */
  private static TacFunction pythonOverAContainerWithATaintedElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar items = var("items");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar cmd = temp("t_cmd");
    out.add(arrayLoad(cmd, request, str("cmd"), 10));
    TacOperand.TacVar array = temp("t_array");
    out.add(newArray(array, 11));
    out.add(arrayStore(array, i(0), cmd, 11));
    out.add(move(items, array, 11));

    appendIteration(out, items, x, 12);
    out.add(command("os.system/1", x, 13));
    return pythonFunction(out, request, items, x);
  }

  /** {@code cmd = request["cmd"]; items = ["safe"]; for x in items: os.system(x)} */
  private static TacFunction pythonOverAContainerWithACleanElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar items = var("items");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar cmd = temp("t_cmd");
    out.add(arrayLoad(cmd, request, str("cmd"), 10));
    TacOperand.TacVar array = temp("t_array");
    out.add(newArray(array, 11));
    out.add(arrayStore(array, i(0), str("safe"), 11));
    out.add(move(items, array, 11));

    appendIteration(out, items, x, 12);
    out.add(command("os.system/1", x, 13));
    return pythonFunction(out, request, items, x);
  }

  /**
   * {@code cmds = request["cmds"]; for x in cmds: os.system(x)}
   *
   * <p>The container is a value read out of the request. Nothing was ever written into it, so the
   * only evidence there is the container's own taint.
   */
  private static TacFunction pythonOverATaintedContainer() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar items = var("cmds");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar container = temp("t_container");
    out.add(arrayLoad(container, request, str("cmds"), 10));
    out.add(move(items, container, 10));

    appendIteration(out, items, x, 12);
    out.add(command("os.system/1", x, 13));
    return pythonFunction(out, request, items, x);
  }

  /**
   * The iteration protocol, as the Python side car lowers it.
   *
   * <p>{@code it = items.__iter__(); x = it.__next__();}
   */
  private static void appendIteration(
      List<TacInstruction> out, TacOperand.TacVar iterable, TacOperand.TacVar target, int line) {
    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("<unknown>.__iter__/0", iterator, List.of(iterable), line));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("<unknown>.__next__/0", element, List.of(iterator), line));
    out.add(move(target, element, line));
  }

  /** {@code arr[0] = tainted; it = arr.iterator(); s = it.next(); Runtime.exec(s)} */
  private static TacFunction javaOverAnArrayWithATaintedElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar array = var("arr");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar temporary = temp("t_array");
    out.add(newArray(temporary, 11));
    out.add(arrayStore(temporary, i(0), request, 11));
    out.add(move(array, temporary, 11));

    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("java.util.List.iterator/0", iterator, List.of(array), 12));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("java.util.Iterator.next/0", element, List.of(iterator), 12));
    out.add(move(x, element, 12));
    out.add(command("java.lang.Runtime.exec/1", x, 13));
    return function(out, SourceLanguage.JAVA, List.of(request), List.of(array, x));
  }

  /**
   * {@code cmd = request["cmd"]; items = []; items.append(cmd); for x in items: os.system(x)}
   *
   * <p>The list is empty and the element is put in by a call, which is how a Python list is filled.
   */
  private static TacFunction pythonOverAContainerFilledWithAppend() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("request");
    TacOperand.TacVar items = var("items");
    TacOperand.TacVar x = var("x");

    TacOperand.TacVar cmd = temp("t_cmd");
    out.add(arrayLoad(cmd, request, str("cmd"), 10));
    TacOperand.TacVar array = temp("t_array");
    out.add(newArray(array, 11));
    out.add(move(items, array, 11));

    // The write: `items.append(cmd)`. The owner is the list's own type, which
    // is what the Python side car emits when the list's type is known.
    out.add(call("list.append/1", temp("t_appended"), List.of(items, cmd), 11));

    appendIteration(out, items, x, 12);
    out.add(command("os.system/1", x, 13));
    return pythonFunction("app.py_iterator_append(any):any", out, request, items, x);
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static TacFunction pythonFunction(List<TacInstruction> out, TacOperand.TacVar... locals) {
    return pythonFunction("app.py_iterator_handler(any):any", out, locals);
  }

  private static TacFunction pythonFunction(
      String signature, List<TacInstruction> out, TacOperand.TacVar... locals) {
    List<TacOperand.TacVar> vars = new ArrayList<>();
    for (TacOperand.TacVar v : locals) {
      vars.add(v);
    }
    return function(out, SourceLanguage.PYTHON3, List.of(vars.get(0)), vars, signature);
  }

  private static TacFunction function(
      List<TacInstruction> out,
      SourceLanguage language,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals) {
    return function(out, language, params, locals, "app.py_iterator_handler(any):any");
  }

  private static TacFunction function(
      List<TacInstruction> out,
      SourceLanguage language,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      String signature) {
    return new TacFunction(
        "handle",
        // The signature is part of the OBJECT LABEL the points-to solver
        // hands out ("type@site:context"), and the keyed taint store is
        // keyed by that label. Two programs in the same JVM that allocate
        // the same type at the same site under the same signature therefore
        // share the store's keys -- so a test that reuses a common
        // signature can be broken by a test that ran before it. This one
        // names itself.
        signature,
        TacType.UNKNOWN,
        params,
        locals,
        out,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        language,
        IrPrecision.SOURCE_FULL);
  }

  private static List<Finding> scan(TacFunction function) {
    TacProgram program = new TacProgram(Path.of("app.py"), "app");
    program.addFunction(function);
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
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

  private static TacOperand.Const i(int value) {
    return TacOperand.Const.ofInt(value);
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

  /** The command sink: the receiver slot holds the module, the value is the first argument. */
  private static TacInstruction command(String callee, TacOperand value, int line) {
    return call(callee, temp("t_result"), List.of(str(receiverOf(callee)), value), line);
  }

  /** The receiver a call carries: a module's name for Python, an object for Java. */
  private static String receiverOf(String callee) {
    return callee.startsWith("os.") ? "os" : "runtime";
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction arrayLoad(
      TacOperand dest, TacOperand receiver, TacOperand key, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
        .dest(dest)
        .lhs1(receiver)
        .lhs2(key)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction arrayStore(
      TacOperand receiver, TacOperand key, TacOperand value, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
        .lhs1(receiver)
        .lhs2(key)
        .dest(value)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction newArray(TacOperand dest, int line) {
    return TacInstruction.builder(TacInstruction.Kind.NEWARRAY)
        .dest(dest)
        .ctorType(TacType.arrayOf(TacType.UNKNOWN))
        .line(line)
        .column(1)
        .build();
  }
}
