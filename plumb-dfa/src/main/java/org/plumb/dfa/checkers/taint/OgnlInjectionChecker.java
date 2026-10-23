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

public final class OgnlInjectionChecker extends TaintedCheckerBase {
  public OgnlInjectionChecker() {
    super(
        new TaintRule(
            "dfa-taint-ognl-injection",
            "dfa-taint-untrusted-source",
            "ognl.sinks",
            "CWE-94",
            "CRITICAL",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }
}
