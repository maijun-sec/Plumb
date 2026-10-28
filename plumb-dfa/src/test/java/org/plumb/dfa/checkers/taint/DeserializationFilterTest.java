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
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * The JEP-290 deserialization filter is a mitigation, and the engine has to honour it.
 *
 * <p><b>Why this matters.</b> {@code UnsafeDeserializationChecker}'s own specification names {@code
 * ObjectInputFilter} and {@code resolveClass} as sanitizers it must recognise, and no such
 * recognition existed: the names appeared in prose and nowhere else. A build that shipped the fix
 * for a deserialization CVE was still reported -- a false positive on the one family whose severity
 * is CRITICAL.
 *
 * <p><b>What the test pins.</b> Three shapes, and the difference between them is the whole point:
 *
 * <ul>
 *   <li>a PATTERN filter ending in {@code !*} refuses everything it does not list, so the read is
 *       safe;
 *   <li>a LAMBDA filter that can return {@code REJECTED} is an allowlist, so the read is safe;
 *   <li>a LAMBDA filter that only ever returns {@code ALLOWED} or {@code UNDECIDED} restricts
 *       nothing, so the read is NOT safe and the finding must stand.
 * </ul>
 *
 * <p>The third is the partial mitigation, and a checker that only asked "is there a filter?" would
 * call it safe and would be wrong.
 */
class DeserializationFilterTest {

  private static final String RULE_ID = "dfa-taint-unsafe-deserialize";

  /** The source of the untrusted bytes, and the stream that carries them. */
  private static final String FIXTURE_HEADER =
      """
      import java.io.ByteArrayInputStream;
      import java.io.IOException;
      import java.io.InputStream;
      import java.io.ObjectInputFilter;
      import java.io.ObjectInputStream;

      public class FilterFixture {\
      """;

  @Test
  void aPatternFilterThatRefusesEverythingElseIsAMitigation(@TempDir Path dir) throws Exception {
    String source =
        FIXTURE_HEADER
            + """
              public Object patternFiltered(byte[] payload) throws Exception {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
                ois.setObjectInputFilter(
                    ObjectInputFilter.Config.createFilter(
                        "java.security.KeyPair;java.security.PrivateKey;!*"));
                return ois.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("PatternFiltered.java"), source);

    assertThat(findings)
        .as("a pattern filter ending in !* refuses every class it does not list")
        .isEmpty();
  }

  @Test
  void aLambdaFilterThatCanRejectIsAMitigation(@TempDir Path dir) throws Exception {
    String source =
        FIXTURE_HEADER
            + """
              public Object allowlistFiltered(byte[] payload) throws Exception {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
                ois.setObjectInputFilter(
                    info -> {
                      if (info.serialClass() == null) {
                        return ObjectInputFilter.Status.UNDECIDED;
                      }
                      String name = info.serialClass().getName();
                      if (name.startsWith("com.example.safe.")) {
                        return ObjectInputFilter.Status.ALLOWED;
                      }
                      return ObjectInputFilter.Status.REJECTED;
                    });
                return ois.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("AllowlistFiltered.java"), source);

    assertThat(findings).as("a filter that can REJECT is the shipped mitigation").isEmpty();
  }

  @Test
  void aFilterThatNeverRejectsIsNotAMitigation(@TempDir Path dir) throws Exception {
    String source =
        FIXTURE_HEADER
            + """
              public Object partiallyFiltered(byte[] payload) throws Exception {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
                ois.setObjectInputFilter(
                    info -> {
                      if (info.serialClass() == null) {
                        return ObjectInputFilter.Status.UNDECIDED;
                      }
                      String name = info.serialClass().getName();
                      if (name.startsWith("java.lang.") || name.startsWith("java.util.")) {
                        return ObjectInputFilter.Status.ALLOWED;
                      }
                      return ObjectInputFilter.Status.UNDECIDED;
                    });
                return ois.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("PartiallyFiltered.java"), source);

    assertThat(findings)
        .as("a filter that can only ALLOW or leave UNDECIDED restricts nothing")
        .isNotEmpty();
  }

  @Test
  void aPatternFilterThatDoesNotRefuseEverythingElseIsNotAMitigation(@TempDir Path dir)
      throws Exception {
    // The PATTERN counterpart of the partial-mitigation case.
    //
    // A JEP-290 pattern without the trailing "!*" allows every class the
    // pattern does not mention, so it restricts nothing. A tracker that asked
    // only "is there a filter?" would call this safe, and the gadget classes
    // would still reach their constructors.
    String source =
        FIXTURE_HEADER
            + """
              public Object permissivePattern(byte[] payload) throws Exception {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
                ois.setObjectInputFilter(
                    ObjectInputFilter.Config.createFilter("java.util.*;java.lang.*"));
                return ois.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("PermissivePattern.java"), source);

    assertThat(findings)
        .as("a pattern without !* allows every class it does not mention")
        .isNotEmpty();
  }

  @Test
  void anUnfilteredStreamIsStillReported(@TempDir Path dir) throws Exception {
    // The control: without a filter, the finding must stand.  A tracker that
    // suppressed every deserialization finding would pass the other three
    // tests and be worthless.
    String source =
        FIXTURE_HEADER
            + """
              public Object unfiltered(byte[] payload) throws Exception {
                ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
                return ois.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("Unfiltered.java"), source);

    assertThat(findings).isNotEmpty();
  }

  @Test
  void aFilterDoesNotSilenceAnotherSinkFamily(@TempDir Path dir) throws Exception {
    // The mitigation is SCOPED.
    //
    // A JEP-290 filter says "these classes may be reconstructed". It says
    // NOTHING about a log entry. A tracker that suppressed every finding on a
    // filtered stream would silence real bugs in other families -- and it
    // would do so silently, which is the worst way for a security tool to be
    // wrong.
    //
    // The shape matters: the STREAM ITSELF is the tainted argument of the log
    // sink, so the tracker's stream name and the sink's argument are the same
    // variable. Without that, the tracker's answer would never be consulted
    // and the test would pass whether or not the scoping existed.
    //
    // The mutation that removes the family check survives every other test in
    // this file, so this test is the only thing standing between the engine and
    // an unscoped suppression.
    String source =
        """
        import java.io.ByteArrayInputStream;
        import java.io.ObjectInputFilter;
        import java.io.ObjectInputStream;
        import java.util.logging.Logger;

        public class OtherFamilyFixture {
          public Object filteredButStillLogged(byte[] payload) throws Exception {
            ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(payload));
            ois.setObjectInputFilter(
                ObjectInputFilter.Config.createFilter("java.util.*;!*"));
            Logger log = Logger.getLogger("x");
            log.info(ois);
            return ois.readObject();
          }
        }
        """;

    List<Finding> findings =
        runOn(dir.resolve("OtherFamily.java"), source, "dfa-taint-log-injection");

    assertThat(findings).as("a deserialization filter says nothing about a log entry").isNotEmpty();
  }

  @Test
  void aFilterOnOneStreamDoesNotSilenceAnother(@TempDir Path dir) throws Exception {
    // The filter is a property of the STREAM.  A second, unfiltered stream in
    // the same method is still a finding.
    String source =
        FIXTURE_HEADER
            + """
              public Object twoStreams(byte[] payload) throws Exception {
                ObjectInputStream filtered = new ObjectInputStream(new ByteArrayInputStream(payload));
                filtered.setObjectInputFilter(
                    ObjectInputFilter.Config.createFilter("java.util.*;!*"));
                ObjectInputStream plain = new ObjectInputStream(new ByteArrayInputStream(payload));
                return plain.readObject();
              }
            """
            + "}\n";

    List<Finding> findings = runOn(dir.resolve("TwoStreams.java"), source);

    assertThat(findings).as("the filter is on `filtered`, and the read is on `plain`").isNotEmpty();
  }

  /** Analyse one file with the deserialization checker and return its findings. */
  private List<Finding> runOn(Path file, String source) throws Exception {
    return runOn(file, source, RULE_ID);
  }

  /**
   * Analyse one file with the deserialization checker and return the findings of one rule.
   *
   * @param ruleId the rule to collect
   */
  private List<Finding> runOn(Path file, String source, String ruleId) throws Exception {
    Files.writeString(file, source);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(
                config,
                List.of(
                    new UnsafeDeserializationChecker(),
                    new CommandInjectionChecker(),
                    new LogInjectionChecker()),
                "global",
                null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (ruleId.equals(f.ruleId())) {
        out.add(f);
      }
    }
    return out;
  }
}
