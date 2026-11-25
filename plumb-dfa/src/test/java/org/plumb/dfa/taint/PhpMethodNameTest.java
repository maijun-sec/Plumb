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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.frontend.php.PhpSidecarClient;
import org.plumb.tac.frontend.php.PhpTacFrontend;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * A PHP method's name is written after a double colon, and the engine has to read it.
 *
 * <p><b>Why this needed its own test.</b> A PHP METHOD is written {@code owner::member}, and the
 * side car emits the callee key that way: a static call arrives as {@code \PDO::query/1} and an
 * instance one as {@code \Runner::getIterator/0}. The engine's method-name reader used to split a
 * key on the last dot or the last backslash only, so it read {@code \PDO::query/1} as a method
 * named {@code PDO::query} -- a name that is in no vocabulary, so no method-name list ever matched
 * a PHP method call, and the taint stopped there.
 *
 * <p><b>Why the SEMANTICS table is not in this test.</b> The semantics table asks a DIFFERENT
 * question: "is this the global function {@code trim}?". Its reader must NOT split on the double
 * colon, so a method that happens to be called {@code trim} does not inherit the global function's
 * semantics. That is the other half of the change, and it is pinned in {@code
 * LanguageSemanticsTest}, because it is a claim about the table and not about the taint engine.
 *
 * <p><b>Why the controls are here.</b> The engine's method-name reader is shared by every language.
 * A change to it that fixed PHP and broke Java, Python or TypeScript would pass a PHP-only test, so
 * the other spellings are pinned here too.
 *
 * <p><b>Why the clean case is here.</b> A propagation that treats every container as tainted passes
 * the other tests and is useless. So the control -- a container whose element is clean -- is
 * asserted to stay clean. If it ever goes red, the tracker has become "everything is tainted", and
 * the other tests prove nothing.
 */
@DisplayName("Taint: a PHP method's name is read out of the double colon")
class PhpMethodNameTest {

  /** The sink: a shared family, so it applies to every language. */
  private static final String COMMAND_RULE = "dfa-taint-command-injection";

  /**
   * A PHP program whose iterator hands out the container's tainted element.
   *
   * <p><b>Why the methods are DECLARED.</b> The engine's iterator propagation runs on the calls the
   * front end could resolve. A method the file declares is such a call; a method of a class the
   * program never mentions is a black box, and a black box is modelled conservatively instead of
   * through the protocol.
   *
   * <p><b>Why the calls are CHAINED.</b> {@code $runner->getIterator()->current()} keeps the
   * iterator in the call's own temporary, so the element is taken out of the very object the
   * factory made. A program that stored the iterator in a variable first would put a MOVE between
   * the two calls, and the MOVE is not part of what this test is about.
   */
  private static final String PHP_ITERATOR_SOURCE =
      """
      <?php
      class Runner {
          public string $held = "";

          public function getIterator(): Traversable {
              return $this;
          }

          public function current(): mixed {
              return $this->held;
          }

          public function run(): void {
              $runner = new Runner();
              $runner->held = $_GET['cmd'];
              $cmd = $runner->getIterator()->current();
              system($cmd);
          }
      }
      """;

  /**
   * The same program with nothing tainted in it.
   *
   * <p>The control for the end-to-end test: the value the iterator hands out was chosen by the
   * program itself, so it is not a finding.
   */
  private static final String PHP_ITERATOR_CLEAN_SOURCE =
      """
      <?php
      class Runner {
          public string $held = "";

          public function getIterator(): Traversable {
              return $this;
          }

          public function current(): mixed {
              return $this->held;
          }

          public function run(): void {
              $runner = new Runner();
              $runner->held = "safe";
              $cmd = $runner->getIterator()->current();
              system($cmd);
          }
      }
      """;

  @Test
  @DisplayName("the taint of a container crosses a PHP method's iteration protocol")
  void theTaintOfAContainerCrossesAPhpMethodsIterationProtocol() {
    List<Finding> findings = scan(phpOverAContainerWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as(
            "the container is tainted, the iterator is made by getIterator() and the element is"
                + " taken out by current() -- both of them PHP METHODS, so their names are read"
                + " out of the double colon")
        .isTrue();
  }

  /**
   * The reader reads the METHOD's name out of a PHP key.
   *
   * <p>This is the positive claim, stated on the reader itself rather than on what the engine does
   * with the name: a PHP method is written {@code owner::member}, and the name the reader hands to
   * the method-name lists is the member.
   */
  @Test
  @DisplayName("a PHP method's name is the member behind the double colon")
  void aPhpMethodsNameIsTheMemberBehindTheDoubleColon() {
    assertThat(TaintedCheckerBase.methodNameOf("\\PDO::query/1")).isEqualTo("query");
    assertThat(TaintedCheckerBase.methodNameOf("\\PDO::trim/1")).isEqualTo("trim");
    assertThat(TaintedCheckerBase.methodNameOf("\\Iterator::current/0")).isEqualTo("current");
    assertThat(TaintedCheckerBase.methodNameOf("\\ArrayObject::getIterator/0"))
        .isEqualTo("getIterator");
    // The global functions are unchanged: a leading backslash is the namespace
    // qualifier, and the name still stands after it.
    assertThat(TaintedCheckerBase.methodNameOf("\\trim/1")).isEqualTo("trim");
    assertThat(TaintedCheckerBase.methodNameOf("\\App\\Support\\sanitize/1")).isEqualTo("sanitize");
  }

  /**
   * The other languages' names are read exactly as they were.
   *
   * <p><b>Why this is pinned.</b> The reader is shared by every language, and the double colon is a
   * separator in ONE of them. A change that fixed PHP and moved a JVM, Python, TypeScript, C# or
   * Scala name would pass a PHP-only test, so every other spelling is pinned here.
   *
   * <p><b>Why Scala is in the list.</b> Scala spells its collection operators {@code owner.::}, and
   * there the double colon IS the method's name. A reader that split on every double colon would
   * read that name as nothing at all.
   */
  @Test
  @DisplayName("the other languages' method names are read exactly as they were")
  void theOtherLanguagesMethodNamesAreReadExactlyAsTheyWere() {
    // The JVM.
    assertThat(TaintedCheckerBase.methodNameOf("java.lang.String.trim/1")).isEqualTo("trim");
    assertThat(TaintedCheckerBase.methodNameOf("java.util.Iterator.next/0")).isEqualTo("next");
    // Python.
    assertThat(TaintedCheckerBase.methodNameOf("str.strip/0")).isEqualTo("strip");
    assertThat(TaintedCheckerBase.methodNameOf("<unknown>.__iter__/0")).isEqualTo("__iter__");
    assertThat(TaintedCheckerBase.methodNameOf("<unknown>.__next__/0")).isEqualTo("__next__");
    // TypeScript. A COMPUTED property name is read the way it always was -- the
    // bracket is still on the name, and the protocol's own reader unpacks it.
    assertThat(TaintedCheckerBase.methodNameOf("Iterator[Symbol.iterator]/1"))
        .isEqualTo("iterator]");
    // C#.
    assertThat(TaintedCheckerBase.methodNameOf("System.String.Trim/0")).isEqualTo("Trim");
    assertThat(TaintedCheckerBase.methodNameOf("System.Collections.IEnumerator.get_Current/0"))
        .isEqualTo("get_Current");
    // Scala. The double colon IS the method's name there.
    assertThat(TaintedCheckerBase.methodNameOf("scala.collection.List.::/1")).isEqualTo("::");
    assertThat(TaintedCheckerBase.methodNameOf("scala.collection.List.:::/1")).isEqualTo(":::");
  }

  @Test
  @DisplayName("the Java spelling of the protocol still crosses the taint")
  void theJavaSpellingOfTheProtocolStillCrossesTheTaint() {
    List<Finding> findings = scan(javaOverAnArrayWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "java.lang.Runtime.exec/1"))
        .as(
            "arr[0] = tainted; it = arr.iterator(); s = it.next() -- the engine's method-name"
                + " reader is shared, so the dot form must keep working")
        .isTrue();
  }

  @Test
  @DisplayName("the Python spelling of the protocol still crosses the taint")
  void thePythonSpellingOfTheProtocolStillCrossesTheTaint() {
    List<Finding> findings = scan(pythonOverAContainerWithATaintedElement());

    assertThat(hasFinding(findings, COMMAND_RULE, "os.system/1"))
        .as(
            "items = [cmd]; for x in items: os.system(x) -- Python spells the protocol with"
                + " double underscores, and the reader must still see them")
        .isTrue();
  }

  @Test
  @DisplayName("a clean container hands out clean values")
  void aCleanContainerHandsOutCleanValues() {
    List<Finding> findings = scan(phpOverAContainerWithACleanElement());

    assertThat(findings)
        .as(
            "the container was filled by the program itself, so the command it builds is not a"
                + " finding")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * {@code $cmds = $_GET['cmds']; $it = $cmds->getIterator(); $cmd = $it->current(); system($cmd);}
   *
   * <p>The container is a value read out of the request, so it is tainted itself. The element the
   * iterator hands out is the container's taint, which is what the protocol crosses.
   */
  private static TacFunction phpOverAContainerWithATaintedElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("_GET");
    TacOperand.TacVar container = var("cmds");
    TacOperand.TacVar cmd = var("cmd");

    TacOperand.TacVar value = temp("t_t1");
    out.add(arrayLoad(value, request, str("cmds"), 10));
    out.add(move(container, value, 10));
    TacOperand.TacVar iterator = temp("t_it");
    out.add(call("\\ArrayObject::getIterator/0", iterator, List.of(container), 11));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("\\Runner::current/0", element, List.of(iterator), 12));
    out.add(move(cmd, element, 12));
    out.add(command("\\system/1", cmd, 13));
    return phpFunction(out, request, container, cmd);
  }

  /** {@code $cmds = "safe"; ... system($cmd);} -- nothing came from outside the program. */
  private static TacFunction phpOverAContainerWithACleanElement() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar request = var("_GET");
    TacOperand.TacVar container = var("cmds");
    TacOperand.TacVar cmd = var("cmd");

    out.add(move(container, str("safe"), 10));
    TacOperand.TacVar iterator = temp("t_it");
    out.add(call("\\ArrayObject::getIterator/0", iterator, List.of(container), 11));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("\\Runner::current/0", element, List.of(iterator), 12));
    out.add(move(cmd, element, 12));
    out.add(command("\\system/1", cmd, 13));
    return phpFunction(out, request, container, cmd);
  }

  /**
   * {@code arr[0] = tainted; it = arr.iterator(); s = it.next(); Runtime.exec(s)}
   *
   * <p>The Java spelling of the protocol, written out by hand so the control does not depend on the
   * PHP front end.
   */
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
   * {@code cmd = request["cmd"]; items = [cmd]; for x in items: os.system(x)}
   *
   * <p>The Python spelling of the protocol, written out by hand.
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

    TacOperand.TacVar iterator = temp("t_iterator");
    out.add(call("<unknown>.__iter__/0", iterator, List.of(items), 12));
    TacOperand.TacVar element = temp("t_element");
    out.add(call("<unknown>.__next__/0", element, List.of(iterator), 12));
    out.add(move(x, element, 12));
    out.add(command("os.system/1", x, 13));
    return function(out, SourceLanguage.PYTHON3, List.of(request), List.of(items, x));
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static TacFunction phpFunction(List<TacInstruction> out, TacOperand.TacVar... locals) {
    List<TacOperand.TacVar> all = new ArrayList<>();
    for (TacOperand.TacVar v : locals) {
      all.add(v);
    }
    // The superglobal is a LOCAL, not a parameter: a PHP superglobal is a trust-boundary
    // entry point, and the engine seeds the source variables by name beside the parameters.
    return function(out, SourceLanguage.PHP, List.of(), all, "app\\handler():void");
  }

  private static TacFunction function(
      List<TacInstruction> out,
      SourceLanguage language,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals) {
    return function(out, language, params, locals, "app\\handler():void");
  }

  private static TacFunction function(
      List<TacInstruction> out,
      SourceLanguage language,
      List<TacOperand.TacVar> params,
      List<TacOperand.TacVar> locals,
      String signature) {
    return new TacFunction(
        "handle",
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
    TacProgram program = new TacProgram(Path.of("app.php"), "app");
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
    if (callee.startsWith("os.")) return "os";
    if (callee.startsWith("\\system")) return "\\";
    return "runtime";
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

  // ---- the real front end
  // ------------------------------------------------------------------------

  /**
   * Run the source through the front end, then through the engine.
   *
   * @param source the source text
   * @param file the file the source came from
   * @return the findings the engine reported
   */
  private static List<Finding> scan(String source, Path file) {
    TacFrontend.ParseResult result = new PhpTacFrontend().parse(source, file, null);
    assertThat(result.problems()).as("the source must lower without a problem").isEmpty();

    TacProgram program = new TacProgram(file, "app");
    for (TacFunction function : result.functions()) {
      program.addFunction(function);
    }

    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new CommandInjectionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> findings = new ArrayList<>();
    for (Finding finding : reporter.all()) {
      if (finding.ruleId().startsWith("dfa-taint-")) {
        findings.add(finding);
      }
    }
    return findings;
  }

  @Test
  @DisplayName("the real PHP front end's iterator hands out the container's tainted element")
  void theRealPhpFrontEndsIteratorHandsOutTheContainersTaintedElement(@TempDir Path directory)
      throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_ITERATOR_SOURCE);

    List<Finding> findings = scan(PHP_ITERATOR_SOURCE, file);

    assertThat(hasFinding(findings, COMMAND_RULE, "\\system/1"))
        .as(
            "the request's value is held by the object, the iterator is made by getIterator() and"
                + " the element is taken out by current() -- the element must reach the shell")
        .isTrue();
  }

  @Test
  @DisplayName("the real PHP front end's iterator hands out nothing when the program chose it")
  void theRealPhpFrontEndsIteratorHandsOutNothingWhenTheProgramChoseIt(@TempDir Path directory)
      throws IOException {
    assumeTrue(phpAvailable(), "the PHP front end needs Node and its parser dependency");

    Path file = directory.resolve("app.php");
    Files.writeString(file, PHP_ITERATOR_CLEAN_SOURCE);

    List<Finding> findings = scan(PHP_ITERATOR_CLEAN_SOURCE, file);

    assertThat(findings)
        .as("the program chose the value itself, so the command it builds is not a finding")
        .isEmpty();
  }

  /**
   * Whether the PHP front end can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the package the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean phpAvailable() {
    Path node = PhpSidecarClient.locateNode();
    Path script = PhpSidecarClient.locateScript(null);
    if (node == null || script == null) {
      return false;
    }
    Path project = script.getParent();
    if (project == null) {
      return false;
    }
    Path root = project.getParent();
    return root != null && Files.isDirectory(root.resolve("node_modules").resolve("php-parser"));
  }
}
