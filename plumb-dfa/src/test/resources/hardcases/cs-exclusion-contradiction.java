// CASE cs-exclusion-contradiction
//
// No PAIR of the three guards conflicts: the two bounds are satisfied together
// by the value 5, and the exclusion rules nothing out on its own. Only the
// conjunction has no solution.
//
// EXPECT: no finding.
class CsExclusionContradiction {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    if (n >= 5 && n <= 5) {
      if (n != 5) {
        StringBuilder sb = new StringBuilder(n);
        System.out.println(sb);
      }
    }
  }
}
