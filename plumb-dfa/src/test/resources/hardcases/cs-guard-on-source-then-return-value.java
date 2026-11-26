// CASE cs-guard-on-source-then-return-value
//
// The guard is on the SOURCE variable inside the CALLEE, and the tainted value
// leaves the callee as a return value. The sink is in the caller and its argument
// is the returned value -- a temporary that carries no guard of its own.
//
// A path condition built only from the sink's own argument is empty, so the
// contradiction between the two bounds is never asked about.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardOnSourceThenReturnValue {
  int parse(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n > 5 && n < 3) {
      return n;
    }
    return 0;
  }

  void go(java.io.BufferedReader reader) throws Exception {
    int v = parse(reader);
    StringBuilder sb = new StringBuilder("x" + v + "y");
    System.out.println(sb);
  }
}
