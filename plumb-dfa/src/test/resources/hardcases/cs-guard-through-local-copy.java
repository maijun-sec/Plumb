// CASE cs-guard-through-local-copy
//
// The guard is on a LOCAL that holds a copy of the tainted number, and the sink
// reads the original. A path condition built only from the sink's own argument
// is therefore empty: the argument carries no guard, so the contradiction is
// never asked about -- and the engine reports an allocation it cannot happen.
//
// The copy is what makes the two values the same value, so the guard on the copy
// constrains the original.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardThroughLocalCopy {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    int copy = n;
    if (copy > 5 && copy < 3) {
      StringBuilder sb = new StringBuilder(n);
      System.out.println(sb);
    }
  }
}
