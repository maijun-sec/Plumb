// CASE cs-div-zero-guard
//
// The divisor is guarded against zero, so the division cannot divide by zero.
//
// EXPECT: no finding.
class CsDivZeroGuard {
  void go(java.io.BufferedReader reader) throws Exception {
    int d = Integer.parseInt(reader.readLine());
    int a = 10;
    if (d != 0) {
      int q = a / d;
      System.out.println(q);
    }
  }
}
