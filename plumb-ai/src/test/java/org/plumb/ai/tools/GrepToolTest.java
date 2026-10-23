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
 * R-? — Tests for the file-ops toolset (Grep / Glob / ListDir). These tools are IR-independent —
 * they survive {@code TacProgram == null}, so the FP reducer and bug scanner keep working even when
 * the static analyzer's IR layer is unavailable.
 */
class GrepToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("GrepToolTest");
        Files.writeString(
                tmpDir.resolve("A.java"),
                "package x;\n"
                        + "class A {\n"
                        + "  String password = \"P@ssw0rd\";\n"
                        + "  void f() { stmt.executeQuery(query); }\n"
                        + "  String query = \"SELECT *\";\n"
                        + "}\n");
        Files.createDirectory(tmpDir.resolve("sub"));
        Files.writeString(
                tmpDir.resolve("sub").resolve("B.java"),
                "package x.sub;\n" + "class B {\n" + "  void g() { stmt.executeQuery(\"x\"); }\n" + "}\n");
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
    void grepFindsMatchesInSingleFile() throws Exception {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "executeQuery");
        args.put("path", tmpDir.resolve("A.java").toString());
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains("executeQuery");
        assertThat(r.body()).contains("L4 in A.java");
    }

    @Test
    void grepRecursesIntoSubdirectories() {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "executeQuery");
        args.put("path", tmpDir.toString());
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        // Both A.java and sub/B.java contain executeQuery
        assertThat(r.body()).contains("A.java");
        assertThat(r.body()).contains("B.java");
    }

    @Test
    void grepSupportsIgnoreCase() {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "EXECUTEQUERY");
        args.put("path", tmpDir.toString());
        args.put("ignore_case", true);
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains("executeQuery");
    }

    @Test
    void grepRespectsMaxResults() {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "executeQuery");
        args.put("path", tmpDir.toString());
        args.put("max_results", 1);
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains("Found 1");
        assertThat(r.body()).contains("truncated");
    }

    @Test
    void grepRejectsInvalidRegex() {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "[unclosed");
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isFalse();
        assertThat(r.body()).contains("invalid regex");
    }

    @Test
    void grepSkipsBinaryFiles() throws Exception {
        // Write a binary file with NUL bytes. Grep should
        // skip it.
        Files.write(tmpDir.resolve("binary.dat"), new byte[]{0x00, 0x01, 0x02, 0x00});
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "executeQuery");
        args.put("path", tmpDir.toString());
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).doesNotContain("binary.dat");
    }

    @Test
    void grepReturnsErrorWhenPathMissing() {
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "x");
        args.put("path", "/nonexistent/path/that/does/not/exist");
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isFalse();
        assertThat(r.body()).contains("does not exist");
    }

    @Test
    void grepRespectsFileGlob() throws Exception {
        // Create a .txt file containing the pattern.
        Files.writeString(tmpDir.resolve("notes.txt"), "executeQuery mention here");
        GrepTool tool = new GrepTool();
        ToolContext ctx = ctx(tmpDir);
        ObjectNode args = MAPPER.createObjectNode();
        args.put("pattern", "executeQuery");
        args.put("path", tmpDir.toString());
        args.put("file_glob", "*.java");
        ToolResult r = tool.execute(ctx, args);
        assertThat(r.ok()).isTrue();
        assertThat(r.body()).contains(".java");
        assertThat(r.body()).doesNotContain("notes.txt");
    }

    private static ToolContext ctx(Path sourceRoot) {
        return new ToolContext(
                null,
                "test",
                sourceRoot.toString(),
                java.util.List.of(),
                java.util.List.of(),
                null,
                null,
                null);
    }
}
