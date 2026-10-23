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

public final class UnsafeReflectionChecker extends TaintedCheckerBase {
  public UnsafeReflectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-unsafe-reflection",
            "dfa-taint-untrusted-source",
            "reflection.sinks",
            "CWE-470",
            "HIGH",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }
}
