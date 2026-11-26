// CASE cs-guard-through-return-value
//
// The guard is in the CALLEE and the tainted number leaves it as a return value.
// The sink is in the caller, and its argument is the returned value -- which
// carries no guard, so a path condition built only from the sink's arguments is
// empty.
//
// The returned value and the guarded number are the same value, so the guard
// inside the callee constrains what the caller receives.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardThroughReturnValue {
  int parse(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n > 5 && n < 3) {
      return n;
    }
    return 0;
  }

  void go(java.io.BufferedReader reader) throws Exception {
    int v = parse(reader);
    StringBuilder sb = new StringBuilder(v);
    System.out.println(sb);
  }
}
