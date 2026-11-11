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
package org.plumb.dfa.checkers.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.TuningConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.dfa.fact.BuiltinFactKinds;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;
import org.plumb.tac.translate.TacTranslator;

/**
 * A call with the same method name and arity as the enclosing method is NOT self-recursion when the
 * receiver is a different object.
 *
 * <p>The checker used to classify a call as self-recursion from the method name and arity alone:
 *
 * <pre>
 *   boolean isSelfRecursion = callee.equals(mine) || (nameMatch &amp;&amp; arityMatch) || ...;
 * </pre>
 *
 * <p>So ordinary delegation was reported as uncontrolled recursion:
 *
 * <pre>
 *   class Repository { void rename(String a, String b) { } }
 *   class Controller {
 *     private final Repository repository = new Repository();
 *     void rename(String a, String b) { repository.rename(a, b); }   // ← reported
 *   }
 * </pre>
 *
 * <p>Receiver identity is what distinguishes the two cases. The IR records the receiver operand as
 * {@code args[0]} ahead of the declared arguments, so a scoped call can be told from an unqualified
 * one, and the receiver's declared type says which object it is.
 *
 * <p>Each defect case below has a control that must stay silent, so the test cannot pass for the
 * wrong reason.
 */
class RecursionReceiverIdentityTest {

  private static final String RULE = "dfa-taint-uncontrolled-recursion";

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
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
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
        new DfaEngine(cfg, List.of(new UncontrolledRecursionChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  /**
   * The defect: a controller delegating to a repository method of the same name and arity.
   *
   * <p>This is the shape that produced the false positive — two unrelated classes sharing a method
   * name and an arity.
   */
  @Test
  void delegationToAnotherObjectOfTheSameMethodNameIsNotRecursion() throws Exception {
    List<Finding> out =
        scan(
            """
            class Repository {
              public void rename(String a, String b) { }
            }
            class Controller {
              private final Repository repository = new Repository();
              public void rename(String a, String b) {
                repository.rename(a, b);
              }
            }
            """,
            "Delegation.java");
    assertThat(out).as("delegating to a different object is not a self-call").isEmpty();
  }

  /**
   * The same shape across separate files, which is how it appears in a real Spring controller.
   *
   * <p>Cross-file scanning was what surfaced the defect in the first place.
   */
  @Test
  void delegationAcrossFilesIsNotRecursion() throws Exception {
    Path repoDir = tmp.resolve("repo");
    Path ctlDir = tmp.resolve("ctl");
    Files.createDirectories(repoDir);
    Files.createDirectories(ctlDir);
    Files.writeString(
        repoDir.resolve("OrderRepository.java"),
        """
        package repo;
        public class OrderRepository {
          public void rename(String a, String b) { }
        }
        """);
    Files.writeString(
        ctlDir.resolve("OrderController.java"),
        """
        package ctl;
        import repo.OrderRepository;
        public class OrderController {
          private final OrderRepository repository = new OrderRepository();
          public void rename(String a, String b) {
            repository.rename(a, b);
          }
        }
        """);

    List<Finding> out =
        scanFiles(repoDir.resolve("OrderRepository.java"), ctlDir.resolve("OrderController.java"));
    assertThat(out).as("a cross-class call with the same name and arity is delegation").isEmpty();
  }

  /** Control: a genuine unqualified self-call must still be reported. */
  @Test
  void anUnqualifiedSelfCallIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public void loop(String a) {
                loop(a);
              }
            }
            """,
            "Unqualified.java");
    assertThat(out).as("an unqualified self-call is recursion").isNotEmpty();
  }

  /** Control: a genuine {@code this.<same name>(...)} call must still be reported. */
  @Test
  void aThisSelfCallIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public void loop(String a) {
                this.loop(a);
              }
            }
            """,
            "ThisCall.java");
    assertThat(out).as("`this.loop(...)` is recursion").isNotEmpty();
  }

  /**
   * Control: recursion through a field that holds the enclosing instance is still recursion.
   *
   * <p>The IR does not type field references, so the receiver's type is unknown and the decision
   * falls back to the class named in the callee signature. Here that class IS the enclosing class.
   */
  @Test
  void aSelfCallThroughAFieldOfTheEnclosingTypeIsStillReported() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              private T self;
              public void loop(String a) {
                self.loop(a);
              }
            }
            """,
            "ViaField.java");
    assertThat(out).as("a field of the enclosing type is the same kind of receiver").isNotEmpty();
  }

  /**
   * Control: a field of ANOTHER type, holding an object whose method has the same name.
   *
   * <p>This is the shape that forced the fallback: the receiver's type is unknown here too, so the
   * class named in the callee signature is what decides. It is {@code T}, not the enclosing {@code
   * U}, so the call leaves the enclosing method.
   */
  @Test
  void aFieldOfAnotherTypeHoldingTheSameMethodNameIsNotRecursion() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public void loop(String a) { }
            }
            class U {
              private T t = new T();
              public void delegate(String a) {
                t.loop(a);
              }
            }
            """,
            "ForeignField.java");
    assertThat(out).as("the call leaves the enclosing method").isEmpty();
  }

  /**
   * Control: a same-named method on a DIFFERENT class, reached without a receiver variable — the
   * unqualified case must resolve against the enclosing class, so it stays recursion.
   */
  @Test
  void anUnqualifiedCallOfTheSameNameResolvesAgainstTheEnclosingClass() throws Exception {
    List<Finding> out =
        scan(
            """
            class Other {
              public void loop(String a) { }
            }
            class T {
              public void loop(String a) {
                loop(a);
              }
            }
            """,
            "UnqualifiedWithOtherClass.java");
    assertThat(out).as("an unqualified call binds to the enclosing class").isNotEmpty();
  }

  /** Control: a different method name on the same class is not recursion. */
  @Test
  void aDifferentMethodNameIsNotRecursion() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public void outer(String a) {
                inner(a);
              }
              public void inner(String a) { }
            }
            """,
            "DifferentName.java");
    assertThat(out).as("a different method name is not recursion").isEmpty();
  }

  /**
   * A constructor is not recursion.
   *
   * <p>A constructor that called itself would never return, and the language rejects it at compile
   * time -- so the comparison of the callee's name against the enclosing method's name could only
   * ever match by accident: the constructor's name and the enclosing constructor's name are the
   * same word, and the receiver is {@code this} in both cases.
   *
   * <p>This is not hypothetical. The bytecode front end names the implicit super() call the
   * compiler inserts, so every class with a constructor carries a {@code java.lang.Object.<init>/0}
   * call whose receiver is {@code this} -- and every one of them was reported as uncontrolled
   * recursion. The source front end does not emit that call, which is why the false positive only
   * showed up on bytecode.
   */
  @Test
  void aConstructorCallIsNotRecursion() throws Exception {
    List<Finding> out =
        scan(
            """
            class T {
              public T() {
                super();
              }
              public void loop(String a) { }
            }
            """,
            "Constructor.java");
    assertThat(out).as("running a constructor is not re-entering the enclosing method").isEmpty();
  }

  /**
   * A front end that does not record the enclosing class still gets the right answer.
   *
   * <p>The bytecode front end does not record it, so the receiver named {@code this} had no class
   * to be compared against, and every call on {@code this} looked like a call on the enclosing
   * instance. The function's signature names the declaring class when the front end knows it, so
   * that is where the class comes from.
   *
   * <p>The control is a receiver of ANOTHER type: the call leaves the enclosing class, so it is
   * delegation and must stay silent.
   */
  @Test
  void theDeclaringClassComesFromTheSignatureWhenTheEnclosingClassIsAbsent() throws Exception {
    TacFunction function =
        functionWithoutEnclosingClass(
            "org.apache.commons.text.StringTokenizer.add(1)",
            "add",
            List.of(new TacOperand.TacVar("$stack2:java.lang.String", TacType.STRING, false)));

    TacInstruction onThis =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("org.apache.commons.text.StringTokenizer.add/1")
            .args(
                List.of(
                    new TacOperand.TacVar(
                        "this:org.apache.commons.text.StringTokenizer",
                        TacType.ofClass("org.apache.commons.text.StringTokenizer"),
                        false),
                    new TacOperand.TacVar("$stack2:java.lang.String", TacType.STRING, false)))
            .line(7)
            .column(1)
            .build();

    assertThat(UncontrolledRecursionChecker.isSelfRecursion(onThis, function))
        .as(
            "a call on this, inside the class the signature names, is recursion"
                + " [fn.name()="
                + function.name()
                + " sig="
                + function.signature()
                + " enclosing="
                + function.enclosingClass()
                + "]")
        .isTrue();

    TacInstruction onAnother =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("org.apache.commons.text.StringTokenizer.add/1")
            .args(
                List.of(
                    new TacOperand.TacVar(
                        "other:org.apache.commons.text.StringLookupFactory",
                        TacType.ofClass("org.apache.commons.text.StringLookupFactory"),
                        false),
                    new TacOperand.TacVar("$stack2:java.lang.String", TacType.STRING, false)))
            .line(7)
            .column(1)
            .build();

    assertThat(UncontrolledRecursionChecker.isSelfRecursion(onAnother, function))
        .as("a call on another object of another type is delegation")
        .isFalse();

    // The return type may itself be a qualified name, and its dots are not the declaring
    // class. A source front end that does not record the enclosing class must not pick the
    // return type's package apart as the class.
    TacFunction sourceShaped =
        functionWithoutEnclosingClass(
            "open(String):java.io.File",
            "open",
            List.of(new TacOperand.TacVar("$stack2:java.lang.String", TacType.STRING, false)));
    TacInstruction onThisInSourceShape =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("open/1")
            .args(
                List.of(
                    new TacOperand.TacVar(
                        "this:java.io.File", TacType.ofClass("java.io.File"), false),
                    new TacOperand.TacVar("$stack2:java.lang.String", TacType.STRING, false)))
            .line(7)
            .column(1)
            .build();

    assertThat(UncontrolledRecursionChecker.isSelfRecursion(onThisInSourceShape, sourceShaped))
        .as("the return type's package is not the declaring class")
        .isFalse();
  }

  /** A function whose enclosing class was never recorded, as the bytecode front end produces. */
  private static TacFunction functionWithoutEnclosingClass(
      String signature, String name, List<TacOperand.TacVar> params) {
    return new TacFunction(
        name,
        signature,
        TacType.VOID,
        params,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        java.util.Map.of(),
        java.util.Map.of(),
        null,
        org.plumb.tac.ir.SourceOrigin.SOURCE,
        List.of(),
        org.plumb.tac.ir.SourceLanguage.JAVA,
        org.plumb.tac.ir.IrPrecision.SOURCE_FULL);
  }

  /** Scan several files together, as a cross-TU scan does. */
  private List<Finding> scanFiles(Path... files) throws Exception {
    List<TacProgram> programs = new ArrayList<>();
    for (Path file : files) {
      ParsedSource ps = new JavaSourceParser().parse(file);
      programs.add(new TacTranslator(ps).translate());
    }
    EngineConfig base = ConfigLoader.loadFromClasspath();
    EngineConfig cfg =
        new EngineConfig(
            base.sinkFamilies(),
            BuiltinFactKinds.registryWithBuiltins(),
            base.checkers(),
            base.cweMappings(),
            new TuningConfig(
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
        new DfaEngine(cfg, List.of(new UncontrolledRecursionChecker()), "global", null)
            .runWithGlobalStore(programs, 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE.equals(f.ruleId())) out.add(f);
    }
    return out;
  }
}
