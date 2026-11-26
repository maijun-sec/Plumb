// CASE cs-array-length-guard
//
// The index is bounded on both sides by the array's own length, so the access is
// in range on every path that reaches it.
//
// EXPECT: no dfa-taint-unchecked-array-index finding.
class CsArrayLengthGuard {
  void go(java.io.BufferedReader reader) throws Exception {
    int i = Integer.parseInt(reader.readLine());
    String[] arr = new String[] {"a", "b", "c"};
    if (i >= 0 && i < arr.length) {
      String v = arr[i];
      System.out.println(v);
    }
  }
}
