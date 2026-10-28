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
package org.plumb.dfa.checkers.controlflow;

import java.util.HashSet;
import java.util.Set;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.dfa.summary.FunctionSummary;
import org.plumb.dfa.trace.PatternTrace;
import org.plumb.reporter.trace.TraceNode;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * CWE-611 (Improper Restriction of XML External Entity Reference) / XXE attack detector, in the
 * unified DFA engine.
 *
 * <p>The classic XXE pattern is:
 *
 * <pre>{@code
 * DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
 * // no setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
 * // no setFeature("http://xml.org/sax/features/external-general-entities", false)
 * // no setFeature("http://xml.org/sax/features/external-parameter-entities", false)
 * // no setXIncludeAware(false) / setExpandEntityReferences(false)
 * DocumentBuilder db = dbf.newDocumentBuilder();
 * db.parse(inputStream);  // <-- vulnerable
 * }</pre>
 *
 * <p>The DFA tracks per-function the set of vars that were created from a {@code
 * DocumentBuilderFactory} or {@code SAXParserFactory} constructor, plus which of those vars have
 * had a "secure feature" set on them (e.g. {@code setFeature("...disallow-doctype-decl", true)}). A
 * subsequent {@code parse} / {@code unmarshal} on a derived {@code DocumentBuilder} / {@code
 * SAXParser} that traces back to an unsecured factory fires CWE-611.
 *
 * <p>This is a CVE-2014-3577 / CVE-2016-5007 / general XXE pattern; many of the {@code "OWASP Top
 * 10 XXE"} examples are caught by this rule.
 *
 * <p><b>Why DFA, not AST.</b> A syntactic pass flags every {@code db.parse(...)} call as XXE, which
 * is wrong: the user may have correctly configured the factory. The DFA correlates the factory
 * construction, the secure configuration call, and the parse call -- missing the config call, the
 * parse is XXE.
 */
public final class XxeChecker implements DfaChecker {

  /** Per-function: vars holding a *secured* factory (had a safe setFeature). */
  private final Set<String> securedFactories = new HashSet<>();

  /** Per-function: vars holding a *plain* factory (created but not secured). */
  private final Set<String> plainFactories = new HashSet<>();

  /** Per-function: vars holding a parser / unmarshaller derived from a factory. */
  private final Set<String> parsers = new HashSet<>();

  /** Map parser var -> factory var. */
  private final java.util.Map<String, String> parserFromFactory = new java.util.HashMap<>();

  @Override
  public String name() {
    return "Xxe";
  }

  @Override
  public void initFunction(AnalysisContext ctx, TacFunction fn) {
    securedFactories.clear();
    plainFactories.clear();
    parsers.clear();
    parserFromFactory.clear();
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    switch (ins.kind) {
      case NEW -> {
        // DocumentBuilderFactory.newInstance()
        // → t = new DocumentBuilderFactory (synthetic;
        // the translator would normally
        // generate the new for
        // "new DocumentBuilderFactory()"
        // with ctorType "DocumentBuilderFactory").
        if (ins.ctorType == null) return;
        String type = ins.ctorType.toString();
        if (isFactory(type) && ins.dest instanceof TacOperand.TacVar dest) {
          plainFactories.add(dest.name());
        }
      }
      case CALL -> {
        if (ins.calleeSig == null) return;
        String method = methodName(ins.calleeSig);
        // setFeature / setXIncludeAware /
        // setExpandEntityReferences on a
        // factory: tag the receiver as
        // secured. The receiver is args[0]
        // in thiscall convention.
        if (isSecureConfigCall(method)
            && ins.args != null
            && !ins.args.isEmpty()
            && ins.args.get(0) instanceof TacOperand.TacVar recv
            && plainFactories.contains(recv.name())) {
          securedFactories.add(recv.name());
        }
        // newDocumentBuilder / newSAXParser:
        // creates a parser from a factory.
        if (isParserCreator(method)
            && ins.args != null
            && !ins.args.isEmpty()
            && ins.args.get(0) instanceof TacOperand.TacVar recv
            && ins.dest instanceof TacOperand.TacVar dest) {
          parsers.add(dest.name());
          parserFromFactory.put(dest.name(), recv.name());
        }
        // parse / unmarshal on a parser:
        // the dangerous call site.
        if (isParseCall(method) && ins.args != null && !ins.args.isEmpty()) {
          TacOperand recv = ins.args.get(0);
          if (recv instanceof TacOperand.TacVar recvVar && parsers.contains(recvVar.name())) {
            String factory = parserFromFactory.get(recvVar.name());
            if (factory != null && !securedFactories.contains(factory)) {
              emit(ctx, ins, fn, recvVar.name(), factory);
            }
          }
        }
      }
      case MOVE -> {
        // Track alias: t = parser → t is also a parser
        if (ins.dest instanceof TacOperand.TacVar dest
            && ins.lhs1 instanceof TacOperand.TacVar src
            && parsers.contains(src.name())) {
          parsers.add(dest.name());
          parserFromFactory.put(dest.name(), parserFromFactory.get(src.name()));
        }
      }
      default -> {
        /* ignore */
      }
    }
  }

  @Override
  public FunctionSummary summarizeFunction(AnalysisContext ctx, TacFunction fn) {
    return FunctionSummary.empty();
  }

  private static boolean isFactory(String type) {
    if (type == null) return false;
    String shortName = shortName(type);
    return shortName.equals("DocumentBuilderFactory")
        || shortName.equals("SAXParserFactory")
        || shortName.equals("XMLInputFactory");
  }

  private static boolean isSecureConfigCall(String method) {
    return "setFeature".equals(method)
        || "setXIncludeAware".equals(method)
        || "setExpandEntityReferences".equals(method)
        || "setValidating".equals(method);
  }

  private static boolean isParserCreator(String method) {
    return "newDocumentBuilder".equals(method)
        || "newSAXParser".equals(method)
        || "createXMLStreamReader".equals(method);
  }

  private static boolean isParseCall(String method) {
    return "parse".equals(method) || "unmarshal".equals(method);
  }

  private static String shortName(String fqn) {
    int dot = fqn.lastIndexOf('.');
    return dot < 0 ? fqn : fqn.substring(dot + 1);
  }

  private static String methodName(String sig) {
    if (sig == null) return null;
    int lastDot = sig.lastIndexOf('.');
    int hash = sig.lastIndexOf('#');
    int sep = Math.max(lastDot, hash);
    if (sep < 0) {
      int slash = sig.indexOf('/');
      return slash < 0 ? sig : sig.substring(0, slash);
    }
    String tail = sig.substring(sep + 1);
    int slash = tail.indexOf('/');
    return slash < 0 ? tail : tail.substring(0, slash);
  }

  private static void emit(
      AnalysisContext ctx,
      TacInstruction ins,
      TacFunction fn,
      String parserVar,
      String factoryVar) {
    // v2 trace: single-node VIOLATION
    // with XXE op + a Free operand encoding
    // the "parser:factory" shape so the AI
    // dedup layer can see the pair without
    // regex-parsing the legacy flow string.
    PatternTrace.reportFree(
        ctx,
        fn,
        ins,
        TraceNode.Op.XXE,
        "xxe: " + parserVar + " from " + factoryVar,
        "Xxe",
        "plumb.611",
        "Parser "
            + parserVar
            + " derived from factory "
            + factoryVar
            + " is used to parse untrusted XML without a known-safe"
            + " configuration (setFeature disallow-doctype-decl,"
            + " setXIncludeAware(false), etc.) — XXE attack surface",
        RuleDef.Severity.HIGH,
        "CWE-611",
        "xxe: " + parserVar + " from " + factoryVar);
  }

  private static final String AI_PROMPT =
"""
        RULE: XML External Entity (XXE) vulnerability (CWE-611).
        This rule fires when an XML parser is constructed
        without disabling external entity processing
        (DocumentBuilderFactory.setFeature(..., false) for
        FEATURE_SECURE_PROCESSING, or SAXParserFactory,
        TransformerFactory, etc.).

        A finding is a TRUE POSITIVE (TP) when the XML comes
        from user input (request body, file upload, URL).

        A finding is a FALSE POSITIVE (FP) when:
          * The XML is a static config file in the
            application's classpath
          * The parser is configured elsewhere in the same
            file / class

        UNKNOWN when the XML source is unclear.
""";

  @Override
  public String getAiPrompt(org.plumb.reporter.Finding finding) {
    return AI_PROMPT;
  }
}
