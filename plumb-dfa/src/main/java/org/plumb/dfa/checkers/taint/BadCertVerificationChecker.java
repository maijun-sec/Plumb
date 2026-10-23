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

public final class BadCertVerificationChecker extends TaintedCheckerBase {
  public BadCertVerificationChecker() {
    super(
        new TaintRule(
            "dfa-taint-bad-cert-verification",
            "dfa-taint-untrusted-source",
            "ssl.sinks",
            "CWE-295",
            "CRITICAL",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }
}
