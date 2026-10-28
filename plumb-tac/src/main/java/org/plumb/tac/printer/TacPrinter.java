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
package org.plumb.tac.printer;

import java.util.Locale;
import java.util.Map;
import org.plumb.tac.ir.TacCfg;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * Human-readable dump of a {@link TacProgram} / {@link TacFunction} / {@link TacCfg}. Used by the
 * CLI's {@code --print-tac} flag and by tests. Output is plain text; the format is intentionally
 * simple.
 */
public final class TacPrinter {

  private TacPrinter() {}

  public static String dump(TacProgram program) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== ").append(program.typeName()).append(" ===\n");
    for (TacFunction fn : program.functions()) {
      sb.append("\n").append(dump(fn));
    }
    if (program.hasProblems()) {
      sb.append("\n--- problems ---\n");
      program.programProblems().forEach(p -> sb.append(p).append('\n'));
    }
    return sb.toString();
  }

  public static String dump(TacFunction fn) {
    StringBuilder sb = new StringBuilder();
    sb.append("function ").append(fn.signature()).append('\n');
    sb.append("  params: ").append(fn.params()).append('\n');
    sb.append("  locals: ").append(fn.locals()).append('\n');
    sb.append("  instructions:\n");
    int i = 0;
    for (TacInstruction ins : fn.instructions()) {
      sb.append(String.format(Locale.ROOT, "    %4d  %s%n", i++, ins));
    }
    return sb.toString();
  }

  public static String dump(TacCfg cfg) {
    StringBuilder sb = new StringBuilder();
    sb.append("cfg ")
        .append(cfg.functionSig())
        .append(" entry=B")
        .append(cfg.entryId())
        .append('\n');
    for (var b : cfg.blocks()) {
      sb.append("  B").append(b.id()).append(" (insns ");
      sb.append(b.firstIndex()).append("-").append(b.lastIndex()).append("):\n");
    }
    sb.append("  succs:\n");
    for (Map.Entry<Integer, java.util.List<Integer>> e : cfg.successors().entrySet()) {
      sb.append("    B").append(e.getKey()).append(" -> ").append(e.getValue()).append('\n');
    }
    return sb.toString();
  }
}
