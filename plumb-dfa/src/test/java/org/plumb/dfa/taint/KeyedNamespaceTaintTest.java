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
import org.plumb.dfa.checkers.taint.DatabaseAccessChecker;
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
 * A keyed namespace: an entry's identity is the pair (object, key).
 *
 * <p><b>Why this needed its own machinery.</b> A subscript used to mean exactly one thing: an ARRAY
 * index, tracked by (variable, position). {@code a[0]} and {@code a[1]} were different slots, and a
 * symbolic index was a slot of its own. In a language whose subscripts name a namespace --
 * JavaScript's properties, Python's dict items, PHP's array entries -- the key is a STRING, and a
 * key the analysis cannot resolve stands for EVERY key. Without that, a write to {@code obj.userId}
 * was invisible to a read of {@code obj[anyKey]}, and the taint simply stopped at the write.
 *
 * <p><b>Why the last test is the one that matters most.</b> A tracker that treats every entry as
 * tainted passes the first three tests and is useless. So the control -- two DIFFERENT named
 * entries of the SAME object -- is asserted to stay clean. If it ever goes red, the tracker has
 * become "everything is tainted", and the other three tests prove nothing.
 *
 * <p><b>Why the instructions are written out by hand.</b> The point of these tests is the ENGINE's
 * answer, not the engine's input shape. A Java program cannot produce a keyed namespace -- Java has
 * no string-keyed subscript -- so the TAC is written directly, in the shape a JavaScript front end
 * will emit.
 */
@DisplayName("Taint: a keyed namespace tracks entries, not whole objects")
class KeyedNamespaceTaintTest {

  /** The taint source: a call the engine's source catalogue recognises. */
  private static final String SOURCE_CALL = "fs.readLineSync/1";

  /** The sink: a shared family, so it applies to every language. */
  private static final String SINK_CALL = "java.sql.Statement.execute/1";

  private static final String RULE_ID = "dfa-taint-database-access";

  @Test
  @DisplayName("a write to a named entry is seen through a key the analysis cannot resolve")
  void aNamedWriteIsSeenThroughAnUnresolvedKey() {
    List<Finding> findings = scan(namedWriteThenUnresolvedRead());

    assertThat(findings)
        .as(
            "obj.userId = tainted; x = obj[anyKey] -- the read's key might be \"userId\", so the"
                + " taint written to the named entry has to reach it")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a write through a key the analysis cannot resolve covers every entry")
  void aWriteThroughAnUnresolvedKeyCoversEveryEntry() {
    List<Finding> findings = scan(unresolvedWriteThenNamedRead());

    assertThat(findings)
        .as(
            "obj[dynamicKey] = tainted; x = obj.userId -- the written key might be \"userId\", so"
                + " every entry of the object may carry the taint")
        .isNotEmpty();
  }

  @Test
  @DisplayName("two allocations of one class are two locations")
  void twoAllocationsAreTwoLocations() {
    List<Finding> findings = scan(writeOnOneAllocationReadOnAnother());

    assertThat(findings)
        .as(
            "a.userId = tainted; x = b.userId -- the two objects were allocated at different"
                + " sites, so the taint written on one of them must not be read off the other")
        .isEmpty();
  }

  @Test
  @DisplayName("two different named entries of one object are two locations")
  void twoDifferentNamedEntriesAreTwoLocations() {
    List<Finding> findings = scan(writeOneEntryReadAnother());

    assertThat(findings)
        .as(
            "obj.userId = tainted; x = obj.role -- the entries are different locations, and a"
                + " tracker that reports this one has become \"everything is tainted\"")
        .isEmpty();
  }

  // ---- the programs ---------------------------------------------------------------------------

  /**
   * {@code input := read(); obj := {}; obj.userId = input; x := obj[anyKey]; sink(x)}
   *
   * <p>The write names its entry; the read does not name one at all.
   */
  private static List<TacInstruction> namedWriteThenUnresolvedRead() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("input", TacType.STRING);
    TacOperand.TacVar obj = var("obj", TacType.ofClass("Object"));
    out.add(call(SOURCE_CALL, input, List.of(str("fs")), 10));
    out.add(newObject(temp("t_obj"), "Object", 11));
    out.add(move(obj, temp("t_obj"), 11));
    out.add(keyedStore(obj, str("userId"), input, 12));
    out.add(keyedLoad(temp("t_x"), obj, var("anyKey", TacType.STRING), 13));
    sink(out, temp("t_x"), 14);
    return out;
  }

  /**
   * {@code input := read(); obj := {}; obj[dynamicKey] = input; x := obj.userId; sink(x)}
   *
   * <p>The write does not name its entry; the read does.
   */
  private static List<TacInstruction> unresolvedWriteThenNamedRead() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("input", TacType.STRING);
    TacOperand.TacVar obj = var("obj", TacType.ofClass("Object"));
    out.add(call(SOURCE_CALL, input, List.of(str("fs")), 10));
    out.add(newObject(temp("t_obj"), "Object", 11));
    out.add(move(obj, temp("t_obj"), 11));
    out.add(keyedStore(obj, var("dynamicKey", TacType.STRING), input, 12));
    out.add(keyedLoad(temp("t_x"), obj, str("userId"), 13));
    sink(out, temp("t_x"), 14);
    return out;
  }

  /**
   * {@code input := read(); a := {}; b := {}; a.userId = input; x := b.userId; sink(x)}
   *
   * <p>Two allocations of the same type, at different sites.
   */
  private static List<TacInstruction> writeOnOneAllocationReadOnAnother() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("input", TacType.STRING);
    TacOperand.TacVar a = var("a", TacType.ofClass("Object"));
    TacOperand.TacVar b = var("b", TacType.ofClass("Object"));
    out.add(call(SOURCE_CALL, input, List.of(str("fs")), 10));
    out.add(newObject(temp("t_a"), "Object", 11));
    out.add(move(a, temp("t_a"), 11));
    out.add(newObject(temp("t_b"), "Object", 12));
    out.add(move(b, temp("t_b"), 12));
    out.add(keyedStore(a, str("userId"), input, 13));
    out.add(keyedLoad(temp("t_x"), b, str("userId"), 14));
    sink(out, temp("t_x"), 15);
    return out;
  }

  /**
   * {@code input := read(); obj := {}; obj.userId = input; x := obj.role; sink(x)}
   *
   * <p>Two named entries of ONE object.
   */
  private static List<TacInstruction> writeOneEntryReadAnother() {
    List<TacInstruction> out = new ArrayList<>();
    TacOperand.TacVar input = var("input", TacType.STRING);
    TacOperand.TacVar obj = var("obj", TacType.ofClass("Object"));
    out.add(call(SOURCE_CALL, input, List.of(str("fs")), 10));
    out.add(newObject(temp("t_obj"), "Object", 11));
    out.add(move(obj, temp("t_obj"), 11));
    out.add(keyedStore(obj, str("userId"), input, 12));
    out.add(keyedLoad(temp("t_x"), obj, str("role"), 13));
    sink(out, temp("t_x"), 14);
    return out;
  }

  // ---- the harness ---------------------------------------------------------------------------

  private static List<Finding> scan(List<TacInstruction> instructions) {
    TacProgram program = new TacProgram(Path.of("app.js"), "app");
    program.addFunction(jsFunction(instructions));
    EngineConfig config = ConfigLoader.loadFromClasspath();
    FindingReporter reporter =
        new DfaEngine(config, List.of(new DatabaseAccessChecker()), "global", null)
            .runWithGlobalStore(List.of(program), 0);
    List<Finding> out = new ArrayList<>();
    for (Finding f : reporter.all()) {
      if (f.ruleId().startsWith("dfa-taint-")) out.add(f);
    }
    return out;
  }

  /** A JavaScript function: the language marker is what turns on the keyed semantics. */
  private static TacFunction jsFunction(List<TacInstruction> instructions) {
    List<TacOperand.TacVar> locals = new ArrayList<>();
    for (TacInstruction ins : instructions) {
      for (TacOperand op : new TacOperand[] {ins.dest, ins.lhs1, ins.lhs2}) {
        if (op instanceof TacOperand.TacVar v && !v.isTemp() && !locals.contains(v)) {
          locals.add(v);
        }
      }
    }
    return new TacFunction(
        "handler",
        "handler():void",
        TacType.VOID,
        List.of(),
        locals,
        instructions,
        List.of(),
        List.of(),
        Map.of(),
        Map.of(),
        null,
        SourceOrigin.SOURCE,
        List.of(),
        SourceLanguage.JAVASCRIPT,
        IrPrecision.SOURCE_FULL);
  }

  // ---- the instruction builders ----------------------------------------------------------------

  private static TacOperand.TacVar var(String name, TacType type) {
    return new TacOperand.TacVar(name, type, false);
  }

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

  private static TacInstruction newObject(TacOperand dest, String type, int line) {
    return TacInstruction.builder(TacInstruction.Kind.NEW)
        .dest(dest)
        .ctorType(TacType.ofClass(type))
        .args(List.of())
        .line(line)
        .column(1)
        .build();
  }

  private static TacInstruction move(TacOperand dest, TacOperand src, int line) {
    return TacInstruction.builder(TacInstruction.Kind.MOVE)
        .dest(dest)
        .lhs1(src)
        .line(line)
        .column(1)
        .build();
  }

  /** A write into a keyed namespace: {@code receiver[key] = value}. */
  private static TacInstruction keyedStore(
      TacOperand receiver, TacOperand key, TacOperand value, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYSTORE)
        .lhs1(receiver)
        .lhs2(key)
        .dest(value)
        .line(line)
        .column(1)
        .build();
  }

  /** A read from a keyed namespace: {@code dest = receiver[key]}. */
  private static TacInstruction keyedLoad(
      TacOperand dest, TacOperand receiver, TacOperand key, int line) {
    return TacInstruction.builder(TacInstruction.Kind.ARRAYLOAD)
        .dest(dest)
        .lhs1(receiver)
        .lhs2(key)
        .line(line)
        .column(1)
        .build();
  }

  /** The tainted value reaching the sink: {@code "SELECT " + value}, then the sink call. */
  private static void sink(List<TacInstruction> out, TacOperand value, int line) {
    TacOperand.TacVar query = temp("t_query");
    out.add(
        TacInstruction.builder(TacInstruction.Kind.BINOP)
            .dest(query)
            .lhs1(str("SELECT * FROM t WHERE x = '"))
            .lhs2(value)
            .binop(BinaryOperator.ADD)
            .line(line)
            .column(1)
            .build());
    // The sink's argument index is 0 in the catalogue, which is args[1] in the TAC: the
    // receiver occupies the receiver slot.
    out.add(call(SINK_CALL, temp("t_result"), List.of(var("stmt", TacType.UNKNOWN), query), line));
  }
}
