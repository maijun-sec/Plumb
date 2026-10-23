package org.plumb.dfa.config;

import java.util.List;
import org.junit.jupiter.api.Test;

class R7SinkFamilyLookupTest {
  @Test
  void verifyDbCredentialSinksRegistered() throws Exception {
    EngineConfig cfg = ConfigLoader.loadFromClasspath();
    List<SinkFamily> matches =
        cfg.sinkFamilies().lookupAll("java.sql.DriverManager.getConnection/3");
    System.out.println("matches.size = " + matches.size());
    for (SinkFamily f : matches) {
      System.out.println("  family: " + f.name() + " cwe=" + f.cweOrNull());
    }
  }
}
