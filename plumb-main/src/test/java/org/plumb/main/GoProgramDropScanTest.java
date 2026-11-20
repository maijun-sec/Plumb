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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.frontend.go.GoProjectDiscovery;

/**
 * A loss the sidecar recorded has to reach the coverage report.
 *
 * <h2>Why this is the whole chain</h2>
 *
 * <p>The program-level drops are the losses that belong to the WHOLE program: a build fact the
 * front end knows and no function owns. The sidecar records them in the response's top-level array;
 * the Java side used to read each function's own drop array and stop there, so they were thrown
 * away on the way in.
 *
 * <p>This test walks the chain the whole way: a Go module with an embedded resource, scanned the
 * way a user scans it, and the coverage report that comes out. The sidecar has to record the loss,
 * the wire has to carry it, the front end has to read it, the scan has to put it into the IR, and
 * the report has to group it by name.
 *
 * <p><b>Why it is worth a slow test.</b> Every other test of this change pins one link. This one
 * pins the chain: a link that is missing anywhere shows up as a report that does not mention the
 * loss.
 */
@DisplayName("Scan: a program-level loss reaches the coverage report")
class GoProgramDropScanTest {

  @Test
  @DisplayName("an embedded resource's loss is named in the coverage report")
  void anEmbeddedResourcesLossIsNamedInTheCoverageReport(@TempDir Path directory) throws Exception {
    Path module = directory.resolve("demo");
    Files.createDirectories(module);
    Files.writeString(module.resolve("go.mod"), "module example.com/demo\n\ngo 1.21\n");
    Files.writeString(module.resolve("greeting.txt"), "hello\n");
    Path source = module.resolve("main.go");
    Files.writeString(
        source,
        """
        package demo

        import _ "embed"

        //go:embed greeting.txt
        var greeting string

        func work() {
        }
        """);

    assumeTrue(
        GoProjectDiscovery.toolchainRootOf() != null,
        "the Go front end needs a Go toolchain to resolve the project's types: install Go"
            + " (https://go.dev/dl) and put `go` on the PATH");

    Path outFile = directory.resolve("out.ndjson");
    ScanConfig cfg = new ScanConfig();
    cfg.inputs.add(source);
    cfg.outputPath = outFile;
    cfg.outputFormat = ScanConfig.OutputFormat.NDJSON;

    new ScanRunner(cfg).run();

    Path coverageFile = directory.resolve("out.coverage.json");
    assertThat(coverageFile).as("the scan writes the coverage beside the findings").exists();
    String coverage = Files.readString(coverageFile);
    assertThat(coverage)
        .as("the loss the sidecar recorded has to be named in the report")
        .contains("\"go.embed.value\":1");
    assertThat(coverage)
        .as("and it is a program-level loss, listed in its own group")
        .contains("\"programDrops\":{");
  }
}
