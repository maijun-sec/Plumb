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
package org.plumb.dfa.taint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.dfa.checkers.taint.CommandInjectionChecker;
import org.plumb.dfa.checkers.taint.FormatStringChecker;
import org.plumb.dfa.checkers.taint.LogInjectionChecker;
import org.plumb.dfa.checkers.taint.PathTraversalChecker;
import org.plumb.dfa.checkers.taint.SqlInjectionChecker;
import org.plumb.dfa.config.ConfigLoader;
import org.plumb.dfa.config.EngineConfig;
import org.plumb.dfa.engine.DfaEngine;
import org.plumb.dfa.engine.FindingReporter;
import org.plumb.reporter.Finding;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.IrPrecision;
import org.plumb.tac.ir.SourceLanguage;
import org.plumb.tac.ir.SourceOrigin;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

/**
 * The Go sink catalogue, and the shape the Go front end actually produces.
 *
 * <p><b>Why the instructions are written out by hand.</b> The catalogue names callee signatures,
 * and a signature that the front end never emits is a pattern that never fires. The instructions
 * below are not invented: each one is the TAC the Go front end produced for the corresponding Go
 * source, read back out of the TAC store after a capture of a probe program that calls it.
 *
 * <p><b>Why that matters for the arguments.</b> A Go package-level function has no receiver object,
 * so the front end puts the PACKAGE'S IMPORT PATH at args[0] as a constant, and the first real
 * argument lands at args[1]. A method call puts the receiver there instead. Either way the
 * catalogue's {@code arg_index} -- the user-visible one -- maps to args[N + 1], which is the same
 * convention the JVM languages use. A Go function whose last parameter is variadic counts it ONCE
 * in the arity but gives each variadic value its own slot, which is why the command table names a
 * span of positions.
 *
 * <p><b>What the test protects.</b> That a Go call site is classified by the same catalogue the
 * other languages use, that the argument index points at the tainted value rather than at the
 * format string or the package constant next to it, and that the Go containers carry their
 * contents' taint in both directions.
 */
@DisplayName("Taint: the Go sink catalogue fires on the shapes the Go front end emits")
class GoSinkCatalogTest {

  private static final TacOperand.TacVar INPUT =
      new TacOperand.TacVar("input", TacType.STRING, false);
  private static final TacOperand.TacVar DB = new TacOperand.TacVar("db", TacType.UNKNOWN, false);
  private static final TacOperand.TacVar M = new TacOperand.TacVar("m", TacType.UNKNOWN, false);
  private static final TacOperand.TacVar CH = new TacOperand.TacVar("ch", TacType.UNKNOWN, false);
  private static final TacOperand.TacVar SB = new TacOperand.TacVar("sb", TacType.UNKNOWN, false);

  private static TacOperand.TacVar temp(String name) {
    return new TacOperand.TacVar(name, TacType.UNKNOWN, true);
  }

  private static TacOperand.Const str(String value) {
    return TacOperand.Const.ofString(value);
  }

  private static TacInstruction call(
      String callee, TacOperand dest, List<TacOperand> args, int line) {
    return TacInstruction.builder(TacInstruction.Kind.CALL)
        .calleeSig(callee)
        .dest(dest)
        .args(args)
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction add(TacOperand dest, TacOperand left, TacOperand right, int line) {
    return TacInstruction.builder(TacInstruction.Kind.BINOP)
        .dest(dest)
        .lhs1(left)
        .lhs2(right)
        .binop(BinaryOperator.ADD)
        .line(line)
        .column(1)
        .build();
  }

  /**
   * The Go program, as the front end lowers it.
   *
   * @return the function under analysis
   */
  private static TacFunction goMain() {
    TacOperand.TacVar t2 = temp("t2");
    TacOperand.TacVar t7 = temp("t7");
    TacOperand.TacVar t9 = temp("t9");
    TacOperand.TacVar t18 = temp("t18");
    TacOperand.TacVar t22 = temp("t22");
    TacOperand.TacVar t26 = temp("t26");
    TacOperand.TacVar built = new TacOperand.TacVar("built", TacType.UNKNOWN, false);

    List<TacInstruction> instructions = new ArrayList<>();
    // input := os.Getenv("HOME")
    instructions.add(call("os.Getenv/1", INPUT, List.of(str("os"), str("HOME")), 14));
    // db.Exec("SELECT ... '" + input + "'")
    instructions.add(add(t2, str("SELECT * FROM t WHERE x = '"), INPUT, 18));
    instructions.add(call("database/sql.DB.Exec/2", temp("t3"), List.of(DB, t2), 18));
    // exec.Command("sh", "-c", "echo "+input)
    instructions.add(add(t7, str("echo "), INPUT, 22));
    instructions.add(
        call(
            "os/exec.Command/2",
            temp("t8"),
            List.of(str("os/exec"), str("sh"), str("-c"), t7),
            22));
    // os.Open("/data/" + input)
    instructions.add(add(t9, str("/data/"), INPUT, 25));
    instructions.add(call("os.Open/1", temp("t10"), List.of(str("os"), t9), 25));
    // log.Printf("user=%s", input)
    instructions.add(
        call("log.Printf/2", temp("t13"), List.of(str("log"), str("user=%s"), INPUT), 29));
    // fmt.Sprintf(input)
    instructions.add(call("fmt.Sprintf/2", temp("t14"), List.of(str("fmt"), INPUT), 32));
    // m["k"] = input; out := m["k"]
    instructions.add(call("<go>.mapStore/3", null, List.of(M, str("k"), INPUT), 39));
    instructions.add(call("<go>.mapIndex/2", t18, List.of(M, str("k")), 40));
    // ch <- input; got := <-ch
    instructions.add(call("<go>.chanSend/2", null, List.of(CH, INPUT), 45));
    instructions.add(call("<go>.chanRecv/1", t22, List.of(CH), 46));
    // sb.WriteString(input); built := sb.String()
    instructions.add(call("strings.Builder.WriteString/1", t26, List.of(SB, INPUT), 51));
    instructions.add(call("strings.Builder.String/0", built, List.of(SB), 52));
    return new TacFunction(
        "main",
        "main.main():void",
        TacType.VOID,
        List.of(),
        List.of(INPUT, DB, M, CH, SB, built),
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.GO,
        IrPrecision.SOURCE_FULL);
  }

  private List<Finding> scan() {
    TacProgram program = new TacProgram(Path.of("main.go"), "main");
    program.addFunction(goMain());
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(
                config,
                List.of(
                    new SqlInjectionChecker(),
                    new CommandInjectionChecker(),
                    new PathTraversalChecker(),
                    new LogInjectionChecker(),
                    new FormatStringChecker()),
                "global",
                null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  private static boolean hasFinding(List<Finding> findings, String ruleId, String callee) {
    for (Finding f : findings) {
      if (!f.ruleId().equals(ruleId)) continue;
      if (f.message().contains(callee)) return true;
    }
    return false;
  }

  @Test
  @DisplayName("a Go SQL sink is classified by the catalogue")
  void aGoSqlSinkIsClassifiedByTheCatalogue() {
    List<Finding> findings = scan();

    assertThat(hasFinding(findings, "dfa-taint-sql-injection", "database/sql.DB.Exec/2"))
        .as("the Go query call must be recognised as a SQL sink")
        .isTrue();
  }

  @Test
  @DisplayName("a Go command sink is classified, and the VARIADIC argument is the tainted one")
  void aGoCommandSinkIsClassified() {
    List<Finding> findings = scan();

    assertThat(hasFinding(findings, "dfa-taint-command-injection", "os/exec.Command/2"))
        .as("the Go command call must be recognised as a command sink")
        .isTrue();
  }

  @Test
  @DisplayName("a Go path sink is classified")
  void aGoPathSinkIsClassified() {
    List<Finding> findings = scan();

    assertThat(hasFinding(findings, "dfa-taint-path-traversal", "os.Open/1"))
        .as("the Go open call must be recognised as a path sink")
        .isTrue();
  }

  @Test
  @DisplayName("a Go log sink is classified, and the VALUE argument is the tainted one")
  void aGoLogSinkIsClassified() {
    List<Finding> findings = scan();

    assertThat(hasFinding(findings, "dfa-taint-log-injection", "log.Printf/2"))
        .as("the Go log call must be recognised as a log sink")
        .isTrue();
  }

  @Test
  @DisplayName("a Go format-string sink is classified, and the FORMAT argument is the tainted one")
  void aGoFormatStringSinkIsClassified() {
    List<Finding> findings = scan();

    assertThat(hasFinding(findings, "dfa-taint-format-string", "fmt.Sprintf/2"))
        .as("the Go format call must be recognised as a format-string sink")
        .isTrue();
  }

  @Test
  @DisplayName("a Go map carries the taint of what was written into it, in both directions")
  void aGoMapCarriesTheTaintInBothDirections() {
    // The write is a mutator: the value taints the map. The read is an
    // accumulator read: the map's taint comes back out. Without the write
    // direction the map stays clean; without the read direction the value
    // read back out is clean.
    assertThat(AccumulatorCatalog.isMutator("<go>.mapStore/3"))
        .as("a map write writes into the map")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("<go>.mapIndex/2"))
        .as("a map read reads the map's state")
        .isTrue();
    // The KEY indexes the map; it is not what the map holds. A tainted key
    // must not make every later read of the map look tainted.
    TacInstruction store =
        TacInstruction.builder(TacInstruction.Kind.CALL)
            .calleeSig("<go>.mapStore/3")
            .args(List.of(M, str("k"), INPUT))
            .build();
    assertThat(AccumulatorCatalog.mutatorArgIndices(store))
        .as("only the value is part of the map's state")
        .containsExactly(2);
  }

  @Test
  @DisplayName("a Go channel carries the taint of what was sent into it, in both directions")
  void aGoChannelCarriesTheTaintInBothDirections() {
    assertThat(AccumulatorCatalog.isMutator("<go>.chanSend/2"))
        .as("a channel send writes into the channel")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("<go>.chanRecv/1"))
        .as("a channel receive reads the channel's state")
        .isTrue();
  }

  @Test
  @DisplayName("a Go builder carries the taint of what was written into it, in both directions")
  void aGoBuilderCarriesTheTaintInBothDirections() {
    assertThat(AccumulatorCatalog.isMutator("strings.Builder.WriteString/1"))
        .as("a builder's write writes into the builder")
        .isTrue();
    assertThat(AccumulatorCatalog.isAccumulatorRead("strings.Builder.String/0"))
        .as("a builder's String hands back what the builder holds")
        .isTrue();
  }

  @Test
  @DisplayName("the Go table names the symbols the Go front end emits")
  void theGoTableNamesTheSymbolsTheFrontEndEmits() {
    EngineConfig config = ConfigLoader.loadFromClasspath();

    // Every one of these is a callee signature read out of the TAC the Go
    // front end produced. A pattern that names a symbol the front end cannot
    // produce is a pattern that never fires.
    assertThat(
            config
                .sinkFamilies()
                .byName("sql.sinks")
                .orElseThrow()
                .matches("database/sql.DB.Exec/2", SourceLanguage.GO))
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("command.sinks")
                .orElseThrow()
                .matches("os/exec.Command/2", SourceLanguage.GO))
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("file-path.sinks")
                .orElseThrow()
                .matches("os.Open/1", SourceLanguage.GO))
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("log.sinks")
                .orElseThrow()
                .matches("log.Printf/2", SourceLanguage.GO))
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("format-string.sinks")
                .orElseThrow()
                .matches("fmt.Sprintf/2", SourceLanguage.GO))
        .isTrue();
    assertThat(
            config
                .sinkFamilies()
                .byName("taint.sources.env")
                .orElseThrow()
                .matches("os.Getenv/1", SourceLanguage.GO))
        .as("the environment is a Go source")
        .isTrue();
  }
}
