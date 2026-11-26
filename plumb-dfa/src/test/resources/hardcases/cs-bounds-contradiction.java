// CASE cs-bounds-contradiction
//
// The two bounds cannot both hold, so the body of the if is unreachable and the
// tainted number cannot reach the allocation.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsBoundsContradiction {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n > 5 && n < 3) {
      StringBuilder sb = new StringBuilder(n);
      System.out.println(sb);
    }
  }
}
