package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.dfa.checkers.taint.UnsafeDeserializationChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.config.SinkFamily;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * R1099 regression gate: the receiver-tainted sink convention.
 *
 * <h2>The defect</h2>
 *
 * <p>{@code ObjectInputStream#readObject()} takes zero arguments; the untrusted data is the stream
 * (the receiver). The CWE-502 family listed it as a pattern with no {@code arg_index}, so it fell
 * back to {@code default_arg_index = 0} — "first user argument" — and indexed past the end of an
 * empty argument list. The rule was registered CRITICAL but was structurally unreachable, which is
 * worse than having no rule at all: the coverage report claims the CWE is handled.
 *
 * <h2>The fix</h2>
 *
 * <p>{@link SinkFamily#RECEIVER_ARG_INDEX} ({@code -2}) declares "the tainted value is the
 * receiver", which maps to TAC {@code args[0]} with no offset.
 */
class R1099ReceiverSinkTest {

  /**
   * A receiver-tainted sink is reachable.
   *
   * <p>NOTE ON SCOPE: this asserts the plumbing — that a pattern declaring {@code arg_index: -2}
   * causes the checker to inspect the receiver operand (TAC {@code args[0]}) and report when that
   * operand carries taint. It deliberately marks the receiver as tainted through a direct parameter
   * alias rather than through a constructor chain, because taint propagation INTO an object under
   * construction is a separate, still-open gap (the NEW instruction does not yet taint its dest
   * from a tainted constructor argument). Fixing that is tracked independently; bundling it here
   * would make this regression gate fail for an unrelated reason.
   */
  private static final String TAINTED_RECEIVER =
      """
      import java.io.ObjectInputStream;
      class Rcv {
        Object go(ObjectInputStream attackerStream) throws Exception {
          return attackerStream.readObject();
        }
      }
      """;

  /** Receiver reached through a local alias. */
  private static final String ALIASED_RECEIVER =
      """
      import java.io.ObjectInputStream;
      class Rcv3 {
        Object go(ObjectInputStream attackerStream) throws Exception {
          ObjectInputStream alias = attackerStream;
          return alias.readObject();
        }
      }
      """;

  /** Receiver is constructed from a constant byte array. */
  private static final String CLEAN_RECEIVER =
      """
      import java.io.ByteArrayInputStream;
      import java.io.ObjectInputStream;
      class Rcv2 {
        Object go() throws Exception {
          ObjectInputStream ois =
              new ObjectInputStream(new ByteArrayInputStream(new byte[] {1, 2}));
          return ois.readObject();
        }
      }
      """;

  @TempDir Path tmp;

  private List<Finding> scan(String source, String name) throws Exception {
    Path file = tmp.resolve(name);
    Files.writeString(file, source);
    ParsedSource ps = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(ps).translate();
    EngineConfig base = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(base, List.of(new UnsafeDeserializationChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if ("dfa-taint-unsafe-deserialize".equals(f.ruleId())) out.add(f);
    }
    return out;
  }

  @Test
  void receiverSentinelIsDistinctFromFamilyDefault() {
    assertThat(SinkFamily.RECEIVER_ARG_INDEX).isEqualTo(-2);

    // A pattern declared with the sentinel must survive argIndexFor() rather than
    // collapsing to the family default.
    SinkFamily family =
        new SinkFamily(
            "deserialization.sinks",
            "CWE-502",
            "CRITICAL",
            true,
            0,
            List.of(
                new SinkFamily.SinkPattern(
                    "^java\\.io\\.ObjectInputStream\\.readObject(/\\d+)?",
                    SinkFamily.RECEIVER_ARG_INDEX)));

    int idx = family.argIndexFor("java.io.ObjectInputStream.readObject/0");
    assertThat(idx)
        .as("readObject must resolve to the receiver sentinel, not default_arg_index 0")
        .isEqualTo(SinkFamily.RECEIVER_ARG_INDEX);
    assertThat(family.matchPattern("java.io.ObjectInputStream.readObject/0").isReceiverTainted())
        .isTrue();
  }

  @Test
  void zeroArgDeserializationSinkIsReachableWhenReceiverIsTainted() throws Exception {
    List<Finding> findings = scan(TAINTED_RECEIVER, "Rcv.java");
    assertThat(findings)
        .as(
            "a tainted receiver reaching ObjectInputStream#readObject must report CWE-502;"
                + " before the receiver sentinel this sink was structurally unreachable")
        .hasSize(1);
    assertThat(findings.get(0).line()).isPositive();
  }

  @Test
  void cleanReceiverDoesNotFire() throws Exception {
    List<Finding> findings = scan(CLEAN_RECEIVER, "Rcv2.java");
    assertThat(findings)
        .as("a receiver built from a constant byte array is not attacker-controlled")
        .isEmpty();
  }
}
