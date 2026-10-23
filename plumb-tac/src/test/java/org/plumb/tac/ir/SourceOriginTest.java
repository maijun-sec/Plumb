package org.plumb.tac.ir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SourceOrigin} and the {@link TacFunction#origin()} integration.
 *
 * <p>Covers the four contract points:
 *
 * <ol>
 *   <li>The default origin is {@link SourceOrigin#SOURCE}.
 *   <li>{@link TacFunction#origin()} returns the value supplied at construction.
 *   <li>{@link TacFunction#hasOriginIn(java.util.Set)} is permissive for the empty / null set (so
 *       callers that don't filter by origin still see every function).
 *   <li>{@link SourceOrigin#analysedByDefault()} is true only for {@code SOURCE}.
 * </ol>
 */
class SourceOriginTest {

  @Test
  void defaultOriginIsSource() {
    // A function built with the old constructor chain
    // should still have
    // SOURCE as the origin; we don't want
    // the new field to silently change
    // behaviour for the rest of the codebase.
    TacFunction fn =
        new TacFunction(
            "m",
            "M#m()",
            TacType.VOID,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of());
    assertEquals(SourceOrigin.SOURCE, fn.origin());
  }

  @Test
  void explicitOriginIsReturned() {
    TacFunction libFn =
        new TacFunction(
            "m",
            "Lib#m()",
            TacType.VOID,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            SourceOrigin.LIBRARY);
    assertEquals(SourceOrigin.LIBRARY, libFn.origin());

    TacFunction sysFn =
        new TacFunction(
            "intern",
            "java.lang.String#intern()",
            TacType.STRING,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            SourceOrigin.SYSTEM_LIBRARY);
    assertEquals(SourceOrigin.SYSTEM_LIBRARY, sysFn.origin());
  }

  @Test
  void hasOriginInIsPermissiveForEmptySet() {
    TacFunction fn =
        new TacFunction(
            "m",
            "M#m()",
            TacType.VOID,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            SourceOrigin.LIBRARY);
    assertTrue(fn.hasOriginIn(null));
    assertTrue(fn.hasOriginIn(java.util.Set.of()));
    // Real filter set: LIBRARY not in the
    // SOURCE-only set, so the function is
    // filtered out.
    assertFalse(fn.hasOriginIn(java.util.Set.of(SourceOrigin.SOURCE)));
    // And the LIBRARY-set accepts it.
    assertTrue(fn.hasOriginIn(java.util.Set.of(SourceOrigin.LIBRARY)));
  }

  @Test
  void analysedByDefaultIsOnlySource() {
    assertTrue(SourceOrigin.SOURCE.analysedByDefault());
    assertFalse(SourceOrigin.LIBRARY.analysedByDefault());
    assertFalse(SourceOrigin.SYSTEM_LIBRARY.analysedByDefault());
  }

  @Test
  void nullOriginInConstructorIsNormalisedToSource() {
    // Defensive: a TacBuilder that hands in
    // null (e.g. from a refactor in flight)
    // should not produce a function whose
    // origin() is null — that would crash
    // any downstream code that does
    // origin.analysedByDefault() without a
    // null check.
    TacFunction fn =
        new TacFunction(
            "m",
            "M#m()",
            TacType.VOID,
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            java.util.Map.of(),
            java.util.Map.of(),
            null,
            null);
    assertNotNull(fn.origin());
    assertEquals(SourceOrigin.SOURCE, fn.origin());
  }
}
