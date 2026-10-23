package org.plumb.tac.bytecode;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * end-to-end smoke for the {@link BytecodeAnalyzer} + {@code JimpleToTacConverter} pipeline. The
 * test compiles a small Java file from the test resources into a {@code .class} on disk, then runs
 * {@code BytecodeAnalyzer.analyzeSingleClass} on it and asserts that the resulting {@link
 * TacProgram} has the expected shape (functions, instruction kinds, callee signatures).
 *
 * <p>This is the first round of integration testing for the bytecode path. It deliberately keeps
 * the assertions shape-level: we're not asserting exact instruction counts (those are fragile
 * across SootUp versions) but rather the kinds of instructions the existing DFA engine cares about
 * (CALL, NEW, RETURN, BRANCH, FIELDSTORE, etc.).
 */
class BytecodeAnalyzerTest {

  @Test
  void compilesR140DemoAndConvertsToTac(@TempDir Path tmp) throws IOException {
    // 1. Locate the R140Demo.java in test resources.
    Path src = locateR140Demo();
    assertThat(src).exists();

    // 2. Compile it to a .class in tmp. javac keeps the package
    //    structure, so the class lands under
    //    tmp/com/hellojava/sast/tac/bytecode/r140/R140Demo.class
    //    — we don't assert on the exact nested path, just that
    //    some R140Demo.class exists somewhere in tmp.
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            tmp.toString(),
            "-encoding",
            "UTF-8",
            src.toString());
    assertThat(rc).isEqualTo(0);
    assertThat(Files.walk(tmp).anyMatch(p -> p.getFileName().toString().equals("R140Demo.class")))
        .as("R140Demo.class should be somewhere under " + tmp)
        .isTrue();

    // 3. Run BytecodeAnalyzer on the parent directory.
    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    assertThat(programs).isNotEmpty();

    // 4. Find the R140Demo program. R140Demo is in the default
    //    (no) package, so the typeName is just "R140Demo".
    Optional<TacProgram> demoProgram =
        programs.stream().filter(p -> "R140Demo".equals(p.typeName())).findFirst();
    assertThat(demoProgram).isPresent();
    TacProgram program = demoProgram.get();

    // 5. Assert we have at least the obvious methods.
    assertThat(program.functions())
        .extracting(TacFunction::name)
        .contains("getSecret", "setSecret", "doubleIt", "buildMessage", "add", "asString", "main");

    // 6. `setSecret` should contain a FIELDSTORE for "secret".
    TacFunction setSecret = program.function("R140Demo.setSecret(1)");
    assertThat(setSecret).isNotNull();
    assertThat(setSecret.instructions())
        .anyMatch(i -> i.kind == TacInstruction.Kind.FIELDSTORE && "secret".equals(i.fieldName));

    // 7. `doubleIt` should contain a BINOP, a RETURN, and no CALL.
    TacFunction doubleIt = program.function("R140Demo.doubleIt(1)");
    assertThat(doubleIt).isNotNull();
    assertThat(doubleIt.instructions()).anyMatch(i -> i.kind == TacInstruction.Kind.BINOP);
    assertThat(doubleIt.instructions()).anyMatch(i -> i.kind == TacInstruction.Kind.RETURN);
    assertThat(doubleIt.instructions()).noneMatch(i -> i.kind == TacInstruction.Kind.CALL);

    // 8. `buildMessage` should have a NEW for StringBuilder and
    //    multiple CALLs (append).
    TacFunction buildMessage = program.function("R140Demo.buildMessage(1)");
    assertThat(buildMessage).isNotNull();
    assertThat(buildMessage.instructions()).anyMatch(i -> i.kind == TacInstruction.Kind.NEW);
    long callCount =
        buildMessage.instructions().stream()
            .filter(i -> i.kind == TacInstruction.Kind.CALL)
            .count();
    assertThat(callCount).isGreaterThanOrEqualTo(2);

    // 9. `add` is static — should have no `this` param.
    TacFunction add = program.function("R140Demo.add(2)");
    assertThat(add).isNotNull();
    assertThat(add.params()).extracting(p -> p.name()).doesNotContain("this");
    assertThat(add.instructions()).anyMatch(i -> i.kind == TacInstruction.Kind.BINOP);

    // 10. `asString` should contain a static CALL (String.valueOf).
    TacFunction asString = program.function("R140Demo.asString(1)");
    assertThat(asString).isNotNull();
    assertThat(asString.instructions())
        .anyMatch(
            i ->
                i.kind == TacInstruction.Kind.CALL
                    && i.calleeSig != null
                    && i.calleeSig.contains("String.valueOf"));
  }

  @Test
  void directoryWithNoClassFilesReturnsEmpty(@TempDir Path tmp) {
    // Empty directory → no programs (no FP, no crash).
    List<TacProgram> programs = new BytecodeAnalyzer(tmp).analyze();
    assertThat(programs).isEmpty();
  }

  @Test
  void analyzeSingleClassReturnsProgramForExistingClass(@TempDir Path tmp) throws IOException {
    Path src = locateR140Demo();
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    int rc =
        compiler.run(
            System.in,
            System.out,
            System.err,
            "-d",
            tmp.toString(),
            "-encoding",
            "UTF-8",
            src.toString());
    assertThat(rc).isEqualTo(0);
    // R140Demo is no-package → lands at tmp/R140Demo.class.
    Path classFile = tmp.resolve("R140Demo.class");
    assertThat(classFile).exists();

    Optional<TacProgram> opt = BytecodeAnalyzer.analyzeSingleClass(classFile);
    assertThat(opt).isPresent();
    assertThat(opt.get().typeName()).isEqualTo("R140Demo");
  }

  private static Path locateR140Demo() {
    // Test resources are on the classpath; resolve from cwd.
    Path fromCwd = Path.of("src", "test", "resources", "r140", "R140Demo.java");
    if (Files.exists(fromCwd)) return fromCwd.toAbsolutePath();
    // Try the resources root relative to maven.
    return Path.of("plumb-tac", "src", "test", "resources", "r140", "R140Demo.java")
        .toAbsolutePath();
  }
}
