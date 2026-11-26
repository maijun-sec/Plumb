// CASE cs-guard-through-container
//
// The tainted number goes into a container and the sink reads it back out of the
// container. The guard is on the number, and the sink's argument is the container
// element -- which carries no guard of its own, so a path condition built only
// from the sink's arguments is empty.
//
// The element and the number are the same value, so the guard on the number
// constrains the element.
//
// EXPECT: no dfa-taint-uncontrolled-mem-alloc finding.
class CsGuardThroughContainer {
  void go(java.io.BufferedReader reader) throws Exception {
    int n = Integer.parseInt(reader.readLine());
    java.util.List<Integer> xs = new java.util.ArrayList<>();
    xs.add(n);
    if (n > 5 && n < 3) {
      StringBuilder sb = new StringBuilder(xs.get(0));
      System.out.println(sb);
    }
  }
}
