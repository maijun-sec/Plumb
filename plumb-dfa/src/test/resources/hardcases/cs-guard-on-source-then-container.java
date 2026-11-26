// CASE cs-guard-on-source-then-container
//
// The guard is on the SOURCE variable, the value goes into a container, and the
// sink reads the element back out. The sink's argument is the element -- a
// temporary that carries no guard of its own.
//
// A path condition built only from the sink's own argument is empty, so the
// contradiction between the two bounds is never asked about.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardOnSourceThenContainer {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    java.util.List<Integer> xs = new java.util.ArrayList<>();
    if (n > 5 && n < 3) {
      xs.add(n);
      StringBuilder sb = new StringBuilder(xs.get(0));
      System.out.println(sb);
    }
  }
}
