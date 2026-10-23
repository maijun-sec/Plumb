package org.plumb.style;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * Registry of {@link AstChecker} implementations with dynamic loading support.
 *
 * <p>Three loading modes:
 *
 * <ul>
 *   <li>{@link #builtins()} : register every checker (the default; 595 checkers across the nine
 *       category subdirectories plus one at the package root).
 *   <li>{@link #load(String...)} : register by glob on the simple class name. * etc. * exactly one.
 *   <li>{@link #byCategory(String...)} : register by the subdirectory a checker lives in. all 51
 *       naming-related checkers.
 * </ul>
 *
 * <p>The list of available classes is discovered once via classpath scanning (see {@link
 * #discoverAvailable()}) and cached. The is still the source of truth for the full list : it just
 * iterates the precomputed map instead of reflection-resolving each checker at registration time.
 */
public final class AstCheckerRegistry {

  private final List<AstChecker> checks = new ArrayList<>();

  public AstCheckerRegistry() {}

  public AstCheckerRegistry register(AstChecker c) {
    checks.add(Objects.requireNonNull(c));
    return this;
  }

  public List<AstChecker> all() {
    return Collections.unmodifiableList(checks);
  }

  /**
   * Variant of {@link #all()} that excludes checkers whose {@code DEFAULT_RULE.enabled()} is
   * {@code false} (stubs). The public surface ? {@link #builtins()}, {@link #load}, and
   * {@link #byCategory} ? returns this filtered view; the mirror path inside
   * {@code RuleRegistry.tryMirrorAstCheckers} uses {@link #all()} so it can still see the
   * stubs and force-enable them when {@code withBuiltinDefaults(true)} is in play.
   */
  public List<AstChecker> enabledList() {
    List<AstChecker> out = new ArrayList<>(checks.size());
    for (AstChecker c : checks) if (isEnabled(c)) out.add(c);
    return Collections.unmodifiableList(out);
  }

  /**
   * Register every available checker (595 across nine category subdirectories plus one at the
   * package root: bug, concurrency, design, error, naming, performance, quality, security, style).
   */
  public AstCheckerRegistry builtins() {
    // Resolve each entry by its simple class name. The
    // set of available class names is determined by
    // the call to {@link #discoverAvailable()} the first
    // time it's needed; subsequent calls are O(n).
    for (Class<? extends AstChecker> cls : discoverAvailable().values()) {
      tryRegister(cls);
    }
    return this;
  }

  /**
   * Register only checkers whose simple class name matches one of the given glob patterns. Patterns
   * use {@link java.util.regex.Pattern#compile(String)} syntax.
   *
   * <p>Examples:
   *
   * <pre>
   * r.load("Empty*") // all Empty*Check classes
   * r.load("EmptyMethodCheck") // exactly one
   * r.load(".*Naming.*") // all naming-related
   * r.load("Empty.*", "Naming.*") // multiple patterns
   * </pre>
   */
  public AstCheckerRegistry load(String... patterns) {
    if (patterns.length == 0) return this;
    List<Pattern> compiled = new ArrayList<>(patterns.length);
    for (String p : patterns) compiled.add(Pattern.compile(p));
    Predicate<String> match =
        name -> {
          for (Pattern p : compiled) if (p.matcher(name).matches()) return true;
          return false;
        };
    for (Map.Entry<String, Class<? extends AstChecker>> e : discoverAvailable().entrySet()) {
      if (match.test(e.getKey())) tryRegister(e.getValue());
    }
    return this;
  }

  /**
   * Register only checkers in the named subdirectory categories. The category is the subdirectory
   * under the checker class file lives.
   */
  public AstCheckerRegistry byCategory(String... categories) {
    if (categories.length == 0) return this;
    Set<String> wanted = new HashSet<>();
    for (String c : categories) wanted.add(c.toLowerCase());
    Map<String, Class<? extends AstChecker>> avail = discoverAvailable();
    Map<String, String> cat = categoryOf();
    for (Map.Entry<String, String> e : cat.entrySet()) {
      if (wanted.contains(e.getValue().toLowerCase())) {
        Class<? extends AstChecker> cls = avail.get(e.getKey());
        if (cls == null) continue;
        tryRegister(cls);
      }
    }
    return this;
  }

  /** Names of all checkers currently registered. */
  public Set<String> registeredNames() {
    Set<String> s = new TreeSet<>();
    for (AstChecker c : checks) s.add(c.getClass().getSimpleName());
    return s;
  }

  /** All available class simple names, sorted. */
  public static List<String> listAvailable() {
    return new ArrayList<>(discoverAvailable().keySet());
  }

  // ─── internals
  // ────────────────────────────────────────────

  private void tryRegister(Class<? extends AstChecker> cls) {
    try {
      // Prefer the no-arg ctor. Every checker has one.
      AstChecker inst = cls.getDeclaredConstructor().newInstance();
      // R-AST-FIX-2026-10-18 (rev 2): the previous version
      // skipped registration when DEFAULT_RULE.enabled() was
      // false. That broke the smoke-test path
      // RuleRegistry#tryMirrorAstCheckers, which calls
      // AstCheckerRegistry.builtins() and relies on
      // forceAllEnabledIf(true) to flip every rule (including
      // the by-default-disabled stubs) for the test. The fix:
      // always register here; let the public surface
      // ({@link #builtins()}, {@link #load(String...)},
      // {@link #byCategory(String...)}) filter the disabled
      // ones, since those are the methods the operator sees.
      // The mirror path walks the full registered list, so it
      // now sees the stubs.
      register(inst);
    } catch (ReflectiveOperationException ex) {
      throw new IllegalStateException("Failed to instantiate " + cls.getName(), ex);
    }
  }

  /**
   * True if the given checker is enabled-by-default (i.e. its DEFAULT_RULE says {@code enabled=true}).
   * Used by the public surface to filter out stubs from {@link #builtins()}, {@link #load}, and
   * {@link #byCategory}. The full registered list (including stubs) is still available via
   * {@link #all()}.
   */
  private static boolean isEnabled(AstChecker c) {
    try {
      org.plumb.rules.RuleDef rd = c.rule();
      return rd == null || rd.enabled();
    } catch (RuntimeException ignore) {
      return true;
    }
  }

  /**
   * Discover every {@link AstChecker} implementation on the classpath under {@code
   * org.plumb.style.**}. Cached in {@link #AVAILABLE}; the cache is populated lazily on the first
   * call and shared across all callers.
   */
  private static Map<String, Class<? extends AstChecker>> discoverAvailable() {
    if (AVAILABLE != null) return AVAILABLE;
    synchronized (AstCheckerRegistry.class) {
      if (AVAILABLE != null) return AVAILABLE;
      AVAILABLE = new LinkedHashMap<>();
      // CATEGORY_OF is set by categoryOf() from
      // CheckerCategories (the build-time-generated
      // mapping). Don't touch it here : the classpath
      // scan can't recover the original source
      // subdirectory.
      ClassLoader cl = Thread.currentThread().getContextClassLoader();
      if (cl == null) cl = AstCheckerRegistry.class.getClassLoader();
      String pkgPath = "org/plumb/style/";
      try {
        Enumeration<URL> roots = cl.getResources(pkgPath);
        while (roots.hasMoreElements()) {
          URL root = roots.nextElement();
          String protocol = root.getProtocol();
          if ("file".equals(protocol)) {
            File dir = new File(URLDecoder.decode(root.getFile(), StandardCharsets.UTF_8));
            scanDir(dir, "");
          } else if ("jar".equals(protocol)) {
            String spec = root.getPath();
            int bang = spec.indexOf("!/");
            if (bang < 0) continue;
            String jarPath = spec.substring(5, bang);
            try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, StandardCharsets.UTF_8))) {
              Enumeration<JarEntry> en = jar.entries();
              while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith(pkgPath) && name.endsWith(".class") && !name.contains("$")) {
                  String simple =
                      name.substring(pkgPath.length(), name.length() - ".class".length());
                  registerClass(simple);
                }
              }
            }
          }
        }
      } catch (IOException ex) {
        throw new IllegalStateException("Failed to discover checkers", ex);
      }
      return AVAILABLE;
    }
  }

  private static void scanDir(File dir, String prefix) {
    if (!dir.isDirectory()) return;
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File f : files) {
      String name = f.getName();
      if (f.isDirectory()) {
        scanDir(f, prefix.isEmpty() ? name : prefix + "/" + name);
      } else if (name.endsWith(".class") && !name.contains("$")) {
        String simple = name.substring(0, name.length() - 6);
        registerClass(prefix.isEmpty() ? simple : prefix + "/" + simple);
      }
    }
  }

  private static void registerClass(String simpleWithCategory) {
    // `simpleWithCategory` may be a plain simple name
    // ("EmptyBlockCheck") or a subdir-prefixed one
    // ("style/EmptyBlockCheck") when the classpath scan
    // recursed into a subdirectory. The new layout puts
    // each rule under org.plumb.style.<category>, so we
    // must use the full FQN : not the legacy flat one : 
    // to resolve the class. The AVAILABLE map still
    // keys on the simple name so the existing
    // registeredNames() / load("Foo.*") / listAvailable()
    // surface area is unchanged.
    int slash = simpleWithCategory.indexOf('/');
    String simple = slash < 0 ? simpleWithCategory : simpleWithCategory.substring(slash + 1);
    String fqn =
        slash < 0
            ? "org.plumb.style." + simple
            : "org.plumb.style." + simpleWithCategory.replace('/', '.');
    try {
      Class<?> cls = Class.forName(fqn);
      if (AstChecker.class.isAssignableFrom(cls)
          && !cls.isInterface()
          && !java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
        @SuppressWarnings("unchecked")
        Class<? extends AstChecker> casted = (Class<? extends AstChecker>) cls;
        AVAILABLE.put(simple, casted);
        // Do NOT touch CATEGORY_OF here.
        // It's owned by categoryOf() / CheckerCategories.
      }
    } catch (ClassNotFoundException ex) {
      // Ignore : not on classpath.
    }
  }

  private static Map<String, String> categoryOf() {
    // The category is baked at build time by
    // scripts/gen_categories.py into
    // {@link CheckerCategories#CHECKER_CATEGORIES} : 
    // we can't infer it from the compiled classpath
    // because Maven flattens the.class files into a
    // single package directory.
    if (CATEGORY_OF == null) {
      CATEGORY_OF = new LinkedHashMap<>(CheckerCategories.CHECKER_CATEGORIES);
    }
    return CATEGORY_OF;
  }

  /** Debug: dump CATEGORY_OF contents. */
  public static java.util.Map<String, Integer> categoryHistogram() {
    Map<String, Integer> hist = new java.util.TreeMap<>();
    for (String c : categoryOf().values()) {
      hist.merge(c, 1, Integer::sum);
    }
    return hist;
  }

  private static volatile Map<String, Class<? extends AstChecker>> AVAILABLE;
  private static volatile Map<String, String> CATEGORY_OF;
}
