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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Which class loader holds the recorder?
 *
 * <p><b>Why this test exists.</b> The agent jar is appended to the <em>bootstrap</em> class loader,
 * so there are two copies of {@code CaptureRecorder}: one on the bootstrap loader, and one on the
 * agent's own loader. The advice calls whichever its own loader resolves, and the instrumented
 * compiler code calls the bootstrap one.
 *
 * <p>Those are different classes, and they do not share state. A value remembered by one is
 * invisible to the other -- so a hook that remembers something in the agent's copy and reads it
 * back from the bootstrap copy finds nothing, and the failure is silent.
 *
 * <p>That is exactly what happened when the capture was taught to record the sources the compiler
 * found on the source path: the advice remembered them, and the capture came out without them. The
 * bridge exists to keep both sides on the same loader, and this test is what keeps the bridge
 * honest.
 */
class LoaderProbeTest {

  /** The recorder's name, as the two loaders see it. */
  private static final String RECORDER = "org.plumb.build.capture.CaptureRecorder";

  @Test
  void theRecorderIsOnTheBootstrapLoaderAndTheAgentHasItsOwnCopy() throws Exception {
    // The bootstrap copy only exists once the agent has injected its jar,
    // so the agent is installed here rather than relying on another test
    // having done it first.
    java.lang.instrument.Instrumentation inst = net.bytebuddy.agent.ByteBuddyAgent.install();
    CaptureAgent.install(inst);

    // The bootstrap loader is the platform loader's parent.
    ClassLoader bootstrap = ClassLoader.getPlatformClassLoader().getParent();
    Class<?> bootstrapCopy = Class.forName(RECORDER, false, bootstrap);

    assertThat(bootstrapCopy.getClassLoader())
        .as("the bootstrap copy must have no loader of its own")
        .isNull();

    // The agent's own copy is the one whose code source is the module's own
    // output directory. It cannot be named through a normal lookup: every
    // loader delegates to the bootstrap loader first, so a lookup finds the
    // bootstrap copy and never this one.
    //
    // <p>The two copies are told apart by their code source: a class that was
    // appended to the bootstrap loader has no code source at all, while the
    // module's own copy has the module's output directory.
    assertThat(bootstrapCopy.getProtectionDomain().getCodeSource())
        .as("a class appended to the bootstrap loader has no code source")
        .isNull();
    assertThat(CaptureAgent.class.getProtectionDomain().getCodeSource())
        .as("the agent's own copy must have one")
        .isNotNull();
  }

  @Test
  void theBootstrapJarHoldsTheRecorderAndItsWholeClosure() throws Exception {
    File jar = CaptureAgent.buildRecorderJarForTest();
    assertThat(jar).as("the recorder jar must be built").isNotNull();
    StringBuilder names = new StringBuilder();
    try (JarFile jf = new JarFile(jar)) {
      java.util.Enumeration<JarEntry> entries = jf.entries();
      while (entries.hasMoreElements()) {
        names.append(entries.nextElement().getName()).append(' ');
      }
    }
    String contents = names.toString();
    assertThat(contents)
        .as("the bridge the advice calls must be on the bootstrap loader")
        .contains("org/plumb/build/capture/CompiledSourcesBridge.class");
    assertThat(contents)
        .as("the recorder's nested record is a class of its own")
        .contains("org/plumb/build/capture/CaptureRecorder$PendingCompile.class");
  }
}
