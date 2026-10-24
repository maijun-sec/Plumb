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
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A call with the same method name and arity as the enclosing method is NOT self-recursion when
 * the receiver is a different object.
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
 * <p>Receiver identity is what distinguishes the two cases. The IR records the receiver operand
 * as {@code args[0]} ahead of the declared arguments, so a scoped call can be told from an
 * unqualified one, and the receiver's declared type says which object it is.
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
            new TuningConfig(2, 1000, 1000, 100, 4, false, false, false, "full", "cwe_family",
                "sarif", false, 0),
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
    assertThat(out)
        .as("delegating to a different object is not a self-call")
        .isEmpty();
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

    List<Finding> out = scanFiles(repoDir.resolve("OrderRepository.java"), ctlDir.resolve("OrderController.java"));
    assertThat(out)
        .as("a cross-class call with the same name and arity is delegation")
        .isEmpty();
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
   * <p>This is the shape that forced the fallback: the receiver's type is unknown here too, so
   * the class named in the callee signature is what decides. It is {@code T}, not the enclosing
   * {@code U}, so the call leaves the enclosing method.
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
            new TuningConfig(2, 1000, 1000, 100, 4, false, false, false, "full", "cwe_family",
                "sarif", false, 0),
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
