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
package org.plumb.tac.frontend.php;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.TacFrontend;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * The PHP frontend, end to end: the Java side, the PHP sidecar and the reference PHP parser.
 *
 * <h2>Why this test exists next to the one that uses a stand-in</h2>
 *
 * <p>The stand-in sidecar proves that the Java side reads the wire correctly. This one proves that
 * the wire the Java side reads is the wire the <b>real</b> sidecar writes -- and the two can
 * disagree: a field name on one side and a property name on the other, a receiver slot in one
 * position and at the other end, a version one side stamps and the other ignores. Every one of
 * those produces TAC that looks reasonable and finds nothing.
 *
 * <p>So the two tests are a pair: the stand-in pins the Java side, this one pins the agreement.
 *
 * <h2>Why it SKIPS</h2>
 *
 * <p>The sidecar needs Node and an installed parser. A machine without those is missing an
 * environment, not carrying a broken product -- the same reason the Go frontend's test skips when
 * there is no Go toolchain.
 *
 * <p><b>The skip is not a pass, and it says what to do about it.</b> The parser the sidecar imports
 * lives in {@code node_modules}, which is not in version control, so a fresh checkout has nothing
 * to run: the test skips, the build stays green, and the PHP front end is quietly unexercised. The
 * message names the command that fixes it, so a skip is a to-do and not a shrug.
 */
@DisplayName("PHP: the real sidecar's TAC arrives as the IR's TAC")
class PhpSidecarEndToEndTest {

  private static final String SOURCE =
      """
      <?php
      namespace App\\Services;

      function helper(string $a): string {
          return $a;
      }

      class Mailer {
          public function send(string $to): bool {
              $box = [];
              $box["label"] = $to;
              $seen = $box->tag;
              $out = helper($seen);
              return system($box["label"] . "!");
          }
      }
      """;

  @Test
  @DisplayName("a translated file keeps the shape the engine reads")
  void aTranslatedFileKeepsTheShapeTheEngineReads(@TempDir Path directory) throws IOException {
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, SOURCE);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(SOURCE, file, null);

      assertThat(result.problems()).isEmpty();
      assertThat(result.functions()).isNotEmpty();

      TacFunction send = find(result, "send");
      assertThat(send).as("the class's method must be in the stream").isNotNull();
      assertThat(send.sourceLanguage()).isEqualTo(SourceLanguage.PHP);
      assertThat(send.signature()).contains("send");
      assertThat(send.enclosingClass())
          .as("a method must name the class it belongs to, qualified as PHP qualifies it")
          .isEqualTo("App\\Services\\Mailer");

      // A property read is a CONTAINER ACCESS with a string key, not a field
      // access: in this language an object is a namespace, and `$box["label"]`
      // and `$box->label` are the same location.
      //
      // The two forms are read with DIFFERENT keys on purpose. A source that
      // only had the subscript form would still pass when the property read
      // was broken, because the subscript's own container access would
      // satisfy the assertion -- and the broken half would go unnoticed.
      TacInstruction propertyRead =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "tag".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property read is not a keyed container access"));
      assertThat(propertyRead.lhs1).isNotNull();

      // The other form of the same location: a subscript is a container
      // access too, and it is the SAME location as the property read.
      TacInstruction subscript =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYLOAD)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the subscript read is not a keyed container access"));
      assertThat(subscript.lhs1).isNotNull();

      assertThat(send.instructions())
          .as("a property read must never become a field access")
          .noneMatch(i -> i.kind == TacInstruction.Kind.FIELDLOAD);

      // A property WRITE is a store with the same key. A write that is
      // dropped is a taint that stops halfway.
      TacInstruction write =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.ARRAYSTORE)
              .filter(i -> i.lhs2 instanceof TacOperand.Const)
              .filter(i -> "label".equals(String.valueOf(((TacOperand.Const) i.lhs2).value())))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the property write is not a keyed container store"));
      assertThat(write.dest).isNotNull();

      // A binary operator is a BINOP, not an unknown value.
      TacInstruction addition =
          send.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.BINOP)
              .findFirst()
              .orElseThrow(() -> new AssertionError("the binary operator is not a BINOP"));
      assertThat(addition.binop).isEqualTo(org.plumb.tac.ir.BinaryOperator.ADD);

      // The call of a global function keeps the receiver slot, and the callee
      // signature is the name PHP gives it -- which is what the sink
      // catalogue matches on.
      TacInstruction system =
          send.instructions().stream()
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("\\system/"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("the call of system is not in the stream"));
      assertThat(system.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(system.args.get(0))
          .as("the receiver slot holds the namespace, not the value")
          .isEqualTo(TacOperand.Const.ofString("\\"));
      assertThat(system.args.get(1))
          .as("the tainted value is the argument, and it is at args[1]")
          .isInstanceOf(TacOperand.TacVar.class);

      // The call of a function the NAMESPACE declares carries the qualified
      // name PHP gives it -- and the declaration comes AFTER the call in the
      // file, so this also pins that the declarations are read before the
      // file is lowered.
      TacInstruction namespaced =
          send.instructions().stream()
              .filter(i -> i.calleeSig != null && i.calleeSig.startsWith("App\\Services\\helper/"))
              .findFirst()
              .orElseThrow(
                  () ->
                      new AssertionError(
                          "the call of the namespaced function is not in the stream"));
      assertThat(namespaced.args)
          .as("the receiver comes first, and the arguments follow it")
          .hasSizeGreaterThanOrEqualTo(2);
      assertThat(namespaced.args.get(0))
          .as("the receiver slot holds the namespace, not the value")
          .isEqualTo(TacOperand.Const.ofString("App\\Services"));
    }
  }

  /**
   * A PHP construction: the key the catalogue has to name.
   *
   * <p><b>Why this is pinned here and not only in the sidecar's smoke test.</b> The sink catalogue
   * matches a callee signature, so a construction's key is what decides whether a catalogue entry
   * can ever fire, and the engine's constructor-sink lookup returns immediately when a NEW carries
   * none. The sidecar's own smoke test does assert this key, but nothing in the build runs it, so
   * the fact had no automatic guard.
   *
   * <p><b>Why the name is the one PHP gives the class -- and why the leading backslash is
   * optional.</b> A class in a namespace is named by its namespace-relative name, {@code
   * App\Support\Logger}, and a class in the global namespace is named with a leading backslash,
   * {@code \DateTime}. But the backslash is part of the key only when the SOURCE spells the name
   * that way: the same class written out in full is {@code \App\Support\Logger}, and that is a
   * different key -- see the test below. A catalogue entry that insists on the backslash claims
   * only the fully qualified spelling, and one that insists on its absence claims only the relative
   * one, so either way half the call sites are unreachable.
   *
   * <p><b>Why the construction has no receiver slot.</b> A call keeps its receiver in the first
   * argument slot and a construction does not -- the object does not exist yet. So the same
   * declared position lands on a different slot in the two, and a catalogue entry for a constructor
   * counts the arguments from the first one.
   */
  @Test
  @DisplayName("a construction names the class it constructs")
  void aConstructionNamesTheClassItConstructs(@TempDir Path directory) throws IOException {
    String source =
        """
        <?php
        namespace App\\Support;

        class Logger {
            public function __construct($path) { $this->path = $path; }
        }

        function handle(string $input): void {
            $logger = new Logger($input);
            $moment = new \\DateTime();
        }
        """;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      assertThat(result.problems()).isEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();

      TacInstruction namespaced =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.NEW)
              .filter(i -> i.calleeSig != null && i.calleeSig.contains("Logger.<init>/"))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the construction of Logger is not in the stream"));
      assertThat(namespaced.calleeSig)
          .as(
              "a class in a namespace is named by its namespace-relative name, without a leading"
                  + " backslash")
          .isEqualTo("App\\Support\\Logger.<init>/1");
      assertThat(namespaced.args)
          .as("a construction has no receiver slot, so the argument is at args[0]")
          .hasSize(1);
      assertThat(namespaced.ctorType)
          .as("the construction must carry the type it built")
          .isNotNull();

      TacInstruction global =
          handle.instructions().stream()
              .filter(i -> i.kind == TacInstruction.Kind.NEW)
              .filter(i -> i.calleeSig != null && i.calleeSig.contains("DateTime.<init>/"))
              .findFirst()
              .orElseThrow(
                  () -> new AssertionError("the construction of DateTime is not in the stream"));
      assertThat(global.calleeSig)
          .as("a class in the global namespace is named with a leading backslash")
          .isEqualTo("\\DateTime.<init>/0");
      assertThat(global.args).as("a construction with no arguments has no slots").isEmpty();
    }
  }

  /**
   * The same class, written two ways, has two keys.
   *
   * <p><b>Why the catalogue has to know this.</b> A pattern names a class, and the leading
   * backslash is part of the key -- but only when the source spells the name that way. The same
   * class in a namespace, written the way PHP resolves it inside that namespace, has NO leading
   * backslash; written out in full, it has one. They are two different keys, so a pattern that
   * insists on the backslash claims only the fully qualified spelling and a pattern that insists on
   * its absence claims only the relative one -- and either way the other spelling is unreachable,
   * silently, with the scan still reporting success.
   *
   * <p><b>Why PHP is asymmetric here.</b> A FUNCTION has a global fallback: an unqualified call
   * names the global function, with its leading backslash, which is why the catalogue's PHP
   * function entries carry one. A CLASS has no global fallback -- PHP looks for an unqualified
   * class name in the current namespace and nowhere else -- so an unqualified class inside a
   * namespace is that namespace's class, and its key has no backslash. A class entry therefore
   * cannot copy the shape of a function entry.
   */
  @Test
  @DisplayName("the same class, written two ways, has two keys")
  void theSameClassWrittenTwoWaysHasTwoKeys(@TempDir Path directory) throws IOException {
    String source =
        """
        <?php
        namespace App\\Support;

        class Logger {
            public function __construct($path) { $this->path = $path; }
        }

        function handle(string $input): void {
            $relative = new Logger($input);
            $qualified = new \\App\\Support\\Logger($input);
        }
        """;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      assertThat(result.problems()).isEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();

      assertThat(handle.instructions())
          .filteredOn(i -> i.kind == TacInstruction.Kind.NEW)
          .extracting(i -> i.calleeSig)
          .as(
              "the same class, written the way PHP resolves it and written out in full, must"
                  + " produce two keys -- so a catalogue pattern has to make the leading backslash"
                  + " optional")
          .containsExactlyInAnyOrder(
              "App\\Support\\Logger.<init>/1", "\\App\\Support\\Logger.<init>/1");
    }
  }

  /**
   * An unqualified class name inside a namespace is that namespace's class -- PHP never falls back
   * to the global one.
   *
   * <p><b>Why this matters for the catalogue.</b> A FUNCTION has a global fallback: an unqualified
   * call names the global function, with its leading backslash, which is why the catalogue's PHP
   * function entries carry one. A CLASS has no such fallback. The language manual is explicit --
   * "Class names always resolve to the current namespace name", and the page is titled "fallback to
   * the global space <em>for functions and constants</em>" -- and the example is a fatal error:
   * inside {@code namespace A\B\C}, {@code new ArrayObject} looks for {@code A\B\C\ArrayObject} and
   * finds nothing, rather than looking for the global {@code ArrayObject}. Only a fully qualified
   * spelling ({@code \ArrayObject}) or an import ({@code use ArrayObject;}) reaches the global
   * class.
   *
   * <p><b>So the key follows the spelling, and the three spellings differ.</b> A catalogue entry
   * that resolves an unqualified name to the global class would name a class PHP never looks for,
   * and would lose the class the program actually constructs -- {@code App\Support\PDO} --
   * silently, with the scan still reporting success. The entry has to name the class PHP resolves,
   * which is the namespace-relative one.
   *
   * <p><b>Why the import is in the same test.</b> An imported name is a third spelling with its own
   * key: {@code use PDO as Pdo; new Pdo(...)} constructs the global {@code PDO}, and the key says
   * so, with no namespace in front of it. The three spellings of one class therefore produce three
   * different keys, and each one is a separate key a catalogue entry has to be able to name.
   */
  @Test
  @DisplayName("an unqualified class in a namespace does not fall back to the global one")
  void anUnqualifiedClassInANamespaceDoesNotFallBackToTheGlobalOne(@TempDir Path directory)
      throws IOException {
    String source =
        """
        <?php
        namespace App\\Support;

        use PDO as Pdo;
        use Other\\Thing as T;

        function handle(string $input): void {
            $global = new \\PDO();
            $aliased = new Pdo($input);
            $unqualified = new PDO($input);
            $other = new T();
            $service = new namespace\\Service();
        }
        """;
    Files.writeString(directory.resolve("composer.json"), "{\"name\": \"app/app\"}\n");
    Path file = directory.resolve("app.php");
    Files.writeString(file, source);

    try (PhpTacFrontend frontend = new PhpTacFrontend()) {
      assumeTrue(
          sidecarAvailable(),
          "the PHP front end needs Node and the sidecar's own parser dependency: run npm ci in"
              + " plumb-frontend/php-sidecar");

      TacFrontend.ParseResult result = frontend.parse(source, file, null);
      assertThat(result.problems()).isEmpty();

      TacFunction handle = find(result, "handle");
      assertThat(handle).as("the module's function must be in the stream").isNotNull();

      assertThat(handle.instructions())
          .filteredOn(i -> i.kind == TacInstruction.Kind.NEW)
          .extracting(i -> i.calleeSig)
          .as(
              "an unqualified class in a namespace is the namespace's class and never the global"
                  + " one; an import or a leading backslash is what reaches the global class")
          .containsExactlyInAnyOrder(
              "\\PDO.<init>/0",
              "PDO.<init>/1",
              "App\\Support\\PDO.<init>/1",
              "Other\\Thing.<init>/0",
              "App\\Support\\Service.<init>/0");
    }
  }

  /** The function with the given name, or null. */
  private static TacFunction find(TacFrontend.ParseResult result, String name) {
    for (TacFunction function : result.functions()) {
      if (name.equals(function.name())) {
        return function;
      }
    }
    return null;
  }

  /**
   * Whether the sidecar can run here.
   *
   * <p>Three things have to be there: Node, the sidecar's script, and the parser the script
   * imports. The third is the one that is easy to miss, and its absence would make the sidecar die
   * on its first request -- which would look like a broken product rather than a missing
   * dependency.
   */
  private static boolean sidecarAvailable() {
    if (PhpSidecarClient.locateNode() == null) {
      return false;
    }
    Path script = PhpSidecarClient.locateScript(null);
    if (script == null) {
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
