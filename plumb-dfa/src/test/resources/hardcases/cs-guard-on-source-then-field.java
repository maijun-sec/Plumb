// CASE cs-guard-on-source-then-field
//
// The guard is on the SOURCE variable, the value goes into a field, and the sink
// reads the field back. The sink's argument is the field read -- a temporary that
// carries no guard of its own.
//
// A path condition built only from the sink's own argument is empty, so the
// contradiction between the two bounds is never asked about.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardOnSourceThenField {
  int n;

  void go(java.io.BufferedReader reader) throws Exception {
    int v = Integer.parseInt(reader.readLine());
    if (v > 5 && v < 3) {
      this.n = v;
      StringBuilder sb = new StringBuilder(this.n);
      System.out.println(sb);
    }
  }
}
