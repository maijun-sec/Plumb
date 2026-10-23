package org.plumb.ai.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.plumb.ai.agent.ToolResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R-? — Tests for {@link GlobTool}.
 */
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
