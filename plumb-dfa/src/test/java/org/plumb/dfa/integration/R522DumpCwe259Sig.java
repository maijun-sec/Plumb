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
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
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
