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

public final class InsecureRandomChecker extends TaintedCheckerBase {
  public InsecureRandomChecker() {
    super(
        new TaintRule(
            "dfa-taint-insecure-random",
            "dfa-taint-untrusted-source",
            "random.sinks",
            "CWE-330",
            "MEDIUM",
            (TaintDomain) TaintDomain.StringTaint.INSTANCE));
  }
}
