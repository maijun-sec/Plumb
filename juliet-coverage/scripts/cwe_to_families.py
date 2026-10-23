"""
cwe_to_families.py — Map each Juliet CWE to the minimal set of Plumb families needed.

Source of truth: Plumb Plumb-OVERVIEW.md section 6 + default-config.yaml.

The full family list (41 family from R676+):
  taint, style, divByZero, resourceLeak, nullDeref, hardcodedCred,
  intOverflow, intUnderflow, arrayBounds, weakCrypto, insecureRandom,
  predictableSeed, predictableKey, staticApiKey, insecureComms, corsMisconfig,
  insecureCookie, badShift, infiniteLoop, constantCondition,
  sensitiveDataLeak, fieldHardcodedPassword, missingAuthorization,
  missingInputValidation, informationExposure, uncheckedReturn, incorrectReturn,
  threadRun, stringEquality, catchGeneric, obsoleteFunction, tryCatchFinally,
  offByOne, equalsCalledOnNull, compareWithSelf, xxe, returnFromFinally,
  emptyCatchHandler, iteratorConcurrentModification, collectionSelfReference,
  uncaughtExceptionPropagator, uncheckedLoopCondition, uncontrolledMemAlloc,
  numericTruncation, bigDecimalDoubleConstructor, defaultEncoding

Mapping rule: a CWE is "in scope" of a family if Plumb-OVERVIEW.md lists that CWE
in the family's main CWE column (per the R668 taxonomy table).

For CWE not in any family (e.g. CWE248 uncaught exception), I map to the closest
related family — `tryCatchFinally` for uncaught-exception-related, etc.

The mapping below is MANUAL, based on Plumb-OVERVIEW.md table (verified against
default-config.yaml comments).
"""

from __future__ import annotations

import sys
from pathlib import Path


# Default: every CWE → empty (means enable nothing, in which case baseline is empty)
# If a CWE is not in this dict, we fall back to "no families enabled" → 0 findings,
# which means TP=0 too. That CWE is then evaluated as "Plumb has no rule for it".

CWE_TO_FAMILIES: dict[str, list[str]] = {
    # ── Taint-driven CWE ──
    "CWE15":  ["taint"],                                  # External Config Control
    "CWE23":  ["taint"],                                  # Relative Path Traversal
    "CWE36":  ["taint"],                                  # Absolute Path Traversal
    "CWE78":  ["taint"],                                  # OS Command Injection
    "CWE80":  ["taint"],                                  # XSS (Reflected)
    "CWE81":  ["taint"],                                  # XSS Error Message
    "CWE83":  ["taint"],                                  # XSS Attribute
    "CWE89":  ["taint"],                                  # SQL Injection
    "CWE90":  ["taint"],                                  # LDAP Injection
    "CWE111": ["taint"],                                  # Unsafe JNI
    "CWE113": ["taint"],                                  # HTTP Response Splitting
    "CWE114": ["taint"],                                  # Process Control
    "CWE134": ["taint"],                                  # Format String
    "CWE378": ["taint"],                                  # Temp File Perms
    "CWE379": ["taint"],                                  # Temp File Insecure Dir
    "CWE400": ["taint"],                                  # Resource Exhaustion
    "CWE470": ["taint"],                                  # Unsafe Reflection
    "CWE566": ["taint"],                                  # Auth Bypass via SQL Primary
    "CWE601": ["taint"],                                  # Open Redirect
    "CWE643": ["taint"],                                  # Xpath Injection
    # ── Bounds / numeric ──
    "CWE129": ["arrayBounds"],                            # Array Bounds
    "CWE193": ["arrayBounds", "offByOne"],                # Off by One
    "CWE190": ["intOverflow"],                            # Integer Overflow
    "CWE191": ["intUnderflow"],                           # Integer Underflow
    "CWE197": ["numericTruncation"],                      # Numeric Truncation
    "CWE681": ["numericTruncation"],                      # Incorrect Numeric Conversion (variant of trunc)
    # ── Resource leak ──
    "CWE404": ["resourceLeak"],                           # Improper Resource Shutdown
    "CWE459": ["resourceLeak"],                           # Incomplete Cleanup
    "CWE772": ["resourceLeak"],                           # Missing Release of Resource
    "CWE775": ["resourceLeak"],                           # Missing Release of FD
    "CWE789": ["uncontrolledMemAlloc"],                    # Uncontrolled Mem Alloc
    # ── Null / control flow ──
    "CWE395": ["nullDeref"],                              # Catch NullPointerException
    "CWE476": ["nullDeref"],                              # NULL Pointer Dereference
    "CWE690": ["nullDeref"],                              # NULL Deref From Return
    "CWE478": ["constantCondition"],                      # Missing Default in Switch
    "CWE484": ["constantCondition"],                      # Omitted Break
    "CWE561": ["constantCondition"],                      # Dead Code
    "CWE570": ["constantCondition"],                      # Always False
    "CWE571": ["constantCondition"],                      # Always True
    "CWE617": ["constantCondition"],                      # Reachable Assertion
    # ── Cryptography ──
    "CWE325": ["weakCrypto"],                             # Missing Cryptographic Step
    "CWE327": ["weakCrypto"],                             # Use Broken Crypto
    "CWE328": ["weakCrypto"],                             # Reversible One Way Hash
    "CWE329": ["weakCrypto"],                             # Not Using Random IV with CBC
    "CWE338": ["weakCrypto", "insecureRandom"],          # Weak PRNG
    "CWE759": ["weakCrypto"],                             # Unsalted One Way Hash
    "CWE760": ["weakCrypto"],                             # Predictable Salt One Way Hash
    # ── Hardcoded credentials ──
    "CWE256": ["hardcodedCred"],                          # Plaintext Storage of Password
    "CWE259": ["hardcodedCred", "fieldHardcodedPassword"],
    "CWE321": ["predictableKey"],                         # Hard-coded Cryptographic Key
    "CWE798": ["hardcodedCred"],                          # Hard-coded Credentials (overlaps)
    "CWE523": ["hardcodedCred"],                          # Unprotected Cred Transport
    "CWE549": ["hardcodedCred"],                          # Missing Password Masking
    "CWE315": ["insecureCookie"],                         # Plaintext Storage in Cookie
    # ── PRNG ──
    "CWE336": ["predictableSeed"],                        # Same Seed in PRNG
    # ── Info exposure / logging ──
    "CWE209": ["informationExposure"],                    # Info Leak Error
    "CWE526": ["informationExposure"],                    # Env Vars in Log
    "CWE533": ["informationExposure"],                    # Server Log Info
    "CWE534": ["informationExposure"],                    # Debug Log
    "CWE535": ["informationExposure"],                    # Shell Error
    "CWE598": ["informationExposure"],                    # QueryString
    "CWE615": ["informationExposure"],                    # Comment
    # ── Sensitive data ──
    "CWE226": ["sensitiveDataLeak"],                      # Sensitive Info Uncleared
    "CWE499": ["sensitiveDataLeak"],                      # Sensitive Data Serializable
    # ── Communications / cookie ──
    "CWE319": ["insecureComms"],                          # Cleartext Tx
    "CWE539": ["insecureCookie"],                         # Persistent Cookie Info Exposure
    "CWE613": ["insecureCookie"],                         # Insufficient Session Expiration
    "CWE614": ["insecureCookie"],                         # Sensitive Cookie Without Secure
    # ── Exception / catch ──
    "CWE248": ["uncaughtExceptionPropagator"],           # Uncaught Exception
    "CWE600": ["uncaughtExceptionPropagator"],           # Uncaught in Servlet
    "CWE390": ["emptyCatchHandler"],                      # Error Without Action
    "CWE396": ["tryCatchFinally"],                        # Catch Generic
    "CWE397": ["tryCatchFinally"],                        # Throw Generic
    "CWE584": ["returnFromFinally"],                      # Return in Finally
    # ── Concurrency ──
    "CWE572": ["threadRun"],                              # Call Thread run Instead of start
    "CWE585": ["iteratorConcurrentModification"],        # Empty Sync Block (closest match)
    # ── String comparison / equality ──
    "CWE597": ["stringEquality"],                         # Wrong Operator String Comparison
    "CWE486": ["equalsCalledOnNull"],                     # Compare Classes by Name (closest)
    # ── Unchecked return / off-by-one ──
    "CWE252": ["uncheckedReturn"],                        # Unchecked Return
    "CWE253": ["uncheckedReturn"],                        # Incorrect Check of Return
    # ── Loop / infinite ──
    "CWE606": ["uncheckedLoopCondition"],                 # Unchecked Loop Condition
    "CWE835": ["infiniteLoop"],                           # Infinite Loop
    # ── XXE / XML ──
    "CWE611": ["xxe"],                                    # XXE
    # ── Misc ──
    "CWE477": ["obsoleteFunction"],                       # Obsolete Functions
    "CWE609": ["uncheckedLoopCondition"],                 # Double Checked Locking (closest match)
    # ── Singleton / object lifecycle (limited coverage) ──
    "CWE374": ["uncheckedReturn"],                        # Passing Mutable Objects (best guess)
    "CWE668": ["uncheckedReturn"],                        # Call to Non-Unlock (best guess)
    # ── Permission / authorization ──
    # Plumb missing these. Skip rather than wrong family.
    # ── Default: no rule → 0 findings (explicit "not supported") ──
}


def families_for_cwe(cwe_id: str) -> list[str]:
    return CWE_TO_FAMILIES.get(cwe_id.upper(), [])


if __name__ == "__main__":
    # Self-test: print summary
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--cwe", type=str, help="e.g. CWE89 (single lookup)")
    ap.add_argument("--list", action="store_true", help="List all CWE mappings")
    args = ap.parse_args()

    if args.cwe:
        families = families_for_cwe(args.cwe)
        print(f"{args.cwe}: {families}")
    elif args.list:
        for cwe in sorted(CWE_TO_FAMILIES.keys()):
            print(f"  {cwe}: {CWE_TO_FAMILIES[cwe]}")
        print(f"\nTotal CWE in mapping: {len(CWE_TO_FAMILIES)}")
    else:
        ap.print_help()