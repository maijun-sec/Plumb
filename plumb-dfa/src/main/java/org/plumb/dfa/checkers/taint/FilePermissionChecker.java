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
package org.plumb.dfa.checkers.taint;

import java.util.List;
import org.plumb.dfa.engine.AnalysisContext;
import org.plumb.dfa.engine.DfaChecker;
import org.plumb.reporter.Finding;
import org.plumb.rules.RuleDef;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacOperand;

/**
 * Unrestricted access to a file (CWE-732).
 *
 * <p>A file is made readable or writable by anyone -- not just its owner -- so a local user on a
 * shared machine can read a secret or replace a program.
 *
 * <h2>Why this rule is not a taint rule</h2>
 *
 * <p>Most of Plumb's checkers ask "did untrusted data reach a dangerous API?". This one asks a
 * different question: "does this code grant a permission that is too broad?". The dangerous call is
 * the call itself; no tainted value is involved. So the rule watches a <i>call shape</i>, not a
 * data flow.
 *
 * <p>That distinction matters for how it is implemented: it is a plain {@link DfaChecker} rather
 * than a {@link org.plumb.dfa.taint.TaintedCheckerBase}, because inheriting the taint machinery
 * would buy nothing and would make the rule look like it needs a source.
 *
 * <h2>What counts as a finding</h2>
 *
 * <ul>
 *   <li>{@code file.setReadable(true)} -- everyone can read. A finding.
 *   <li>{@code file.setReadable(true, true)} -- only the owner can read. <b>Not</b> a finding.
 *   <li>{@code file.setReadable(false)} -- nobody can read. Not a finding.
 *   <li>{@code file.setWritable(true)} / {@code setExecutable(true)} -- the same shape.
 *   <li>{@code file.setReadOnly()} -- removes write permission. Not a finding.
 * </ul>
 *
 * <p>The {@code ownerOnly} flag is the whole point of the API: {@code setReadable(true, true)} is
 * the safe form and {@code setReadable(true)} is not. The checker reads that flag out of the
 * argument list rather than guessing from the call shape.
 *
 * <h2>Language neutrality</h2>
 *
 * <p>{@code java.io.File} is the same symbol in every JVM language, so the rule applies everywhere
 * and the sink family carries no language restriction. A Kotlin program that calls {@code
 * file.setReadable(true)} has exactly the same defect.
 */
public final class FilePermissionChecker implements DfaChecker {

  private static final String RULE_ID = "dfa-file-permission";
  private static final String CWE = "CWE-732";

  /** The sink family this rule watches; see {@code file-permission.sinks} in the catalog. */
  private static final String FAMILY = "file-permission.sinks";

  /**
   * The permission-granting methods, and which argument carries the "readable / writable /
   * executable" flag.
   *
   * <p>Each of these takes {@code (boolean readable)} or {@code (boolean readable, boolean
   * ownerOnly)}. The one-argument form grants the permission to EVERYONE; the two-argument form
   * grants it to the owner alone when the second argument is {@code true}.
   */
  private static final List<String> PERMISSION_METHODS =
      List.of("setReadable", "setWritable", "setExecutable");

  @Override
  public String name() {
    return RULE_ID;
  }

  @Override
  public void checkInstruction(TacInstruction ins, TacFunction fn, AnalysisContext ctx) {
    if (ins.kind != TacInstruction.Kind.CALL && ins.kind != TacInstruction.Kind.NEW) return;
    String calleeSig = ins.calleeSig;
    if (calleeSig == null) return;

    // The language-aware lookup: a family that does not apply to this language is not
    // considered at all.
    boolean inFamily =
        ctx.config().sinkFamilies().lookupAll(calleeSig, fn.sourceLanguage()).stream()
            .anyMatch(f -> FAMILY.equals(f.name()));
    if (!inFamily) return;

    // Which permission method is this, if any?
    String method = simpleMethodName(calleeSig);
    if (method == null) return;
    boolean isPermissionMethod = PERMISSION_METHODS.contains(method);
    boolean isSetReadOnly = "setReadOnly".equals(method);
    if (!isPermissionMethod && !isSetReadOnly) return;

    // setReadOnly removes write permission; it is not a grant. It is in the family so that
    // the coverage report can name it, but it does not produce a finding.
    if (isSetReadOnly) return;

    // The TAC encodes a CALL with the receiver at args[0], so the Java argument N is at
    // args[N + 1]. A NEW has no receiver, so its args start at 0.
    int offset = ins.kind == TacInstruction.Kind.CALL ? 1 : 0;
    int flagIdx = offset; // the boolean permission flag
    if (flagIdx >= ins.args.size()) return;
    TacOperand flag = ins.args.get(flagIdx);
    if (!isBooleanTrue(flag)) return;

    // The ownerOnly flag, when present, decides whether the permission is restricted.
    int ownerOnlyIdx = offset + 1;
    if (ownerOnlyIdx < ins.args.size()) {
      TacOperand ownerOnly = ins.args.get(ownerOnlyIdx);
      if (isBooleanTrue(ownerOnly)) {
        // ownerOnly = true: only the owner gets the permission. Safe.
        return;
      }
      if (isBooleanFalse(ownerOnly)) {
        // ownerOnly = false: everyone gets it. A finding.
      }
      // A non-constant ownerOnly: unknown at compile time, so the finding stands.
    }

    ctx.reporter()
        .report(
            Finding.dfa(
                RULE_ID,
                RULE_ID,
                "File permission granted to everyone: "
                    + method
                    + "(true) without restricting it to the owner",
                RuleDef.Severity.MEDIUM,
                CWE,
                "",
                ins.line,
                ins.column,
                "unrestricted file permission via " + calleeSig));
  }

  /**
   * The method name of a callee signature.
   *
   * <p>Signatures look like {@code java.io.File.setReadable/2} or {@code File.setReadable/2}; the
   * name is the segment after the last dot, up to the arity slash.
   */
  private static String simpleMethodName(String calleeSig) {
    int slash = calleeSig.indexOf('/');
    String withoutArity = slash < 0 ? calleeSig : calleeSig.substring(0, slash);
    int dot = withoutArity.lastIndexOf('.');
    if (dot < 0) return null;
    return withoutArity.substring(dot + 1);
  }

  /** Whether an operand is the boolean constant {@code true}. */
  private static boolean isBooleanTrue(TacOperand op) {
    if (!(op instanceof TacOperand.Const c)) return false;
    return c.kind() == TacOperand.Const.Kind.BOOL && Boolean.TRUE.equals(c.value());
  }

  /** Whether an operand is the boolean constant {@code false}. */
  private static boolean isBooleanFalse(TacOperand op) {
    if (!(op instanceof TacOperand.Const c)) return false;
    return c.kind() == TacOperand.Const.Kind.BOOL && Boolean.FALSE.equals(c.value());
  }

  @Override
  public org.plumb.rules.RuleDef ruleDef() {
    return new org.plumb.rules.RuleDef(
        new org.plumb.rules.RuleId(RULE_ID, "FILE_PERMISSION"),
        "Unrestricted access to a file (permissions granted to everyone)",
        RuleDef.Severity.MEDIUM,
        RuleDef.Type.SECURITY,
        List.of(CWE),
        getClass().getName(),
        true);
  }
}
