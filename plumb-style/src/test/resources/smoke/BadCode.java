// Synthetic bad code for smoke-testing the R298-R310
// style checkers. Each anti-pattern below should be
// caught by at least one of the new checkers.
import java.util.*;
import java.util.HashMap;

public class BadCode {

  // LooseCouplingCheck: HashMap field should be Map
  private HashMap<String, String> map = new HashMap<>();

  // SingletonClassCheck: private ctor + all static
  private BadCode() {}

  // UnnecessaryConstructorCheck: empty no-arg ctor
  public BadCode(int x) {}

  public BadCode() {}

  // MagicNumberCheck: 42 in method body
  public int answer() {
    int x = 42;
    return x;
  }

  // UnnecessaryReturnCheck: final 'return' in void
  public void doWork() {
    System.out.println("hi");
    return;
  }

  // CovariantEqualsCheck: equals(T) not equals(Object)
  public boolean equals(BadCode other) {
    return false;
  }

  // SystemOutPrintlnCheck: should fire
  public void logStuff() {
    System.out.println("debug");
  }

  // PrintStackTraceCheck: should fire
  public void handleError() {
    try {
      int x = 1 / 0;
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  // IteratorIdiomaticCheck: index loop should be foreach
  public void iterate(List<String> list) {
    for (int i = 0; i < list.size(); i++) {
      String s = list.get(i);
      System.out.println(s);
    }
  }

  // R310a NonStaticInitializerCheck
  {
    map.put("init", "1");
  }

  // R310d IncorrectReturnValueCheckChecker
  public void compareBadly(boolean b) {
    if (b == true) {
      System.out.println("ok");
    }
  }

  // AvoidStarImportCheck: java.util.* in source above
  // (checked at CU level)

  // MissingDeprecatedCheck: @Deprecated class needs @Deprecated methods
}
