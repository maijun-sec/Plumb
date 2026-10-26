package org.plumb.dfa.cg;

import java.nio.file.Path;
import java.util.Objects;
import org.plumb.tac.ir.TacProgram;

/**
 * Factory for {@link CallGraphStrategy}.
 *
 * <p>Selected by name. The CLI flag {@code --cg-strategy} maps to:
 *
 * <ul>
 *   <li>{@code cha} -- {@link ChaCallGraphStrategy} (default, fast, conservative)
 *   <li>{@code rta} -- {@link RtaCallGraphStrategy} (slower, more precise)
 *   <li>{@code vta} -- {@link VtaCallGraphStrategy} (per-variable points-to sets)
 *   <li>{@code 1cfa} -- {@link OneCfaCallGraphStrategy} (VTA + per-function union filter)
 *   <li>{@code 2cfa} -- {@link TwoCfaCallGraphStrategy} (2-hop forward context widening: f's own
 *       points-to ∪ all direct callees' points-to)
 *   <li>{@code stub} -- {@link StubCallGraphStrategy} (only direct calls)
 *   <li>{@code cached:<name>:<path>} -- wraps {@code <name>} with a JSON cache at {@code <path>}.
 *       The cache key is the SHA-256 of the program's source list, so different source roots get
 *       separate caches automatically.
 * </ul>
 */
public final class CallGraphStrategyFactory {

  private CallGraphStrategyFactory() {}

  public static CallGraphStrategy create(String name, TacProgram program) {
    return create(name, program, null);
  }

  /**
   * Create a strategy. {@code cachePath} is only used for the {@code cached:<name>:<path>} spec.
   */
  public static CallGraphStrategy create(String name, TacProgram program, Path cachePath) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(program, "program");
    if (name.startsWith("cached:")) {
      // cached:innerName:cachePath
      String[] parts = name.split(":", 3);
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "cached strategy requires 'cached:<name>:<path>' " + "but got '" + name + "'");
      }
      String innerName = parts[1];
      Path innerPath = Path.of(parts[2]);
      CallGraphStrategy inner = create(innerName, program, null);
      return new CachedCallGraphStrategy(inner, innerPath);
    }
    switch (name.toLowerCase()) {
      case "cha":
        return new ChaCallGraphStrategy(program);
      case "rta":
        return new RtaCallGraphStrategy(program);
      case "vta":
        return new VtaCallGraphStrategy(program);
      case "1cfa":
        return new OneCfaCallGraphStrategy(program);
      case "2cfa":
        return new TwoCfaCallGraphStrategy(program);
      case "stub":
        return new StubCallGraphStrategy(program);
      default:
        throw new IllegalArgumentException(
            "Unknown call-graph strategy: "
                + name
                + " (expected cha | rta | vta | 1cfa | 2cfa | stub | cached:<name>:<path>)");
    }
  }

  // ===== Cross-TU factory =====

  /**
   * Create a cross-TU strategy from a {@link GlobalTACStore} spanning multiple source files. The
   * available strategies are:
   *
   * <ul>
   *   <li>{@code global} -- {@link GlobalCallGraphStrategy} (default, uses the global store to
   *       resolve callees across TUs)
   *   <li>{@code cached:global:<path>} -- wraps {@code global} with a JSON cache at {@code <path>}
   * </ul>
   *
   * <p>Unlike the single-TU factory, these strategies do not depend on a CHA / RTA analysis of the
   * receiver type at the call site: the global store already provides a complete callee index. The
   * strategy walks every function in the store and emits one {@code CallSite} per {@code CALL}
   * instruction.
   */
  public static CallGraphStrategy createCrossTu(String name, GlobalTACStore store, Path cachePath) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(store, "store");
    if (name.startsWith("cached:")) {
      String[] parts = name.split(":", 3);
      if (parts.length != 3) {
        throw new IllegalArgumentException(
            "cached strategy requires 'cached:<name>:<path>' " + "but got '" + name + "'");
      }
      String innerName = parts[1];
      Path innerPath = Path.of(parts[2]);
      CallGraphStrategy inner = createCrossTu(innerName, store, null);
      return new CachedCallGraphStrategy(inner, innerPath);
    }
    switch (name.toLowerCase()) {
      case "global":
        return new GlobalCallGraphStrategy(store);
      default:
        throw new IllegalArgumentException(
            "Unknown cross-TU call-graph strategy: "
                + name
                + " (expected global | cached:global:<path>)");
    }
  }
}
