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

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.plumb.style.AstChecker;

/**
 * The AST checkers ONE worker runs with.
 *
 * <p><b>Why each worker needs its own.</b> An AST checker holds the state of the file it is looking
 * at -- the positions of a duplicated string literal, the names it has already seen -- and the
 * dispatcher's lifecycle is per FILE: {@code init()}, then the visits, then {@code finish()}. The
 * phase-1 workers run at the same time, so a checker set shared between two of them is two files
 * writing into one state: one worker's {@code init()} clears what the other one collected, and the
 * checker throws while the other worker is reading it.
 *
 * <p>The failure is not only a smaller report. The file whose findings are lost is whichever one
 * the scheduler picked, so two runs of the same scan over the same tree report DIFFERENT numbers,
 * and the difference lands entirely on the rules that keep per-file state. A measurement taken
 * against such a report cannot tell a real change from the scheduler.
 *
 * <p>The set is built once per worker and not once per file: the checkers are numerous, and
 * building them for every file would cost more than the phase they accelerate.
 */
final class PerWorkerStyleChecks {

  private final AtomicInteger built = new AtomicInteger();
  private final ThreadLocal<List<AstChecker>> perThread;

  PerWorkerStyleChecks(Supplier<List<AstChecker>> factory) {
    this.perThread =
        ThreadLocal.withInitial(
            () -> {
              built.incrementAndGet();
              return factory.get();
            });
  }

  /**
   * The checkers for the calling thread.
   *
   * @return this worker's own set, the same one every time this thread asks
   */
  List<AstChecker> forCurrentWorker() {
    return perThread.get();
  }

  /**
   * How many sets this holder has built.
   *
   * <p>The tests use it to see that the factory runs once per worker rather than once per question.
   *
   * @return the number of times the factory has been called
   */
  int builtCount() {
    return built.get();
  }
}
