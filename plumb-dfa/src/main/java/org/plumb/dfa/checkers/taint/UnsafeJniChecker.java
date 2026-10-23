/*
 * Decompiled with CFR 0.152.
 *
 * Could not load the following classes:
 *  org.plumb.dfa.taint.TaintDomain
 *  org.plumb.dfa.taint.TaintDomain$StringTaint
 *  org.plumb.dfa.taint.TaintRule
 *  org.plumb.dfa.taint.TaintedCheckerBase
 */
package org.plumb.dfa.checkers.taint;

import org.plumb.dfa.taint.TaintDomain;
import org.plumb.dfa.taint.TaintRule;
import org.plumb.dfa.taint.TaintedCheckerBase;

public final class UnsafeJniChecker extends TaintedCheckerBase {
  public UnsafeJniChecker() {
    super(
        new TaintRule(
            "dfa-taint-unsafe-jni",
            "dfa-taint-untrusted-source",
            "jni.sinks",
            "CWE-114",
            "CRITICAL",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }
}
