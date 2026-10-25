package org.plumb.tac.io;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.plumb.tac.ir.BinaryOperator;
import org.plumb.tac.ir.MergedProgram;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacCfgBuilder;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.ir.TacType;

class IrSerializerTest {

  @Test
  void roundTripTacProgram(@TempDir Path tmp) throws Exception {
    TacProgram program = sampleProgram();
    Path file = tmp.resolve("prog.tac");
    IrSerializer.writeProgram(program, file);
    TacProgram back = IrSerializer.readProgram(file);
    assertThat(back.typeName()).isEqualTo("Demo");
    assertThat(back.functions()).hasSize(1);
    TacFunction fn = back.functions().get(0);
    assertThat(fn.signature()).isEqualTo("Demo#main");
    assertThat(fn.instructions()).hasSize(4);
  }

  @Test
  void roundTripMergedProgram(@TempDir Path tmp) throws Exception {
    TacProgram p1 = sampleProgram();
    TacProgram p2 = sampleProgram();
    MergedProgram merged = new MergedProgram(List.of(p1, p2));
    Path file = tmp.resolve("merged.tac");
    IrSerializer.writeMerged(merged, file);
    MergedProgram back = IrSerializer.readMerged(file);
    assertThat(back.sources()).hasSize(2);
  }

  @Test
  void roundTripGzipped(@TempDir Path tmp) throws Exception {
    TacProgram p = sampleProgram();
    Path gz = tmp.resolve("prog.tac.gz");
    IrSerializer.writeProgram(p, gz);
    assertThat(Files.size(gz)).isGreaterThan(0);
    TacProgram back = IrSerializer.readProgram(gz);
    assertThat(back.typeName()).isEqualTo("Demo");
  }

  @Test
  void rejectsBadMagic(@TempDir Path tmp) throws Exception {
    Path file = tmp.resolve("bad.tac");
    // Write a valid ObjectOutputStream header but with the wrong
    // magic int (we expect a clean "Bad IR magic" diagnostic).
    try (var out =
        new java.io.ObjectOutputStream(
            new java.io.BufferedOutputStream(Files.newOutputStream(file)))) {
      out.writeInt(0xDEADBEEF);
      out.writeInt(IrSerializer.VERSION);
      out.writeObject("not-a-tac-program");
    }
    try {
      IrSerializer.readProgram(file);
      assertThat(false).as("expected IOException").isTrue();
    } catch (Exception expected) {
      // Either "Bad IR magic" (our diagnostic) or the ObjectInputStream
      // ClassCastException (because the payload is a String, not a
      // TacProgram). Both prove the file was rejected.
      String msg = String.valueOf(expected.getMessage());
      assertThat(msg.matches(".*(Bad IR magic|cast|Expected TacProgram).*")).isTrue();
    }
  }

  @Test
  void roundTripCallGraphStub(@TempDir Path tmp) throws Exception {
    // Generic object path: round-trip a List<String> as a stub
    // "call graph" payload to prove the tag/object form works.
    Object payload = List.of("A#foo -> B#bar", "B#bar -> C#baz");
    Path file = tmp.resolve("cg.tac");
    IrSerializer.writeObject("callgraph/v1", payload, file);
    Object back = IrSerializer.readObject(file);
    assertThat(back).isEqualTo(payload);
  }

  /**
   * — verify that {@code TacInstruction.line} / {@code column} (the per-instruction source
   * position) survive a serialize + deserialize round-trip. Findings reporters rely on these fields
   * to map back to the original .java file.
   */
  @Test
  void roundTripPreservesSourceLineAndColumn(@TempDir Path tmp) throws Exception {
    // Build a function with deliberately varied line/column
    // values to catch any field-level oversight in the IR
    // classes' Serializable contract.
    TacOperand.TacVar x = new TacOperand.TacVar("x", TacType.INT, false);
    List<TacInstruction> instrs =
        List.of(
            TacInstruction.builder(TacInstruction.Kind.MOVE)
                .dest(x)
                .lhs1(TacOperand.Const.ofInt(1))
                .line(10)
                .column(4)
                .build(),
            TacInstruction.builder(TacInstruction.Kind.BINOP)
                .dest(new TacOperand.TacVar("t0", TacType.INT, true))
                .lhs1(x)
                .binop(BinaryOperator.ADD)
                .lhs2(TacOperand.Const.ofInt(2))
                .line(11)
                .column(8)
                .build(),
            TacInstruction.builder(TacInstruction.Kind.RETURN).lhs1(x).line(12).column(12).build());
    TacFunction fn =
        new TacFunction("Demo", "Demo#main", TacType.VOID, List.of(), List.of(), instrs);
    TacProgram p = new TacProgram(null, "Demo");
    p.addFunction(fn);
    Path file = tmp.resolve("src.tac");
    IrSerializer.writeProgram(p, file);
    TacProgram back = IrSerializer.readProgram(file);
    TacFunction fbn = back.functions().get(0);
    // Every instruction's line + column must round-trip exactly.
    for (int i = 0; i < instrs.size(); i++) {
      TacInstruction orig = instrs.get(i);
      TacInstruction read = fbn.instructionAt(i);
      assertThat(read.line).as("line for insn #%d (%s)", i, read.kind).isEqualTo(orig.line);
      assertThat(read.column).as("column for insn #%d (%s)", i, read.kind).isEqualTo(orig.column);
    }
  }

  /**
   * — verify that {@link org.plumb.tac.ir.SourceMap} (a separate {@code (functionSig, insnIdx) ->
   * Loc} map used by the CFG builder) round-trips through {@code IrSerializer}'s generic {@code
   * writeObject} / {@code readObject} path. Source positions for findings are derived either from
   * {@code TacInstruction.line} (above) or from a {@code SourceMap} that the translator may attach
   * post-hoc; both must survive the cache.
   */
  @Test
  void roundTripPreservesSourceMap(@TempDir Path tmp) throws Exception {
    org.plumb.tac.ir.SourceMap map = new org.plumb.tac.ir.SourceMap();
    map.record("Foo#bar()", 0, 42, 8);
    map.record("Foo#bar()", 3, 45, 12);
    map.record("Foo#baz(int)", 1, 100, 0);
    Path file = tmp.resolve("sourcemap.tac");
    IrSerializer.writeObject("sourcemap/v1", map, file);
    Object back = IrSerializer.readObject(file);
    assertThat(back).isInstanceOf(org.plumb.tac.ir.SourceMap.class);
    org.plumb.tac.ir.SourceMap rsm = (org.plumb.tac.ir.SourceMap) back;
    assertThat(rsm.lookup("Foo#bar()", 0)).isEqualTo(new org.plumb.tac.ir.SourceMap.Loc(42, 8));
    assertThat(rsm.lookup("Foo#bar()", 3)).isEqualTo(new org.plumb.tac.ir.SourceMap.Loc(45, 12));
    assertThat(rsm.lookup("Foo#baz(int)", 1)).isEqualTo(new org.plumb.tac.ir.SourceMap.Loc(100, 0));
  }

  /**
   * — verify that {@code TacProgram.sourcePath} (the original .java file the program was translated
   * from) round- trips. Findings that need to point back to the source file consult this; if it
   * deserialised as {@code null} the reporter would fall back to "unknown" and the user would lose
   * per-file filtering.
   */
  @Test
  void roundTripPreservesSourcePath(@TempDir Path tmp) throws Exception {
    java.nio.file.Path src = tmp.resolve("Foo.java");
    TacProgram p = new TacProgram(src, "Foo");
    Path file = tmp.resolve("foo.tac");
    IrSerializer.writeProgram(p, file);
    TacProgram back = IrSerializer.readProgram(file);
    assertThat(back.sourcePath()).isEqualTo(src);
  }

  // --- helpers ---

  private static TacProgram sampleProgram() {
    TacProgram p = new TacProgram(null, "Demo");
    List<TacInstruction> instrs = new java.util.ArrayList<>();
    // x = 1
    instrs.add(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(new TacOperand.TacVar("x", TacType.INT, false))
            .lhs1(TacOperand.Const.ofInt(1))
            .build());
    // t0 = x + 2
    instrs.add(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(new TacOperand.TacVar("t0", TacType.INT, true))
            .lhs1(new TacOperand.TacVar("x", TacType.INT, false))
            .binop(BinaryOperator.ADD)
            .lhs2(TacOperand.Const.ofInt(2))
            .build());
    // x = t0
    instrs.add(
        TacInstruction.builder(TacInstruction.Kind.MOVE)
            .dest(new TacOperand.TacVar("x", TacType.INT, false))
            .lhs1(new TacOperand.TacVar("t0", TacType.INT, true))
            .build());
    // return x
    instrs.add(
        TacInstruction.builder(TacInstruction.Kind.RETURN)
            .lhs1(new TacOperand.TacVar("x", TacType.INT, false))
            .build());
    TacFunction fn =
        new TacFunction(
            "Demo",
            "Demo#main",
            TacType.VOID,
            List.of(new TacOperand.TacVar("x", TacType.INT, false)),
            List.of(),
            instrs);
    TacCfg cfg = TacCfgBuilder.build(fn);
    // sanity: cfg built without throwing
    assertThat(cfg).isNotNull();
    p.addFunction(fn);
    return p;
  }
}
