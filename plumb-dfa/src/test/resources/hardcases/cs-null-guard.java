// CASE cs-null-guard
//
// The reference is guarded against null before it is dereferenced.
//
// EXPECT: no null-dereference finding.
class CsNullGuard {
  void go(String userInput) {
    if (userInput != null) {
      int n = userInput.length();
      System.out.println(n);
    }
  }
}
