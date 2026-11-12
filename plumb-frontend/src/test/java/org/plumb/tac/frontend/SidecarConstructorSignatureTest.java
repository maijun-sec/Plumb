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
package org.plumb.tac.frontend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A construction that cannot be NAMED cannot be matched by any rule.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>The DFA matches a construction the same way it matches a call: it looks the instruction's
 * callee signature up in the sink catalogue, and it skips the instruction outright when the
 * signature is null. A frontend that emits a construction with a type but no signature therefore
 * makes EVERY constructor family structurally unreachable — and the symptom is a scan that succeeds
 * and reports nothing, which reads exactly like "there is no problem here".
 *
 * <p>Nine frontends emit constructions, and each one spells the emission its own way: a builder
 * chain in Java, Kotlin and Scala; a named-argument call in C#, TypeScript, ArkTS and PHP; a dict
 * literal in Python; a struct literal in Go. Nothing in the codebase ties them together, so a
 * frontend that grows a second construction site — or a new frontend altogether — can silently drop
 * the signature. This test is what ties them together.
 *
 * <h2>What it does NOT require</h2>
 *
 * <p>A construction of a type the COMPILER MAKES UP has no constructor a catalogue could name: an
 * anonymous object, an object literal, a map or a slice. Those are exempt, and the exemption is by
 * the member that emits them, so a new synthetic shape has to be classified on purpose.
 *
 * <h2>The bytecode lane</h2>
 *
 * <p>The bytecode front end deliberately names a constructor on the INVOKE rather than on the
 * allocation, and {@code BytecodeConstructorSinkTest} pins that. This test therefore exempts the
 * bytecode lane rather than contradicting it.
 */
class SidecarConstructorSignatureTest {

  /** One frontend's construction sites, and how that language spells them. */
  private record Lane(
      String name,
      String relativePath,
      Pattern newMarker,
      Pattern signatureMarker,
      Set<String> exemptMembers,
      List<String> exemptCtorTypes,
      Set<String> exemptCaseLabels) {}

  private static final List<Lane> LANES =
      List.of(
          new Lane(
              "java",
              "plumb-tac/src/main/java/org/plumb/tac/translate/ExpressionTranslator.java",
              Pattern.compile("Kind\\.NEW\\b"),
              Pattern.compile("\\.calleeSig\\s*\\("),
              Set.of(),
              List.of(),
              Set.of()),
          new Lane(
              "kotlin",
              "plumb-frontend/src/main/java/org/plumb/tac/frontend/kotlin/KotlinTacTranslator.java",
              Pattern.compile("Kind\\.NEW\\b"),
              Pattern.compile("\\.calleeSig\\s*\\("),
              Set.of(),
              List.of(),
              Set.of()),
          new Lane(
              "scala",
              "plumb-frontend/src/main/java/org/plumb/tac/frontend/scala/ScalaTacTranslator.java",
              Pattern.compile("Kind\\.NEW\\b"),
              Pattern.compile("\\.calleeSig\\s*\\("),
              Set.of(),
              List.of(),
              Set.of()),
          new Lane(
              "csharp",
              "plumb-frontend/csharp-sidecar/TacTranslator.cs",
              Pattern.compile("InstructionKind\\.NEW\\b"),
              Pattern.compile("calleeSig\\s*:"),
              Set.of("TranslateAnonymousObject"),
              List.of(),
              Set.of("TypeOfExpressionSyntax")),
          new Lane(
              "typescript",
              "plumb-frontend/ts-sidecar/src/translator.mjs",
              Pattern.compile("kind:\\s*KIND\\.NEW\\b"),
              Pattern.compile("calleeSig\\s*:"),
              Set.of("lowerObjectLiteral"),
              List.of(),
              Set.of()),
          new Lane(
              "arkts",
              "plumb-frontend/arkts-sidecar/src/translator.mjs",
              Pattern.compile("kind:\\s*KIND\\.NEW\\b"),
              Pattern.compile("calleeSig\\s*:"),
              Set.of("lowerObjectLiteral"),
              List.of(),
              Set.of()),
          new Lane(
              "php",
              "plumb-frontend/php-sidecar/src/translator.mjs",
              Pattern.compile("kind:\\s*KIND\\.NEW\\b"),
              Pattern.compile("calleeSig\\s*:"),
              Set.of(),
              List.of(),
              Set.of()),
          new Lane(
              "python",
              "plumb-frontend/python-sidecar/src/plumb_translator.py",
              Pattern.compile("\"kind\":\\s*Kind\\.NEW\\b"),
              Pattern.compile("\"calleeSig\""),
              Set.of(),
              List.of(),
              Set.of()),
          new Lane(
              "go",
              "plumb-frontend/go-sidecar/translator.go",
              Pattern.compile("Kind:\\s*KindNew\\b"),
              Pattern.compile("CalleeSig\\s*:"),
              Set.of(),
              List.of("mapGoType(litType)", "mapGoType(elementType("),
              Set.of()));

  /**
   * The bytecode lane names a constructor on the invoke, not on the allocation. That is a
   * deliberate choice (BytecodeConstructorSinkTest pins it), so the lane is exempt here.
   */
  private static final String BYTECODE_LANE =
      "plumb-tac/src/main/java/org/plumb/tac/bytecode/jimple/JimpleToTacConverter.java";

  /** How far past the construction the signature may sit. */
  private static final int LOOKAHEAD = 20;

  @Test
  @DisplayName("every named construction names its constructor")
  void everyNamedConstructionNamesItsConstructor() {
    Path root = repositoryRoot();
    List<String> unnamed = new ArrayList<>();
    for (Lane lane : LANES) {
      Path file = root.resolve(lane.relativePath().replace('/', java.io.File.separatorChar));
      assertThat(Files.exists(file))
          .as("the %s frontend's source must be there to be checked", lane.name())
          .isTrue();
      unnamed.addAll(unnamedConstructions(lane, file));
    }
    assertThat(unnamed)
        .as(
            "a construction with no callee signature is invisible to every constructor family --"
                + " the scan still succeeds and the finding is simply absent")
        .isEmpty();
  }

  @Test
  @DisplayName("the bytecode lane is exempt on purpose")
  void theBytecodeLaneIsExemptOnPurpose() {
    Path root = repositoryRoot();
    Path file = root.resolve(BYTECODE_LANE.replace('/', java.io.File.separatorChar));
    assertThat(Files.exists(file)).as("the bytecode front end must be there").isTrue();
    List<String> lines = readLines(file);
    boolean sawAllocation = false;
    for (String line : lines) {
      if (line.contains("Kind.NEW")) {
        sawAllocation = true;
      }
    }
    assertThat(sawAllocation)
        .as("the bytecode lane allocates; if that changed, this exemption must be revisited")
        .isTrue();
  }

  @Test
  @DisplayName("the check reports a construction that names no constructor")
  void theCheckReportsAConstructionThatNamesNoConstructor() {
    // The negative control. Without it, a check that reported nothing would look exactly like a
    // check that works.
    String source =
        """
        class Demo {
          void m() {
            TacInstruction.builder(TacInstruction.Kind.NEW)
                .dest(dest)
                .ctorType(TacType.ofClass("pkg.Demo"))
                .args(argOps)
                .build());
          }
        }
        """;
    List<String> lines = List.of(source.split("\n", -1));
    Lane lane =
        new Lane(
            "probe",
            "probe",
            Pattern.compile("Kind\\.NEW\\b"),
            Pattern.compile("\\.calleeSig\\s*\\("),
            Set.of(),
            List.of(),
            Set.of());
    List<String> reported = unnamedConstructions(lane, lines);
    assertThat(reported).as("a construction without a signature must be reported").hasSize(1);
    assertThat(reported.get(0)).contains("probe");
  }

  @Test
  @DisplayName("the check reports nothing for a construction that names its constructor")
  void theCheckReportsNothingForANamedConstruction() {
    String source =
        """
        class Demo {
          void m() {
            TacInstruction.builder(TacInstruction.Kind.NEW)
                .dest(dest)
                .ctorType(TacType.ofClass("pkg.Demo"))
                .calleeSig("pkg.Demo.<init>/1")
                .args(argOps)
                .build());
          }
        }
        """;
    Lane lane =
        new Lane(
            "probe",
            "probe",
            Pattern.compile("Kind\\.NEW\\b"),
            Pattern.compile("\\.calleeSig\\s*\\("),
            Set.of(),
            List.of(),
            Set.of());
    assertThat(unnamedConstructions(lane, List.of(source.split("\n", -1))))
        .as("a construction that names its constructor must not be reported")
        .isEmpty();
  }

  @Test
  @DisplayName("a synthetic construction is exempt")
  void aSyntheticConstructionIsExempt() {
    String source =
        """
        private Operand? TranslateAnonymousObject(AnonymousObjectCreationExpressionSyntax creation)
        {
            var newTemp = NewTemp(ctorType);
            Emit(InstructionKind.NEW, dest: newTemp, ctorType: ctorType, args: arguments,
                node: creation);
            return newTemp;
        }
        """;
    Lane lane =
        new Lane(
            "probe",
            "probe",
            Pattern.compile("InstructionKind\\.NEW\\b"),
            Pattern.compile("calleeSig\\s*:"),
            Set.of("TranslateAnonymousObject"),
            List.of(),
            Set.of());
    assertThat(unnamedConstructions(lane, List.of(source.split("\n", -1))))
        .as("a compiler-made type has no constructor a catalogue could name")
        .isEmpty();
  }

  /** The construction sites of one lane that carry no callee signature. */
  private static List<String> unnamedConstructions(Lane lane, Path file) {
    return unnamedConstructions(lane, readLines(file));
  }

  private static List<String> unnamedConstructions(Lane lane, List<String> lines) {
    List<String> reported = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++) {
      String line = lines.get(i);
      if (isComment(line)) {
        // A javadoc that names the kind is not an emission of it.
        continue;
      }
      if (lane.newMarker().matcher(line).find()) {
        // The protocol tables name the kind; they do not emit it.
        if (line.trim().startsWith("NEW")) {
          continue;
        }
        if (isExempt(lane, lines, i)) {
          continue;
        }
        if (!namesItsConstructor(lane, lines, i)) {
          reported.add(lane.name() + ":" + (i + 1) + " (" + line.trim() + ")");
        }
      }
    }
    return reported;
  }

  /** Whether the line is a comment rather than code. */
  private static boolean isComment(String line) {
    String trimmed = line.trim();
    return trimmed.startsWith("//")
        || trimmed.startsWith("*")
        || trimmed.startsWith("/*")
        || trimmed.startsWith("#");
  }

  /** Whether the construction at {@code index} names its constructor within the look-ahead. */
  private static boolean namesItsConstructor(Lane lane, List<String> lines, int index) {
    int end = Math.min(lines.size(), index + LOOKAHEAD);
    for (int i = index; i < end; i++) {
      if (lane.signatureMarker().matcher(lines.get(i)).find()) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the construction at {@code index} is one the COMPILER made up.
   *
   * <p>Two ways to be exempt: the member that emits it is a known synthetic-shape translator, or
   * the constructed type is one of the compiler's own types.
   */
  private static boolean isExempt(Lane lane, List<String> lines, int index) {
    if (lane.exemptMembers().isEmpty()
        && lane.exemptCtorTypes().isEmpty()
        && lane.exemptCaseLabels().isEmpty()) {
      return false;
    }
    for (String member : declaredMembersAbove(lines, index)) {
      if (lane.exemptMembers().contains(member)) {
        return true;
      }
    }
    String caseLabel = caseLabelAbove(lines, index);
    if (caseLabel != null) {
      for (String exempt : lane.exemptCaseLabels()) {
        if (caseLabel.contains(exempt)) {
          return true;
        }
      }
    }
    int end = Math.min(lines.size(), index + LOOKAHEAD);
    for (int i = index; i < end; i++) {
      for (String ctorType : lane.exemptCtorTypes()) {
        if (lines.get(i).contains(ctorType)) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * The names of the members declared above a line, nearest first.
   *
   * <p>A declaration is a name followed by an argument list: {@code lowerObjectLiteral(node) {},
   * {@code private Operand? TranslateAnonymousObject(...)}. A CALL is not a declaration, so a name
   * that is reached through a dot ({@code this.emit({}) does not count.
   */
  /**
   * The label of the switch case a line sits in, when there is one.
   *
   * <p>C# lowers {@code typeof(X)} to a construction that names no constructor: the expression asks
   * for a TYPE, not for an instance, so no constructor runs. The case label is what says so.
   */
  private static String caseLabelAbove(List<String> lines, int index) {
    for (int i = index - 1; i >= 0; i--) {
      String trimmed = lines.get(i).trim();
      if (trimmed.startsWith("case ")) {
        return trimmed;
      }
      if (trimmed.startsWith("switch ")) {
        return null;
      }
    }
    return null;
  }

  private static List<String> declaredMembersAbove(List<String> lines, int index) {
    List<String> names = new ArrayList<>();
    Pattern call = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(");
    int from = Math.max(0, index - MEMBER_LOOKBACK);
    for (int i = index - 1; i >= from; i--) {
      String line = lines.get(i);
      if (isComment(line) || line.isBlank()) {
        continue;
      }
      Matcher matcher = call.matcher(line);
      String last = null;
      while (matcher.find()) {
        String name = matcher.group(1);
        int start = matcher.start(1);
        boolean throughADot = start > 0 && line.charAt(start - 1) == '.';
        if (!throughADot && !KEYWORDS.contains(name)) {
          last = name;
        }
      }
      if (last != null) {
        names.add(last);
      }
    }
    return names;
  }

  /** How far up the names of declared members are looked for. */
  private static final int MEMBER_LOOKBACK = 80;

  /**
   * Words that are followed by a parenthesis but are not declarations: control-flow keywords and
   * the like.
   */
  private static final Set<String> KEYWORDS =
      Set.of(
          "if",
          "for",
          "while",
          "switch",
          "catch",
          "return",
          "function",
          "new",
          "typeof",
          "await",
          "yield",
          "do",
          "else",
          "with",
          "using",
          "lock",
          "foreach",
          "select",
          "print",
          "echo",
          "assert",
          "super",
          "this",
          "class",
          "struct",
          "enum",
          "interface",
          "namespace",
          "public",
          "private",
          "protected",
          "internal",
          "static",
          "override",
          "virtual",
          "async",
          "def",
          "func",
          "var",
          "let",
          "const",
          "val",
          "in",
          "of",
          "is",
          "as",
          "not",
          "and",
          "or",
          "match",
          "case",
          "when",
          "where",
          "defer",
          "go",
          "chan",
          "map",
          "package",
          "import",
          "from",
          "lambda",
          "try",
          "finally",
          "raise",
          "throw",
          "except",
          "elif",
          "global",
          "nonlocal",
          "pass",
          "break",
          "continue",
          "del",
          "then",
          "end",
          "begin",
          "until",
          "unless");

  private static List<String> readLines(Path file) {
    try {
      return Files.readAllLines(file);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + file, e);
    }
  }

  /**
   * The repository root: the directory that holds both the front-end module and the TAC module.
   *
   * <p>Found by walking up from the working directory rather than assumed, so the test works the
   * same whether Maven runs from the repository root or from the module.
   */
  private static Path repositoryRoot() {
    Path candidate = Path.of("").toAbsolutePath();
    while (candidate != null) {
      if (Files.isDirectory(candidate.resolve("plumb-frontend"))
          && Files.isDirectory(candidate.resolve("plumb-tac"))) {
        return candidate;
      }
      candidate = candidate.getParent();
    }
    throw new IllegalStateException(
        "the repository root (the directory holding plumb-frontend and plumb-tac) was not found"
            + " from "
            + Path.of("").toAbsolutePath().toString().toLowerCase(Locale.ROOT));
  }
}
