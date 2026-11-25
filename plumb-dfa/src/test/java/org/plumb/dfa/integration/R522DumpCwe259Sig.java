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
package org.plumb.dfa.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

/** R522 — dump TAC for a single CWE259 file to debug HardCodedPasswordSinkChecker. */
@Disabled(
    "This class DUMPS: it prints the CALL signatures TAC emits for a corpus file so the "
        + "sink catalogue can be checked by hand. It never tested a finding, so a green "
        + "here says nothing about the engine -- which is why it is not a CI guard. "
        + "The old reason (a TacProgram import drift) was false: the import is "
        + "org.plumb.tac.ir.TacProgram and that type exists. Run it explicitly when the "
        + "signatures are wanted.")
class R522DumpCwe259Sig {
  @Test
  void dumpFirstCwe321() throws Exception {
    Path p =
        Paths.get(
            "D:/work/tmp/Juliet_Java/src/testcases/CWE321_Hard_Coded_Cryptographic_Key/CWE321_Hard_Coded_Cryptographic_Key__basic_01.java");
    if (!Files.isRegularFile(p)) {
      System.out.println("File not found: " + p);
      return;
    }
    ParsedSource src = new JavaSourceParser().parse(p);
    TacProgram prog = new TacTranslator(src).translate();
    for (TacFunction fn : prog.functions()) {
      System.out.println("=== function: " + fn.signature() + " ===");
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL
            || ins.kind == TacInstruction.Kind.NEW
            || ins.kind == TacInstruction.Kind.MOVE) {
          System.out.println("  " + ins);
          if (ins.calleeSig != null) System.out.println("    calleeSig=" + ins.calleeSig);
          if (ins.kind == TacInstruction.Kind.MOVE) {
            System.out.println("    MOVE dest=" + ins.dest + " lhs1=" + ins.lhs1);
          }
          if (ins.kind == TacInstruction.Kind.CALL || ins.kind == TacInstruction.Kind.NEW) {
            System.out.println(
                "    " + ins.kind + " args.size=" + ins.args.size() + " args=" + ins.args);
          }
        }
      }
    }
  }

  @Test
  void dumpFirstCwe395() throws Exception {
    Path p =
        Paths.get(
            "D:/work/tmp/Juliet_Java/src/testcases/CWE395_Catch_NullPointerException/CWE395_Catch_NullPointerException__basic_01.java");
    if (!Files.isRegularFile(p)) {
      System.out.println("File not found: " + p);
      return;
    }
    ParsedSource src = new JavaSourceParser().parse(p);
    TacProgram prog = new TacTranslator(src).translate();
    for (TacFunction fn : prog.functions()) {
      System.out.println("=== function: " + fn.signature() + " ===");
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CATCH_HANDLER
            || ins.kind == TacInstruction.Kind.FINALLY_ENTER
            || ins.kind == TacInstruction.Kind.THROW) {
          System.out.println("  " + ins);
          System.out.println("    ctorType=" + ins.ctorType + " dest=" + ins.dest);
        }
      }
    }
  }

  @Test
  void dumpFirstCwe259() throws Exception {
    Path p =
        Paths.get(
            "D:/work/tmp/Juliet_Java/src/testcases/CWE259_Hard_Coded_Password/CWE259_Hard_Coded_Password__driverManager_01.java");
    if (!Files.isRegularFile(p)) {
      System.out.println("File not found: " + p);
      return;
    }
    ParsedSource src = new JavaSourceParser().parse(p);
    TacProgram prog = new TacTranslator(src).translate();
    for (TacFunction fn : prog.functions()) {
      System.out.println("=== function: " + fn.signature() + " ===");
      for (TacInstruction ins : fn.instructions()) {
        if (ins.kind == TacInstruction.Kind.CALL
            || ins.kind == TacInstruction.Kind.NEW
            || ins.kind == TacInstruction.Kind.MOVE) {
          System.out.println("  " + ins);
          if (ins.calleeSig != null) System.out.println("    calleeSig=" + ins.calleeSig);
          if (ins.kind == TacInstruction.Kind.MOVE) {
            System.out.println("    MOVE dest=" + ins.dest + " lhs1=" + ins.lhs1);
          }
          if (ins.kind == TacInstruction.Kind.CALL) {
            System.out.println("    CALL args.size=" + ins.args.size() + " args=" + ins.args);
          }
        }
      }
    }
  }
}
