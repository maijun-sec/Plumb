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
package org.plumb.tac.frontend.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * What happens when two threads speak to one sidecar at the same time.
 *
 * <h2>Why this is pinned</h2>
 *
 * <p>One frontend serves every worker thread of a scan, so one pipe carries one conversation at a
 * time. The framing has no request id, so a request that interleaves with another one is answered
 * against the other's state -- and, worse, the interleaved bytes leave the peer waiting for a
 * length that belongs to neither request, so neither side ever finishes. The symptom in the field
 * is a scan that stops with no error anywhere: every worker waiting for an answer that cannot come.
 *
 * <p>So the exchange has to be one indivisible step, and this test says so: many threads, many
 * requests, and every answer must carry the marker of the request that asked for it. A peer that
 * answers out of turn, or not at all, fails the test.
 */
class SidecarProcessConcurrencyTest {

  /** How many threads speak at once. */
  private static final int THREADS = 8;

  /** How many requests each thread sends. */
  private static final int REQUESTS_PER_THREAD = 25;

  @Test
  @Timeout(120)
  @DisplayName("concurrent exchanges each get their own answer")
  void concurrentExchangesEachGetTheirOwnAnswer() throws Exception {
    SidecarProcess process = launchEcho();
    try {
      ExecutorService pool =
          Executors.newFixedThreadPool(
              THREADS,
              new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                  // A daemon thread, so a peer that never answers fails the
                  // test by TIMEOUT instead of by keeping the test JVM alive.
                  Thread thread = new Thread(runnable, "echo-writer");
                  thread.setDaemon(true);
                  return thread;
                }
              });
      List<Future<String>> answers = new ArrayList<>();
      for (int worker = 0; worker < THREADS; worker++) {
        final int id = worker;
        answers.add(
            pool.submit(
                () -> {
                  StringBuilder wrong = new StringBuilder();
                  for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                    String marker = "worker-" + id + "-request-" + i;
                    String response =
                        process.exchange("{\"op\":\"echo\",\"marker\":\"" + marker + "\"}");
                    if (!response.contains("\"echo\":\"" + marker + "\"")) {
                      wrong
                          .append(marker)
                          .append(" was answered with ")
                          .append(response)
                          .append('\n');
                    }
                  }
                  return wrong.toString();
                }));
      }
      pool.shutdown();
      assertThat(pool.awaitTermination(90, TimeUnit.SECONDS))
          .as("the exchanges did not finish: the peer is waiting for an answer that cannot come")
          .isTrue();
      for (Future<String> answer : answers) {
        assertThat(answer.get()).as("a request was answered with somebody else's answer").isEmpty();
      }
    } finally {
      process.close();
    }
  }

  /**
   * Launch the echo peer.
   *
   * <p>The peer is this test's own class, so the child process needs nothing but the test's classes
   * on its classpath -- no toolchain, no sidecar, nothing to install.
   *
   * @return the peer
   * @throws IOException when the peer could not be started
   */
  private static SidecarProcess launchEcho() throws IOException {
    Path classes;
    try {
      classes =
          Path.of(EchoSidecar.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException ex) {
      throw new IOException(
          "the test's own classes are not somewhere the peer can be pointed at", ex);
    }
    return SidecarProcess.launch(
        "echo",
        List.of(
            javaExecutable().toString(), "-cp", classes.toString(), EchoSidecar.class.getName()),
        null);
  }

  /**
   * The Java program of the JVM that runs this test.
   *
   * @return the Java executable
   */
  private static Path javaExecutable() {
    String name =
        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe"
            : "java";
    return Path.of(System.getProperty("java.home"), "bin", name);
  }
}
