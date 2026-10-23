package org.plumb.style;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * dynamic loading of plumb-style checkers.
 *
 * <p>Verifies the three loading modes exposed by {@link AstCheckerRegistry}:
 *
 * <ul>
 *   <li>{@link AstCheckerRegistry#builtins()} : register every checker (~500 across 7
 *       subdirectories).
 *   <li>{@link AstCheckerRegistry#load(String...)} :  register by glob on the simple class name.
 *   <li>{@link AstCheckerRegistry#byCategory(String...)} :  register by subdirectory.
 * </ul>
 */
class R311cDynamicLoadTest {

  @Test
  void listAvailableReturnsAllCheckers() {
    List<String> available = AstCheckerRegistry.listAvailable();
    assertTrue(
        available.size() > 400, "expected > 400 available checkers, got " + available.size());
    assertTrue(available.contains("EmptyMethodCheck"));
    assertTrue(available.contains("AvoidStarImportCheck"));
  }

  @Test
  void builtinsRegistersEverything() {
    AstCheckerRegistry r = new AstCheckerRegistry().builtins();
    Set<String> names = r.registeredNames();
    assertTrue(names.size() > 400);
    assertTrue(names.contains("EmptyMethodCheck"));
    assertTrue(names.contains("AvoidStarImportCheck"));
  }

  @Test
  void loadByGlobFilters() {
    AstCheckerRegistry r = new AstCheckerRegistry().load("Empty.*");
    Set<String> names = r.registeredNames();
    // All Empty*Check checkers should be loaded.
    assertTrue(names.contains("EmptyBlockCheck"));
    assertTrue(names.contains("EmptyMethodCheck"));
    assertTrue(names.contains("EmptyCatchBlockCheck"));
    // Non-Empty* should NOT be loaded.
    assertFalse(names.contains("AvoidStarImportCheck"));
  }

  @Test
  void loadByExactName() {
    AstCheckerRegistry r = new AstCheckerRegistry().load("AvoidStarImportCheck");
    Set<String> names = r.registeredNames();
    assertEquals(1, names.size());
    assertTrue(names.contains("AvoidStarImportCheck"));
  }

  @Test
  void byCategoryFilters() {
    // debug
    System.out.println("Category histogram: " + AstCheckerRegistry.categoryHistogram());
    System.out.println("listAvailable size: " + AstCheckerRegistry.listAvailable().size());
    System.out.println(
        "First 10 available: "
            + AstCheckerRegistry.listAvailable()
                .subList(0, Math.min(10, AstCheckerRegistry.listAvailable().size())));
    AstCheckerRegistry naming = new AstCheckerRegistry().byCategory("naming");
    Set<String> namingNames = naming.registeredNames();
    assertTrue(namingNames.size() > 30, "expected > 30 naming checkers, got " + namingNames.size());
  }

  @Test
  void byCategoryPerformance() {
    AstCheckerRegistry perf = new AstCheckerRegistry().byCategory("performance");
    Set<String> names = perf.registeredNames();
    assertTrue(names.size() > 20, "expected > 20 performance checkers, got " + names.size());
  }

  @Test
  void loadByMultiplePatterns() {
    AstCheckerRegistry r = new AstCheckerRegistry().load("EmptyBlock.*", "AvoidStarImport.*");
    Set<String> names = r.registeredNames();
    assertTrue(names.contains("EmptyBlockCheck"));
    assertTrue(names.contains("AvoidStarImportCheck"));
    // EmptyMethodCheck doesn't match EmptyBlock.*
    assertFalse(names.contains("EmptyMethodCheck"));
  }
}
