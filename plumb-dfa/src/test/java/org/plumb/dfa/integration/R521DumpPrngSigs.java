package org.plumb.dfa.integration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.TreeSet;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;
import org.plumb.tac.translate.TacTranslator;

@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R521DumpPrngSigs {
  @Test
  void dumpCwe336() throws Exception {
    Path p =
        Paths.get(
            "D:/work/tmp/Juliet_Java/src/testcases/CWE336_Same_Seed_in_PRNG/CWE336_Same_Seed_in_PRNG__basic_01.java");
    if (!Files.isRegularFile(p)) {
      System.out.println("not found");
      return;
    }
    ParsedSource src = new JavaSourceParser().parse(p);
    TacProgram prog = new TacTranslator(src).translate();
    TreeSet<String> sigs = new TreeSet<>();
    for (TacFunction fn : prog.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.calleeSig != null
            && (ins.calleeSig.contains("setSeed")
                || ins.calleeSig.contains("Random")
                || ins.calleeSig.contains("nextInt"))) {
          sigs.add(ins.calleeSig);
        }
      }
    }
    sigs.forEach(System.out::println);
  }
}
