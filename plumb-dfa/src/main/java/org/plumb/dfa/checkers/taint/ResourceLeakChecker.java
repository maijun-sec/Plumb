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
/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.engine.AnalysisContext
 *  org.plumb.dfa.summary.FunctionSummary
 *  org.plumb.dfa.taint.PathSensitiveCheckerBase
 *  org.plumb.tac.ir.TacFunction
 *  org.plumb.tac.ir.TacInstruction
 *  org.plumb.tac.ir.TacInstruction$Kind
 *  org.plumb.tac.ir.TacOperand$TacVar
 */
package org.plumb.dfa.checkers.taint;

import java.util.Map;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.taint.PathSensitiveCheckerBase;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

public final class ResourceLeakChecker extends PathSensitiveCheckerBase {
  public String ruleId() {
    return "dfa-taint-leak";
  }

  public String cwe() {
    return "CWE-404";
  }

  public String severityLabel() {
    return "HIGH";
  }

  /**
   * The .NET resources this rule tracks, by SIMPLE NAME.
   *
   * <p><b>Why this list exists at all.</b> The rule was written for Java, and every name in it is a
   * Java type. A comparative review against a second tool's C# checker taxonomy showed that the C#
   * resource types were missing: {@code new FileStream(...)} in C# was not an allocation site, so
   * the rule never fired on C# -- a capability the rule already had the machinery for (it inspects
   * {@code NEW} instructions and their constructed type) but never applied to.
   *
   * <p>The names are the .NET equivalents of the Java ones, and they are the same discipline the
   * Java list follows: a type is a resource when it has a {@code Dispose} (or a {@code Close}).
   * {@code FileStream}, {@code StreamReader}, {@code SqlConnection} and {@code NetworkStream} all
   * do; {@code String} and {@code Object} do not, so they are absent -- the same reason {@code
   * StringBuilder} is absent from the Java list.
   *
   * <p>The list is by SIMPLE NAME, so it works for a receiver the symbol solver resolved to {@code
   * System.IO.FileStream} and for one it could not resolve, which is recorded as the bare {@code
   * FileStream}.
   */
  private static final java.util.Set<String> DOTNET_CLOSEABLE_SIMPLE_NAMES =
      java.util.Set.of(
          // Files and streams
          "FileStream",
          "StreamReader",
          "StreamWriter",
          "BinaryReader",
          "BinaryWriter",
          "FileInfo",
          "DirectoryInfo",
          // Network
          "NetworkStream",
          "TcpClient",
          "TcpListener",
          "UdpClient",
          "SslStream",
          "WebClient",
          "HttpClient",
          // Database
          "SqlConnection",
          "SqlCommand",
          "SqlDataReader",
          "DbConnection",
          "DbCommand",
          "DbDataReader",
          "DbTransaction",
          // XML
          "XmlReader",
          "XmlTextReader",
          "XmlValidatingReader",
          "XmlDocument");

  protected boolean isAllocSite(TacInstruction ins) {
    if (ins.kind == TacInstruction.Kind.NEWARRAY) {
      // An ARRAY is an allocation, but it is not a RESOURCE: nothing can be "not closed" about
      // it, because it has no close(). `new byte[1024]` was reported as a leak, and that is
      // the difference between a rule that finds a real defect and a rule that cries wolf.
      return false;
    }
    if (ins.kind == TacInstruction.Kind.NEW) {
      // A `new X(...)` is a resource only when X can be closed. The class name decides, and
      // the SAME list that names the closeable resources names the allocatable ones -- so a
      // `new String(...)` or a `new Object()` is not reported.
      return isCloseableType(ins.dest instanceof TacOperand.TacVar v ? v.type().className() : null);
    }
    if (ins.kind == TacInstruction.Kind.CALL) {
      String callee = ins.calleeSig;
      if (callee == null) {
        return false;
      }
      return ResourceLeakChecker.containsCtor(callee, "FileInputStream")
          || ResourceLeakChecker.containsCtor(callee, "FileOutputStream")
          || ResourceLeakChecker.containsCtor(callee, "FileReader")
          || ResourceLeakChecker.containsCtor(callee, "FileWriter")
          || ResourceLeakChecker.containsCtor(callee, "BufferedReader")
          || ResourceLeakChecker.containsCtor(callee, "BufferedWriter")
          || ResourceLeakChecker.containsCtor(callee, "Scanner")
          || ResourceLeakChecker.containsCtor(callee, "Socket")
          || ResourceLeakChecker.containsCtor(callee, "ServerSocket")
          || ResourceLeakChecker.containsCtor(callee, "Connection")
          || ResourceLeakChecker.containsCtor(callee, "Statement")
          || ResourceLeakChecker.containsCtor(callee, "PreparedStatement")
          || ResourceLeakChecker.containsCtor(callee, "ResultSet")
          || ResourceLeakChecker.containsCtor(callee, "InputStream")
          || ResourceLeakChecker.containsCtor(callee, "OutputStream");
    }
    return false;
  }

  /**
   * Whether this CALL releases the resource.
   *
   * <p>The base class recognises {@code close}, which is Java's name. .NET spells the same
   * operation {@code Dispose} -- {@code IDisposable.Dispose} -- and a C# resource that is disposed
   * is a resource that is closed. Without this, a C# program that correctly disposes its resources
   * was reported as leaking every one of them: the rule would see the allocation, find no {@code
   * close}, and report the leak.
   *
   * <p>That is the false positive that matters most here: it fires on CORRECT code, and it fires on
   * every resource, so a scan of a well-written C# program would be nothing but leaks.
   */
  @Override
  protected boolean isCloseCall(TacInstruction ins) {
    if (super.isCloseCall(ins)) {
      return true;
    }
    if (ins.kind != TacInstruction.Kind.CALL) {
      return false;
    }
    String callee = ins.calleeSig;
    if (callee == null) {
      return false;
    }
    // The same shape as the base class's `close` match: the method name is
    // the LAST segment, so `Dispose` is required to be followed by the arity
    // separator or the end of the signature. That keeps a type named
    // `Disposable` from matching.
    return callee.contains(".Dispose/")
        || callee.contains("#Dispose/")
        || callee.endsWith(".Dispose")
        || callee.endsWith("#Dispose");
  }

  /**
   * Whether a type is one of the closeable resources this rule tracks.
   *
   * <p>The check is by SIMPLE NAME, because the type the TAC records is the declared type and the
   * declaration is usually the simple name. A type that is not one of the tracked names is not a
   * resource.
   *
   * <p>The .NET names are consulted too, because the rule applies to every language the TAC covers,
   * and a C# {@code FileStream} has the same resource obligation as a Java {@code FileInputStream}.
   */
  private static boolean isCloseableType(String type) {
    if (type == null) {
      return false;
    }
    String simple = type;
    int dot = simple.lastIndexOf('.');
    if (dot >= 0) {
      simple = simple.substring(dot + 1);
    }
    return CLOSEABLE_SIMPLE_NAMES.contains(simple)
        || DOTNET_CLOSEABLE_SIMPLE_NAMES.contains(simple);
  }

  /**
   * The simple names of the resources this rule tracks.
   *
   * <p>One list, used for BOTH the {@code new X(...)} form and the {@code X.factory(...)} form. Two
   * lists would drift, and the drift would show up as a leak reported on a type that is not tracked
   * -- or a leak missed on a type that is.
   */
  private static final java.util.Set<String> CLOSEABLE_SIMPLE_NAMES =
      java.util.Set.of(
          "FileInputStream",
          "FileOutputStream",
          "FileReader",
          "FileWriter",
          "BufferedReader",
          "BufferedWriter",
          "Scanner",
          "Socket",
          "ServerSocket",
          "Connection",
          "Statement",
          "PreparedStatement",
          "ResultSet",
          "InputStream",
          "OutputStream");

  private static boolean containsCtor(String callee, String className) {
    int idx = callee.indexOf(className);
    if (idx < 0) {
      return false;
    }
    int after = idx + className.length();
    if (after >= callee.length()) {
      return false;
    }
    char sep = callee.charAt(after);
    if (sep != '.' && sep != '#') {
      return false;
    }
    return callee.startsWith("<init>", after + 1) || callee.startsWith(".<init>", after);
  }

  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    for (Map.Entry e : this.allocSites.entrySet()) {
      TacOperand.TacVar allocVar = (TacOperand.TacVar) e.getKey();
      TacInstruction allocSite = (TacInstruction) e.getValue();
      // An UNCONDITIONAL close discharges the obligation.
      if (this.closeSites.containsKey(allocVar) || this.passedOut.contains(allocVar)) continue;
      // A close that only happens on SOME path does not: the rule fires when the resource is
      // "not closed on every path", and a conditional close leaves the other path leaking. The
      // allocation is therefore still reported.
      this.emitLeakFinding(allocVar, allocSite, fn, ctx);
    }
    return super.summarizeFunction(ctx, fn);
  }
}
