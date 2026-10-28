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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.NullDerefFromReturnDfaChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1110: the dataflow null-dereference rule must fire, and must fire only where it should.
 *
 * <h2>Why the "must fire" half is tested first</h2>
 *
 * <p>A checker whose rule does not match any configured sink family is structurally unreachable and
 * still compiles, still registers, and still reports zero — indistinguishable from a rule that is
 * working and finding nothing. That is not hypothetical: {@code NullDerefChecker} is configured
 * with a sink family absent from {@code sink-families.yaml} and survives only because it implements
 * its own {@code checkInstruction} dispatch instead of going through the base class's sink lookup.
 * This rule takes the same self-contained route, so the positive cases below are what keep that
 * route honest.
 *
 * <h2>The naming</h2>
 *
 * <p>The AST counterpart keeps its own id ({@code ast-security-null-deref-from-return-ast}) and
 * both rules express the name {@code NULL_DEREF_FROM_RETURN}. Nothing here filters by name, so a
 * future de-duplication that collapses the two ids would show up as these findings disappearing.
 */
class R1110NullDerefFromReturnDfaTest {

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();

    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            org.plumb.dfa.fact.BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new org.plumb.dfa.config.TuningConfig(
                2,
                1000,
                1000,
                100,
                4,
                false,
                false,
                false,
                "full",
                "cwe_family",
                "sarif",
                false,
                0),
            base.receiverPassthroughPatterns());
    FindingReporter reporter =
        new DfaEngine(cfg, List.of(new NullDerefFromReturnDfaChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-null-deref-from-return".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  private static final String HEADER =
      "import java.util.*;\n"
          + "class T {\n"
          + "  StringBuilder make(String s) { return new StringBuilder(s); }\n";

  // ── the rule must be reachable ────────────────────────────────────────

  /** An unguarded call result is dereferenced. This is the namesake case. */
  @Test
  void unguardedCallResultIsReported() throws Exception {
    String src =
        HEADER
            + "  void bad(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    a.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "Reachability.java"))
        .as(
            "if this is empty the rule is structurally unreachable -- the exact defect shape that"
                + " left CWE-502 registered but unfireable until it got an arg_index sentinel")
        .isNotEmpty();
  }

  /** A literal null is dereferenced. */
  @Test
  void literalNullDereferenceIsReported() throws Exception {
    String src =
        HEADER
            + "  void bad() {\n"
            + "    StringBuilder a = null;\n"
            + "    a.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "LiteralNull.java")).isNotEmpty();
  }

  // ── alias transfer: the point of the dataflow version ─────────────────

  /** A guard on one name covers a plain copy of it. */
  @Test
  void guardCoversAPlainCopy() throws Exception {
    String src =
        HEADER
            + "  void ok(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    StringBuilder b = a;\n"
            + "    if (a == null) { return; }\n"
            + "    b.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "CopyTransfer.java"))
        .as("b and a are one reference, so the guard on a covers b")
        .isEmpty();
  }

  /**
   * A guard on one name covers a plain copy of it, through the dataflow path.
   *
   * <p>This is the case the AST rule reaches by walking a copy chain, and the one the dataflow
   * version reaches structurally. It passes because {@code CopyEquivalence} unions {@code b} with
   * {@code a} inside one method.
   */
  @Test
  void guardCoversAPlainCopyThroughTheDataflowPath() throws Exception {
    String src =
        HEADER
            + "  void ok(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    StringBuilder b = a;\n"
            + "    if (a == null) { return; }\n"
            + "    b.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "CopyTransfer.java"))
        .as("b and a are one reference, so the guard on a covers b")
        .isEmpty();
  }

  /**
   * A guard on one name covers the same object reached through a container.
   *
   * <p>This is the case the AST rule cannot reach, and the reason the dataflow version exists.
   * Whether {@code store.get("k")} yields the guarded object is a question about the heap, not
   * about names: no syntactic walk from {@code b} reaches {@code a}.
   *
   * <p>It was a measured gap until R1112 modelled {@code Map.put} / {@code Map.get} as store and
   * load edges. The gap was recorded with its diagnosis rather than asserted away, which is what
   * made it worth closing: the failure mode had a name and a mechanism, not just a red test.
   */
  @Test
  void guardCoversAContainerRoundTrip() throws Exception {
    String src =
        HEADER
            + "  void ok(String input) {\n"
            + "    Map<String, StringBuilder> store = new HashMap<>();\n"
            + "    StringBuilder a = make(input);\n"
            + "    store.put(\"k\", a);\n"
            + "    StringBuilder b = store.get(\"k\");\n"
            + "    if (a == null) { return; }\n"
            + "    b.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "ContainerTransfer.java"))
        .as("the guard constrains the object, and the container round trip yields that object")
        .isEmpty();
  }

  /**
   * A guard on one map key must not silence a dereference of a different key.
   *
   * <p>The control that keeps the container model from over-merging. A single merged cell per map
   * would make {@code get("b")} return the object stored under {@code "a"}, and the guard on that
   * entry would then hide a genuine finding on an unrelated one -- a false "safe" produced by
   * imprecision rather than by a real alias.
   */
  @Test
  void guardOnOneKeyDoesNotCoverAnother() throws Exception {
    String src =
        HEADER
            + "  void bad(String input) {\n"
            + "    Map<String, StringBuilder> store = new HashMap<>();\n"
            + "    StringBuilder a = make(input);\n"
            + "    StringBuilder other = make(input);\n"
            + "    store.put(\"a\", a);\n"
            + "    store.put(\"b\", other);\n"
            + "    StringBuilder fetched = store.get(\"b\");\n"
            + "    if (a == null) { return; }\n"
            + "    fetched.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "DistinctKeys.java"))
        .as(
            "fetched holds the object stored under \"b\", which the guard on \"a\" says nothing"
                + " about")
        .isNotEmpty();
  }

  // ── controls: must not over-suppress ─────────────────────────────────

  /**
   * Two independent call results are not the same object; guarding one says nothing about the
   * other.
   */
  @Test
  void guardingADifferentObjectDoesNotSuppress() throws Exception {
    String src =
        HEADER
            + "  void bad(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    StringBuilder b = make(input);\n"
            + "    if (a == null) { return; }\n"
            + "    b.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "DistinctObjects.java")).as("b is a different allocation").isNotEmpty();
  }

  /** An unguarded copy is still reported. */
  @Test
  void unguardedCopyIsStillReported() throws Exception {
    String src =
        HEADER
            + "  void bad(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    StringBuilder b = a;\n"
            + "    b.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "UnguardedCopy.java")).isNotEmpty();
  }

  /** A fresh allocation is an object by construction and is never reported. */
  @Test
  void aFreshAllocationIsNeverReported() throws Exception {
    String src =
        HEADER
            + "  void ok(String input) {\n"
            + "    StringBuilder a = new StringBuilder(input);\n"
            + "    a.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "FreshAllocation.java")).as("`new` cannot produce null").isEmpty();
  }

  /** A guarded call result directly is silent. */
  @Test
  void aDirectlyGuardedCallResultIsSilent() throws Exception {
    String src =
        HEADER
            + "  void ok(String input) {\n"
            + "    StringBuilder a = make(input);\n"
            + "    if (a == null) { return; }\n"
            + "    a.length();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "DirectGuard.java")).isEmpty();
  }

  /**
   * A reassignment over a nullable name does clear the <em>original</em> null marking.
   *
   * <p>Pinned with a reassignment from a name the rule does not treat as a call result, because the
   * question here is whether the state is cleared at all: {@code f = null;} then {@code f = other;}
   * leaves {@code f} holding {@code other}, and with no clear it would keep the marking from the
   * first line and report on {@code f.delete()}.
   */
  @Test
  void reassignmentFromAParameterDoesNotClearTheNullableMark() throws Exception {
    String src =
        "import java.io.*;\n"
            + "class T {\n"
            + "  void ok(File other) {\n"
            + "    File f = null;\n"
            + "    f = other;\n"
            + "    f.delete();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "Reassigned.java"))
        .as(
            "R1119: this used to assert silence, encoding the bug the audit found. `other` is a"
                + " parameter and carries no recorded fact, which means \"unknown\" -- not"
                + " \"proven non-null\". A caller may pass null, so the dereference is real."
                + " The case that legitimately clears is a fresh allocation, covered by"
                + " R1119FlowInsensitiveNullFactTest#aFreshAllocationStaysSilent")
        .isNotEmpty();
  }

  /**
   * Documents the limit above: a library method specified never to return null is still treated as
   * maybe-null.
   *
   * <p>{@code File.createTempFile} is total by specification, so reporting on its result is a false
   * positive. Teaching the rule which library methods are total is a sanitizer model, not alias
   * reasoning, and is out of scope here.
   *
   * <p>Asserted as still-reporting on purpose. Flipping it to {@code isEmpty()} would assert a
   * capability the rule does not have, and the assertion would then keep passing after someone
   * deleted the reporting path entirely -- a test that cannot distinguish "handled correctly" from
   * "stopped working" is worse than no test.
   */
  @Test
  void aTotalLibraryMethodIsStillTreatedAsPossiblyNull() throws Exception {
    String src =
        "import java.io.*;\n"
            + "class T {\n"
            + "  void bad() throws IOException {\n"
            + "    File f = null;\n"
            + "    f = File.createTempFile(\"a\", \"b\");\n"
            + "    f.delete();\n"
            + "  }\n"
            + "}\n";
    assertThat(scan(src, "TotalMethod.java"))
        .as(
            "KNOWN GAP: createTempFile never returns null, but this rule has no sanitizer model and"
                + " treats every call result as maybe-null. Asserted as still reporting so the gap"
                + " stays visible.")
        .isNotEmpty();
  }
}
