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

import org.junit.jupiter.api.Assumptions;
import org.plumb.tac.frontend.csharp.CSharpSidecarClient;

/**
 * The C# sidecar is an ENVIRONMENT requirement, not a product defect.
 *
 * <p><b>Why the test skips instead of failing.</b> The C# front end is only half a program: the
 * other half is a .NET sidecar that Roslyn runs in. That sidecar is not in version control and the
 * Java build does not produce it -- the .NET toolchain builds it, once. So a machine without the
 * .NET toolchain cannot answer the question these tests ask, and a FAILURE there would say
 * something false about the product: it would blame the C# front end for a missing build step.
 *
 * <p><b>Why the skip message has to say what to do.</b> A skip that only says "the sidecar is
 * missing" reads like a pass: the build is green and the C# language was never analysed. So the
 * message names the command that produces the sidecar. The same sentence is used by the frontend
 * itself, so the advice a reader gets does not depend on which door they came through.
 *
 * <p><b>Skip is not pass.</b> A skipped test is a test that did not run. The end-to-end proof that
 * the C# front end reaches the analysis exists only when the sidecar is built.
 */
final class CSharpSidecarSupport {

  private CSharpSidecarSupport() {}

  /**
   * Skip the calling test when the C# sidecar is not on this machine.
   *
   * @throws org.opentest4j.TestAbortedException when the sidecar is absent
   */
  static void assumeSidecarAvailable() {
    Assumptions.assumeTrue(
        CSharpSidecarClient.locateExecutable(null) != null, CSharpSidecarClient.NOT_BUILT);
  }
}
