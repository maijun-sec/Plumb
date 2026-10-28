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
package org.plumb.build;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSystemRegistryTest {

  @Test
  void serviceLoaderFindsMavenAdapter() {
    BuildSystemRegistry registry = BuildSystemRegistry.get();
    // Maven is always on the classpath because plumb-build declares
    // MavenAdapter in META-INF/services. The test would fail to
    // compile if the registry wasn't pulling from ServiceLoader
    // (we'd see an empty list).
    assertThat(registry.adapters()).extracting(BuildAdapter::systemName).contains("maven");
  }

  @Test
  void byName_returnsRegisteredAdapter() {
    BuildAdapter a = BuildSystemRegistry.get().byName("maven");
    assertThat(a).isNotNull();
    assertThat(a.systemName()).isEqualTo("maven");
  }

  @Test
  void byName_returnsNullForUnknown() {
    assertThat(BuildSystemRegistry.get().byName("nonexistent")).isNull();
  }
}
