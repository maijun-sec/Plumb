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
package org.plumb.ai.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.plumb.ai.agent.ToolResult;

/** — Tests for {@link GlobTool}. */
class GlobToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private Path tmpDir;

  @BeforeEach
  void setup() throws Exception {
    tmpDir = Files.createTempDirectory("GlobToolTest");
    Files.writeString(tmpDir.resolve("A.java"), "class A {}");
    Files.writeString(tmpDir.resolve("B.java"), "class B {}");
    Files.createDirectory(tmpDir.resolve("sub"));
    Files.writeString(tmpDir.resolve("sub").resolve("C.java"), "class C {}");
    Files.writeString(tmpDir.resolve("sub").resolve("D.txt"), "txt");
  }

  @AfterEach
  void teardown() throws Exception {
    if (tmpDir != null) {
      Files.walk(tmpDir)
          .sorted((a, b) -> b.toString().compareTo(a.toString()))
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (Exception ignore) {
                }
              });
    }
  }

  @Test
  void globFindsAllJavaFiles() {
    GlobTool tool = new GlobTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("pattern", "*.java");
    args.put("base_dir", tmpDir.toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("A.java");
    assertThat(r.body()).contains("B.java");
  }

  @Test
  void globRecursesWithDoubleStar() {
    GlobTool tool = new GlobTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("pattern", "**/*.java");
    args.put("base_dir", tmpDir.toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("A.java");
    assertThat(r.body()).contains("C.java");
  }

  @Test
  void globReturnsNoMatchesWhenNoneFound() {
    GlobTool tool = new GlobTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("pattern", "*.scala");
    args.put("base_dir", tmpDir.toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("no files matching");
  }

  @Test
  void globRespectsMaxResults() {
    GlobTool tool = new GlobTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("pattern", "*.java");
    args.put("base_dir", tmpDir.toString());
    args.put("max_results", 1);
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("Found 1");
  }

  private static ToolContext ctx() {
    return new ToolContext(
        null, "test", ".", java.util.List.of(), java.util.List.of(), null, null, null);
  }
}
