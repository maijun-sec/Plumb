// CASE cs-guard-across-call-boundary
//
// The guard is at the CALL SITE and the sink is in the CALLEE. The callee's own
// argument carries no guard -- the guard is on the caller's variable -- so a path
// condition built only from the sink's arguments is empty.
//
// The value the callee receives is the caller's value, so the guard at the call
// site constrains it.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardAcrossCallBoundary {
  void sink(int n) {
    StringBuilder sb = new StringBuilder(n);
    System.out.println(sb);
  }

  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n > 5 && n < 3) {
      sink(n);
    }
  }
}
