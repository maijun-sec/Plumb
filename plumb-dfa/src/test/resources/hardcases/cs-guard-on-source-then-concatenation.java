// CASE cs-guard-on-source-then-concatenation
//
// The guard is on the SOURCE variable, and the sink's argument is the result of a
// CONCATENATION -- a temporary that carries no guard of its own.
//
// A path condition built only from the sink's own argument is therefore empty:
// the argument has no guard, so the contradiction is never asked about and the
// engine reports an allocation that cannot happen. The guard on the source
// constrains the value that reaches the sink.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardOnSourceThenConcatenation {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n > 5 && n < 3) {
      StringBuilder sb = new StringBuilder("x" + n + "y");
      System.out.println(sb);
    }
  }
}
