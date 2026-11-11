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
package org.plumb.main;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.style.AstChecker;
import org.plumb.style.performance.DuplicatedStringLiteralCheck;

/**
 * The phase-1 workers must not share the AST checkers.
 *
 * <p><b>What went wrong.</b> The runner built the AST checkers ONCE and handed the same list to
 * every worker of the parallel parse phase. An AST checker holds the state of the file it is
 * looking at, and the dispatcher's lifecycle is per file: init(), then the visits, then finish().
 * Two workers running the same checker instance are two files writing into one state: one worker's
 * init() clears what the other one collected, and the checker throws while the other worker is
 * reading it.
 *
 * <p>The loss is not only a smaller report. The file whose findings are lost is whichever one the
 * scheduler picked, so two runs of the same scan over the same tree report DIFFERENT numbers. On a
 * 7047-file corpus the shared instances made 2407 rule visits fail across 1940 files, and the
 * duplicated-literal rule alone moved by 1510 findings between two runs of the SAME build with the
 * SAME configuration.
 */
class PerWorkerStyleChecksTest {

  @Test
  @DisplayName("two workers do not share the AST checkers")
  void twoWorkersDoNotShareTheAstCheckers() throws Exception {
    PerWorkerStyleChecks perWorker =
        new PerWorkerStyleChecks(() -> List.of(new DuplicatedStringLiteralCheck()));

    List<AstChecker> mine = perWorker.forCurrentWorker();
    AtomicReference<List<AstChecker>> theirs = new AtomicReference<>();
    Thread worker =
        new Thread(
            () -> {
              theirs.set(perWorker.forCurrentWorker());
            });
    worker.start();
    worker.join();

    assertThat(perWorker.forCurrentWorker())
        .as("the same worker keeps the same set")
        .isSameAs(mine);
    assertThat(theirs.get()).as("another worker must get its own set").isNotSameAs(mine);
    assertThat(theirs.get().get(0))
        .as("and no checker may be shared between the two workers")
        .isNotSameAs(mine.get(0));
  }

  @Test
  @DisplayName("the set is built once per worker, not once per file")
  void theSetIsBuiltOncePerWorker() {
    PerWorkerStyleChecks perWorker =
        new PerWorkerStyleChecks(() -> List.of(new DuplicatedStringLiteralCheck()));

    perWorker.forCurrentWorker();
    perWorker.forCurrentWorker();
    perWorker.forCurrentWorker();

    assertThat(perWorker.builtCount())
        .as("building the set for every file would cost more than the phase it accelerates")
        .isEqualTo(1);
  }
}
