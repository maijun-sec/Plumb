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

/** — Tests for {@link ListDirTool}. */
class ListDirToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private Path tmpDir;

  @BeforeEach
  void setup() throws Exception {
    tmpDir = Files.createTempDirectory("ListDirToolTest");
    Files.writeString(tmpDir.resolve("A.java"), "x");
    Files.writeString(tmpDir.resolve("B.txt"), "y");
    Files.createDirectory(tmpDir.resolve("sub"));
    Files.writeString(tmpDir.resolve("sub").resolve("C.java"), "z");
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
  void listDirAtDepth0ReturnsImmediateChildren() {
    ListDirTool tool = new ListDirTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("path", tmpDir.toString());
    args.put("max_depth", 0);
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("[D] sub");
    assertThat(r.body()).contains("[F] A.java");
    assertThat(r.body()).contains("[F] B.txt");
    assertThat(r.body()).doesNotContain("[F] C.java");
  }

  @Test
  void listDirAtDepth1RecursesOnce() {
    ListDirTool tool = new ListDirTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("path", tmpDir.toString());
    args.put("max_depth", 1);
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).contains("[F] C.java");
  }

  @Test
  void listDirSkipsBuildDirectory() throws Exception {
    Files.createDirectory(tmpDir.resolve("build"));
    Files.writeString(tmpDir.resolve("build").resolve("noise.class"), "x");
    ListDirTool tool = new ListDirTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("path", tmpDir.toString());
    args.put("max_depth", 1);
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isTrue();
    assertThat(r.body()).doesNotContain("build");
  }

  @Test
  void listDirRejectsNonDirectory() {
    ListDirTool tool = new ListDirTool();
    ObjectNode args = MAPPER.createObjectNode();
    args.put("path", tmpDir.resolve("A.java").toString());
    ToolResult r = tool.execute(ctx(), args);
    assertThat(r.ok()).isFalse();
    assertThat(r.body()).contains("not a directory");
  }

  private static ToolContext ctx() {
    return new ToolContext(
        null, "test", ".", java.util.List.of(), java.util.List.of(), null, null, null);
  }
}
