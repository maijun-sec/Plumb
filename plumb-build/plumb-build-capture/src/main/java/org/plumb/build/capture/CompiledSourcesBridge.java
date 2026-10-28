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
package org.plumb.build.capture;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The bridge between the inlined advice and the recorder.
 *
 * <p><b>Why this class lives in the bootstrap jar.</b> ByteBuddy's {@code Advice} is
 * <em>inlined</em> into the target method, and the inlined code runs inside the {@code
 * jdk.compiler} module. A named module does <em>not</em> read the unnamed module, so the inlined
 * code cannot call a class in the agent's own class loader at all: the call fails with a linkage
 * error, and the advice's {@code suppress = Throwable} swallows it silently.
 *
 * <p>The boot layer's unnamed module <em>is</em> readable from {@code jdk.compiler}, and the
 * bootstrap class loader is where the recorder lives. So the bridge is there too, and the inlined
 * code calls it.
 *
 * <p>And the bridge has to reach the recorder on the <em>same</em> loader: the agent's own copy of
 * the recorder is a different class, and it shares none of the recorder's state -- so a value
 * remembered by one is invisible to the other.
 */
public final class CompiledSourcesBridge {

  private static final String RECORDER = "org.plumb.build.capture.CaptureRecorder";
  private static final String ON_TASK = "onTask";
  private static final String REMEMBER = "rememberCompiledSources";
  private static final String REMEMBER_PARSED = "rememberParsedSource";

  private static volatile Method rememberMethod;
  private static volatile Method onTaskMethod;
  private static volatile Method rememberParsedMethod;

  private CompiledSourcesBridge() {}

  /**
   * Record a source the compiler parsed.
   *
   * <p>This is the sources the compiler really compiled, which is a different set from the ones the
   * task was given: the compiler parses the sources it finds on the source path as well.
   *
   * @param path the path of the parsed source
   */
  public static void recordParsed(String path) {
    if (path == null || path.isBlank()) {
      return;
    }
    try {
      Method remember = rememberParsedMethod();
      if (remember != null) {
        remember.invoke(null, path);
      }
    } catch (Throwable e) {
      // A capture must never break the compile. The advice suppresses
      // Throwable anyway, so the failure is silent by design -- but the
      // capture still works: it reports the command-line sources.
      //
      // <p>Throwable, not Exception: the failure this guards against is a
      // LinkageError, which is an Error and not an Exception.
    }
  }

  /**
   * Record the task's compiled sources, and write the capture.
   *
   * <p>Both happen here, in this order: the compiled sources are only known once the compile has
   * run, so they are read first, and the capture that reports them is written after that.
   *
   * @param task the task that was driven
   * @param sources the source files the compiler compiled
   */
  public static void record(Object task, List<String> sources) {
    try {
      Method remember = rememberMethod();
      if (remember != null) {
        remember.invoke(null, task, sources == null ? List.of() : sources);
      }
      Method onTask = onTaskMethod();
      if (onTask != null) {
        onTask.invoke(null, task);
      }
    } catch (Throwable e) {
      // A capture must never break the compile. The advice suppresses
      // Throwable anyway, so the failure is silent by design -- but the
      // capture still works: it reports the command-line sources.
      //
      // <p>Throwable, not Exception: the failure this guards against is a
      // LinkageError, which is an Error and not an Exception.
    }
  }

  /** The recorder's method, looked up once on the bootstrap loader. */
  private static Method rememberMethod() {
    Method cached = rememberMethod;
    if (cached != null) {
      return cached;
    }
    try {
      Method method = recorderClass().getMethod(REMEMBER, Object.class, List.class);
      rememberMethod = method;
      return method;
    } catch (Throwable e) {
      reportLookupFailure(REMEMBER, e);
      return null;
    }
  }

  /** The recorder's method, looked up once on the bootstrap loader. */
  private static Method onTaskMethod() {
    Method cached = onTaskMethod;
    if (cached != null) {
      return cached;
    }
    try {
      Method method = recorderClass().getMethod(ON_TASK, Object.class);
      onTaskMethod = method;
      return method;
    } catch (Throwable e) {
      reportLookupFailure(ON_TASK, e);
      return null;
    }
  }

  /** The recorder's method, looked up once on the bootstrap loader. */
  private static Method rememberParsedMethod() {
    Method cached = rememberParsedMethod;
    if (cached != null) {
      return cached;
    }
    try {
      Method method = recorderClass().getMethod(REMEMBER_PARSED, String.class);
      rememberParsedMethod = method;
      return method;
    } catch (Throwable e) {
      reportLookupFailure(REMEMBER_PARSED, e);
      return null;
    }
  }

  /**
   * Report a lookup that failed, once.
   *
   * <p><b>Why this is reported at all.</b> A lookup that fails leaves the capture silently
   * incomplete: the compile still runs, the capture is still written, and it is just missing what
   * the failed lookup would have supplied. That is exactly the failure nobody notices. It happened
   * here: the bridge looked the recorder's method up under one name and the recorder had another,
   * and the capture silently lost the sources the compiler really compiled.
   *
   * <p>So the failure is reported on the compiler's own stderr, once -- the lookups are cached, so
   * this runs at most once per method.
   */
  private static void reportLookupFailure(String method, Throwable cause) {
    System.err.println(
        "[plumb-capture] could not find "
            + RECORDER
            + "#"
            + method
            + " on the bootstrap loader: "
            + cause
            + " -- the capture will be incomplete");
  }

  /**
   * The recorder, as the instrumented code sees it.
   *
   * <p>The bootstrap loader holds the copy the instrumented code uses, so the state the bridge
   * touches is the state that matters.
   */
  private static Class<?> recorderClass() throws ClassNotFoundException {
    ClassLoader bootstrap = ClassLoader.getPlatformClassLoader().getParent();
    return Class.forName(RECORDER, false, bootstrap);
  }
}
