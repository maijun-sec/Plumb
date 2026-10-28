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
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/**
 * A close on a local closes the allocation it was assigned from.
 *
 * <p><b>Why this matters.</b> The TAC lowers {@code X x = new X(...); x.close();} to a {@code NEW}
 * into a TEMPORARY followed by a {@code MOVE} into the local:
 *
 * <pre>
 *   NEW    dest=t_t0   (the allocation)
 *   MOVE   dest=in     lhs1=t_t0
 *   CALL   in.close/0  (the close)
 * </pre>
 *
 * <p>So the allocation is recorded on {@code t_t0} and the close on {@code in}, and the two never
 * met. The resource-leak checker reported a leak on code that closes the resource correctly -- a
 * false positive on the rule whose whole job is to be right about closings.
 *
 * <p>Making try-with-resources visible in the IR brought this to the surface: the resource's
 * implicit close is a call on the local, exactly like an explicit one, and it was equally
 * unmatched. Both now go through the same alias.
 */
class ResourceLeakAliasTest {

  private static final String RULE_ID = "dfa-taint-leak";

  @Test
  void aStreamClosedExplicitlyIsNotALeak(@TempDir Path dir) throws Exception {
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void closedExplicitly(java.io.File f) throws Exception {
            FileInputStream in = new FileInputStream(f);
            in.close();
          }
        }
        """;

    assertThat(runOn(dir.resolve("ClosedExplicitly.java"), source))
        .as("the stream IS closed, so there is no leak")
        .isEmpty();
  }

  @Test
  void aStreamClosedByTryWithResourcesIsNotALeak(@TempDir Path dir) throws Exception {
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void closedByTwr(java.io.File f) throws Exception {
            try (FileInputStream in = new FileInputStream(f)) {
              in.read();
            }
          }
        }
        """;

    assertThat(runOn(dir.resolve("ClosedByTwr.java"), source))
        .as("try-with-resources closes the stream, so there is no leak")
        .isEmpty();
  }

  @Test
  void aStreamThatIsNeverClosedIsALeak(@TempDir Path dir) throws Exception {
    // The control. An alias map that resolved too eagerly would silence this too, and the rule
    // would be worthless.
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void genuineLeak(java.io.File f) throws Exception {
            FileInputStream in = new FileInputStream(f);
            in.read();
          }
        }
        """;

    assertThat(runOn(dir.resolve("GenuineLeak.java"), source))
        .as("the stream is never closed, so it IS a leak")
        .isNotEmpty();
  }

  @Test
  void aStreamClosedOnlyOnOnePathIsStillALeak(@TempDir Path dir) throws Exception {
    // The precision probe: a close on SOME path is not a close on EVERY path.
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void closedOnOnePath(java.io.File f, boolean flag) throws Exception {
            FileInputStream in = new FileInputStream(f);
            if (flag) {
              in.close();
            }
            in.read();
          }
        }
        """;

    assertThat(runOn(dir.resolve("ClosedOnOnePath.java"), source))
        .as("the close is conditional, so the other path leaks")
        .isNotEmpty();
  }

  @Test
  void aStreamClosedInAFinallyBlockIsNotALeak(@TempDir Path dir) throws Exception {
    // The canonical shape for closing a resource, and the shape the Juliet
    // corpus uses. A finally block runs on EVERY exit path, so a close inside
    // it is unconditional -- even when a guard puts it inside a branch arm.
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void closedInFinally(java.io.File f) throws Exception {
            FileInputStream in = null;
            try {
              in = new FileInputStream(f);
              in.read();
            } finally {
              if (in != null) {
                in.close();
              }
            }
          }
        }
        """;

    assertThat(runOn(dir.resolve("ClosedInFinally.java"), source))
        .as("a finally block runs on every path, so the close is unconditional")
        .isEmpty();
  }

  @Test
  void aStreamThatIsNeverClosedInAFinallyBlockIsStillALeak(@TempDir Path dir) throws Exception {
    // The control for the finally case: the finally block exists but does not
    // close the resource.
    String source =
        """
        import java.io.FileInputStream;

        public class LeakFixture {
          public void notClosedInFinally(java.io.File f) throws Exception {
            FileInputStream in = null;
            try {
              in = new FileInputStream(f);
              in.read();
            } finally {
              in = null;
            }
          }
        }
        """;

    assertThat(runOn(dir.resolve("NotClosedInFinally.java"), source))
        .as("the finally block does not close the stream, so it IS a leak")
        .isNotEmpty();
  }

  @Test
  void anAllocationThatIsNotCloseableIsNotALeak(@TempDir Path dir) throws Exception {
    // Only a CLOSEABLE allocation can leak. `new byte[1024]` and
    // `new String(bytes)` are allocations, but nothing can be "not closed"
    // about them: they have no close(). Reporting them was the difference
    // between a rule that finds a real defect and a rule that cries wolf.
    String source =
        """
        public class LeakFixture {
          public void notCloseable(int n) {
            byte[] buffer = new byte[n];
            String text = new String(buffer);
          }
        }
        """;

    assertThat(runOn(dir.resolve("NotCloseable.java"), source))
        .as("an array and a String cannot leak; they have no close()")
        .isEmpty();
  }

  /** Analyse one file with the resource-leak checker and return its findings. */
  private List<Finding> runOn(Path file, String source) throws Exception {
    Files.writeString(file, source);
    ParsedSource parsed = new JavaSourceParser().parse(file);
    TacProgram program = new TacTranslator(parsed).translate();
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new ResourceLeakChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (RULE_ID.equals(f.ruleId())) {
        out.add(f);
      }
    }
    return out;
  }
}
