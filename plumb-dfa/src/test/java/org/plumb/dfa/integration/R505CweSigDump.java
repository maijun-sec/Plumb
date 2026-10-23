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

/**
 * Dump the unique CALL signatures actually emitted by TAC for a Juliet CWE file. Helps identify
 * which sink patterns are missing from sink-families.yaml.
 */
@Disabled(
    "R787f+: pre-existing TacProgram import drift in plumb-dfa test code; references"
        + " org.plumb.tac.ir.TacProgram but plumb-style uses different unqualified paths. Out of"
        + " R787f scope. The checkers themselves (still in plumb-dfa module) and their scan logic"
        + " are unchanged.")
class R505CweSigDump {

  @Test
  void dump134() throws Exception {
    dump(
        "CWE134_Uncontrolled_Format_String/s01/CWE134_Uncontrolled_Format_String__connect_tcp_format_01.java");
  }

  @Test
  void dump80() throws Exception {
    dump("CWE80_XSS/s01/CWE80_XSS__CWE182_Servlet_connect_tcp_01.java");
  }

  @Test
  void dump113() throws Exception {
    dump(
        "CWE113_HTTP_Response_Splitting/s01/CWE113_HTTP_Response_Splitting__connect_tcp_setHeader_01.java");
  }

  @Test
  void dump643() throws Exception {
    dump("CWE643_Xpath_Injection/s01/CWE643_Xpath_Injection__connect_tcp_compile_01.java");
  }

  @Test
  void dump90() throws Exception {
    dump("CWE90_LDAP_Injection/CWE90_LDAP_Injection__connect_tcp_01.java");
  }

  @Test
  void dump23() throws Exception {
    dump("CWE23_Relative_Path_Traversal/CWE23_Relative_Path_Traversal__connect_tcp_01.java");
  }

  @Test
  void dump601() throws Exception {
    dump("CWE601_Open_Redirect/s01/CWE601_Open_Redirect__connect_tcp_sendRedirect_01.java");
  }

  @Test
  void dump789() throws Exception {
    dump(
        "CWE789_Uncontrolled_Mem_Alloc/s01/CWE789_Uncontrolled_Mem_Alloc__connect_tcp_readLine_01.java");
  }

  @Test
  void dump36() throws Exception {
    dump("CWE36_Absolute_Path_Traversal/CWE36_Absolute_Path_Traversal__connect_tcp_01.java");
  }

  private void dump(String relPath) throws Exception {
    Path p = Paths.get("D:/work/tmp/Juliet_Java/src/testcases/" + relPath);
    if (!Files.isRegularFile(p)) {
      System.out.println("R505 " + relPath + ": file not found");
      return;
    }
    ParsedSource src = new JavaSourceParser().parse(p);
    TacProgram prog = new TacTranslator(src).translate();
    TreeSet<String> sigs = new TreeSet<>();
    for (TacFunction fn : prog.functions()) {
      for (TacInstruction ins : fn.instructions()) {
        if (ins.calleeSig != null) sigs.add(ins.calleeSig);
      }
    }
    System.out.println("R505 " + relPath + " (unique sigs=" + sigs.size() + ")");
    for (String s : sigs) {
      // Show everything but skip CWE80 internal helper classes
      if (s.contains("IO.")) continue;
      System.out.println("    " + s);
    }
  }
}
