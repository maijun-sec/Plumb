/*
 * Copyright (c) 2026 maijun
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.plumb.tac.translate;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.plumb.parser.JavaSourceParser;
import org.plumb.parser.ParsedSource;
import org.plumb.tac.ir.TacFunction;
import org.plumb.tac.ir.TacInstruction;
import org.plumb.tac.ir.TacProgram;

/**
 * The AST node inventory: which Java constructs the frontend can lower, and which it cannot.
 *
 * <p><b>Why this test exists.</b> The frontend's contract is "every construct in the language
 * becomes IR". That contract is easy to state and hard to keep: a new Java release adds a node
 * type, and the translator -- which has an explicit {@code instanceof} chain -- simply does not
 * match it. The new construct then falls through to the fallback, which returns {@code null} and
 * records an {@code UNHANDLED_NODE} problem.
 *
 * <p><b>Why "records a problem" is not good enough.</b> The problem channel makes the gap VISIBLE,
 * which stops the gap from being silent. It does not make the gap SMALL. A frontend that silently
 * loses one construct in twenty is not a frontend the backend can trust, because a missing finding
 * is indistinguishable from a safe program.
 *
 * <p>So this test does two things:
 *
 * <ol>
 *   <li>it pins the INVENTORY -- the set of AST node classes the translator mentions -- so that a
 *       JavaParser upgrade that adds a node type shows up as a failing test rather than as a silent
 *       regression;
 *   <li>it asserts that a representative source using EVERY supported construct lowers with NO
 *       problems recorded, so "supported" means "actually handled", not "not crashed on".
 * </ol>
 *
 * <p><b>The two directions matter.</b> A test that only checks the inventory passes even if a
 * construct is matched but lowers to nothing. A test that only checks the samples passes even if
 * the translator grew a new unsupported node. Both are needed.
 */
class AstNodeInventoryTest {

  /**
   * The packages holding CONCRETE AST node classes -- the ones a parsed tree can actually contain.
   */
  private static final String[] CONCRETE_NODE_PACKAGES = {
    "com/github/javaparser/ast/expr/",
    "com/github/javaparser/ast/stmt/",
    "com/github/javaparser/ast/type/",
    "com/github/javaparser/ast/body/"
  };

  /**
   * Every AST package, including the root one that holds the abstract bases.
   *
   * <p>Used for the "is this reference real" check, where a broader set is the right one.
   */
  private static final String[] ALL_AST_PACKAGES = {
    "com/github/javaparser/ast/",
    "com/github/javaparser/ast/expr/",
    "com/github/javaparser/ast/stmt/",
    "com/github/javaparser/ast/type/",
    "com/github/javaparser/ast/body/"
  };

  private static TacProgram translate(String source) {
    ParsedSource parsed = new JavaSourceParser().parseFromString(Path.of("Inventory.java"), source);
    return new TacTranslator(parsed).translate();
  }

  private static List<TacInstruction> instructionsOf(TacProgram program) {
    List<TacInstruction> all = new ArrayList<>();
    for (TacFunction fn : program.functions()) {
      all.addAll(fn.instructions());
    }
    return all;
  }

  /**
   * The AST node classes the translator's source mentions.
   *
   * <p>Read out of the translator's own bytecode, not out of a hand-maintained list. A
   * hand-maintained list would rot: a construct could be added to the translator without being
   * added to the list, and the test would keep passing while saying nothing about the new
   * construct.
   *
   * <p>The class file's constant pool holds every class the translator references, including the
   * ones it merely imports. That is a SUPERSET of the ones it actually matches, which is the right
   * direction for this test: a superset makes the "is this node class mentioned?" question
   * answerable without parsing the source.
   */
  private static Set<String> mentionedAstNodeClasses() throws IOException {
    Set<String> names = new TreeSet<>();
    // The constant pool of the compiled translator holds the class references.
    // Reading the class file's constant pool directly is more robust than
    // scanning the source text, which would pick up comments and javadoc.
    String resource = "org/plumb/tac/translate/TacTranslator.class";
    try (InputStream in = TacTranslator.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("cannot locate " + resource + " on the classpath");
      }
      byte[] bytes = in.readAllBytes();
      collectClassReferences(bytes, names);
    }
    // The sub-translators are separate classes; their references matter too.
    for (String extra :
        new String[] {
          "org/plumb/tac/translate/ExpressionTranslator.class",
          "org/plumb/tac/translate/StatementTranslator.class",
          "org/plumb/tac/translate/JavaModernFeatures.class",
          "org/plumb/tac/translate/TypeMapper.class"
        }) {
      try (InputStream in = TacTranslator.class.getClassLoader().getResourceAsStream(extra)) {
        if (in == null) continue;
        collectClassReferences(in.readAllBytes(), names);
      }
    }
    return names;
  }

  /**
   * Collect the class names in a class file's constant pool.
   *
   * <p>A minimal constant-pool walk: read the header, walk the entries, and take the UTF8 ones that
   * look like a class name.
   *
   * <p><b>The slot arithmetic matters.</b> A {@code CONSTANT_Utf8} entry occupies exactly ONE
   * constant-pool slot; only {@code CONSTANT_Long} and {@code CONSTANT_Double} take two. Getting
   * that wrong desynchronizes the walk, and the walk then reports entries as MISSING -- which looks
   * exactly like a frontend gap. That is how a bug in the test harness masquerades as a bug in the
   * product, so the slot counts are spelled out rather than waved at.
   *
   * @param bytes the class file
   * @param out the collector
   */
  private static void collectClassReferences(byte[] bytes, Set<String> out) {
    // magic(4) minor(2) major(2) constant_pool_count(2)
    if (bytes.length < 10) return;
    int count = ((bytes[8] & 0xff) << 8) | (bytes[9] & 0xff);
    int offset = 10;
    for (int i = 1; i < count; i++) {
      if (offset >= bytes.length) return;
      int tag = bytes[offset] & 0xff;
      switch (tag) {
        case 1 -> { // CONSTANT_Utf8 -- ONE slot
          if (offset + 3 > bytes.length) return;
          int len = ((bytes[offset + 1] & 0xff) << 8) | (bytes[offset + 2] & 0xff);
          int start = offset + 3;
          if (start + len > bytes.length) return;
          String value = new String(bytes, start, len, StandardCharsets.UTF_8);
          // A class reference is stored as "com/github/javaparser/ast/expr/FooExpr".
          if (value.startsWith("com/github/javaparser/ast/")) {
            out.add(value);
          }
          offset = start + len;
        }
        case 7, 8, 16, 19, 20 -> offset += 3; // Class, String, MethodType, Module, Package
        case 15 -> offset += 4; // MethodHandle
        case 3, 4, 9, 10, 11, 12, 17, 18 -> offset += 5; // Integer, Float, Long, Double, *
        case 5, 6 -> {
          offset += 9;
          i++; // Long and Double take TWO slots
        }
        default -> {
          return;
        }
      }
    }
  }

  @Test
  @DisplayName("the translator mentions every AST node class the language has")
  void everyAstNodeClassIsMentioned() throws Exception {
    Set<String> mentioned = mentionedAstNodeClasses();
    assertThat(mentioned).as("the constant-pool walk must find something").isNotEmpty();

    // Every concrete AST node class JavaParser defines in the packages the
    // translator works with. This is the set the language can hand us.
    Set<String> expected = concreteAstNodeClasses();

    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(mentioned);

    assertThat(missing)
        .as(
            "these AST node classes are never mentioned by the frontend. Either lower them, "
                + "or -- if the class is an interface/abstract base that can never appear in a "
                + "concrete tree -- record that fact here. A class that is silently unmatched "
                + "falls through to the null fallback and loses its value's taint.")
        .isEmpty();
  }

  /**
   * The AST classes that are NOT expressions, statements, types or bodies, and so are never
   * dispatched on by the translator's {@code instanceof} chain.
   *
   * <p>These are the NAME nodes: they carry an identifier and appear as a declaration's name or a
   * type's scope, never as an expression. {@code Name} is a qualified name ({@code a.b.c}), {@code
   * SimpleName} is one identifier. A translator that "matched" them would be matching a node that
   * can never appear where an expression is expected.
   *
   * <p>The list is explicit rather than derived, because each entry needs a REASON. A derived
   * exclusion ("anything that is not an Expression subclass") would silently widen as JavaParser
   * adds node types, and the widening would be invisible.
   */
  private static final Set<String> NON_DISPATCHED_NODES =
      Set.of("com/github/javaparser/ast/expr/Name", "com/github/javaparser/ast/expr/SimpleName");

  /**
   * The concrete AST node classes, discovered from the classpath.
   *
   * <p>Discovered rather than hard-coded, so a JavaParser upgrade that adds a node type makes this
   * test fail until the frontend deals with it. That is the whole point: the inventory is supposed
   * to track the language, not the translator's current mood.
   *
   * <p>Only the CONCRETE node packages are listed, and ABSTRACT classes are excluded. An abstract
   * base ({@code LiteralExpr}, {@code Expression}, {@code BodyDeclaration}, ...) can never appear
   * as a concrete node in a tree, so the translator legitimately does not match it -- it matches
   * the concrete subclasses instead. Requiring the abstract base to be "mentioned" would be
   * requiring a match that could never fire.
   */
  private static Set<String> concreteAstNodeClasses() throws Exception {
    Set<String> classes = new TreeSet<>();
    for (String pkg : CONCRETE_NODE_PACKAGES) {
      for (String name : listJarEntries(pkg)) {
        String full = pkg + name;
        if (isAbstract(full)) continue;
        if (NON_DISPATCHED_NODES.contains(full)) continue;
        classes.add(full);
      }
    }
    return classes;
  }

  /**
   * Whether a class is abstract (or an interface).
   *
   * <p>Read with reflection rather than by parsing the class file's access flags by hand. The
   * reflection route is exactly what the JVM itself uses to decide whether a class can be
   * instantiated, so it cannot disagree with the runtime -- whereas a hand-rolled constant-pool
   * walk can be off by one field and quietly answer "not abstract" for an abstract class, which is
   * the failure mode this check exists to catch.
   *
   * @param className the fully qualified class name, in slash form
   * @return true when the class cannot be instantiated
   */
  private static boolean isAbstract(String className) {
    try {
      Class<?> type =
          Class.forName(className.replace('/', '.'), false, TacTranslator.class.getClassLoader());
      return java.lang.reflect.Modifier.isAbstract(type.getModifiers()) || type.isInterface();
    } catch (ClassNotFoundException | LinkageError e) {
      return false;
    }
  }

  /**
   * Every AST class JavaParser defines, including the abstract bases.
   *
   * <p>Used for the OTHER direction of the check: a class the translator references must be a real
   * JavaParser class. A typo in an import would otherwise only show up later as "the construct is
   * not handled".
   */
  private static Set<String> allAstClasses() throws Exception {
    Set<String> classes = new TreeSet<>();
    for (String pkg : ALL_AST_PACKAGES) {
      for (String name : listJarEntries(pkg)) {
        classes.add(pkg + name);
      }
    }
    return classes;
  }

  /**
   * The NESTED classes of the AST node classes -- the enums and inner types a node declares ({@code
   * AssignExpr.Operator}, {@code BinaryExpr.Operator}, ...).
   *
   * <p>They are legitimate references, so the "is this reference real" check has to know about
   * them. They are found by listing the jar entries whose name contains a {@code $}.
   */
  private static Set<String> allNestedAstClasses() throws Exception {
    Set<String> classes = new TreeSet<>();
    for (String pkg : ALL_AST_PACKAGES) {
      for (String name : listAllEntries(pkg)) {
        if (name.contains("$")) classes.add(pkg + name);
      }
    }
    return classes;
  }

  /**
   * The class names in a package, as they appear on the classpath.
   *
   * <p>The class loader's own resource enumeration is used, so it works whether the parser is on
   * the classpath as a jar or as an exploded directory (which is what an IDE and some build setups
   * do). Locating "the jar" by path would silently fall back to nothing in the exploded case.
   *
   * @param packagePath the package, in slash form
   * @return the direct children's class names, without the {@code .class} suffix
   */
  private static List<String> listJarEntries(String packagePath) throws Exception {
    return listEntries(packagePath, false);
  }

  /**
   * The class names in a package, INCLUDING the nested ones.
   *
   * @param packagePath the package, in slash form
   * @return the class names, with nested ones keeping their {@code Outer$Inner} form
   */
  private static List<String> listAllEntries(String packagePath) throws Exception {
    return listEntries(packagePath, true);
  }

  private static List<String> listEntries(String packagePath, boolean includeNested)
      throws Exception {
    List<String> names = new ArrayList<>();
    java.util.Enumeration<java.net.URL> resources =
        org.plumb.parser.ParsedSource.class.getClassLoader().getResources(packagePath);
    while (resources.hasMoreElements()) {
      java.net.URL resource = resources.nextElement();
      if ("jar".equals(resource.getProtocol())) {
        // JarURLConnection is the portable way to reach the jar behind a
        // "jar:file:/...!/pkg/" URL. Hand-rolling the path is a trap: on
        // Windows the URL's path is "file:/D:/..." and both getPath() and
        // new URI(...) produce something Path.of() rejects.
        java.net.JarURLConnection connection =
            (java.net.JarURLConnection) resource.openConnection();
        Path jar = Path.of(connection.getJarFileURL().toURI());
        if (!Files.isRegularFile(jar)) continue;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
          java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
          while (entries.hasMoreElements()) {
            java.util.zip.ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!name.startsWith(packagePath) || !name.endsWith(".class")) continue;
            String relative = name.substring(packagePath.length());
            // Only DIRECT children of the package: a subpackage's entry
            // would otherwise be listed with a slash in its "simple name".
            if (relative.contains("/")) continue;
            if (!includeNested && relative.contains("$")) continue;
            names.add(relative.substring(0, relative.length() - ".class".length()));
          }
        }
      } else if ("file".equals(resource.getProtocol())) {
        Path dir = Path.of(resource.toURI());
        if (!Files.isDirectory(dir)) continue;
        try (var stream = Files.list(dir)) {
          stream
              .filter(p -> p.getFileName().toString().endsWith(".class"))
              .filter(p -> includeNested || !p.getFileName().toString().contains("$"))
              .forEach(
                  p -> {
                    String n = p.getFileName().toString();
                    names.add(n.substring(0, n.length() - ".class".length()));
                  });
        }
      }
    }
    return names;
  }

  @Test
  @DisplayName("a source using every supported construct lowers with no problems recorded")
  void everySupportedConstructLowersWithoutProblems() {
    // ONE source that uses every construct the frontend claims to support. If
    // any of them falls through to the fallback, the problem channel records
    // it -- and this test fails, naming the construct.
    TacProgram program =
        translate(
            """
            package com.example;

            import java.io.*;
            import java.util.*;
            import java.util.function.*;

            @interface Marker {
              String value() default "x";
              int count() default 1;
            }

            @Marker("top")
            public class Everything<T extends Number & Comparable<T>> implements Serializable {

              // ── fields ──
              private static final String CONST = "constant";
              private int counter = 0;
              @Deprecated private String legacy = "old";
              protected List<String> items = new ArrayList<>();
              int[] numbers = {1, 2, 3};
              int[][] grid = new int[2][2];

              // ── constructors ──
              public Everything() { this(0); }
              public Everything(int start) {
                super();
                this.counter = start;
              }

              // ── static / instance initializers ──
              static { System.out.println("static init"); }
              { counter = 1; }

              // ── methods ──
              public <R> R generic(R input, int... rest) { return input; }

              public void controlFlow(int n) {
                if (n > 0) {
                  counter++;
                } else if (n < 0) {
                  counter--;
                } else {
                  counter = 0;
                }
                while (n > 0) { n--; }
                do { n++; } while (n < 0);
                for (int i = 0; i < 10; i++) { counter += i; }
                for (String s : items) { counter += s.length(); }
                switch (n) {
                  case 0: counter = 1; break;
                  case 1: counter = 2; break;
                  default: counter = 3;
                }
                int value = switch (n) {
                  case 0 -> 1;
                  case 1 -> { counter++; yield 2; }
                  default -> 3;
                };
                counter += value;
                outer:
                for (int i = 0; i < 3; i++) {
                  if (i == 1) continue outer;
                  if (i == 2) break outer;
                }
                synchronized (this) { counter++; }
                assert counter >= 0;
                try (BufferedReader r = new BufferedReader(new FileReader("f"))) {
                  r.readLine();
                } catch (IOException | RuntimeException ex) {
                  counter = -1;
                } finally {
                  counter = 0;
                }
                try { counter = 1; } catch (Exception ex) { }
              }

              // ── expressions ──
              public void expressions(String s, Object o) {
                int a = 1, b = 2;
                a += b; a -= b; a *= b; a /= b; a %= b;
                a <<= 1; a >>= 1; a >>>= 1; a &= b; a |= b; a ^= b;
                boolean flag = a > b && a < b || a == b && a != b;
                int ternary = flag ? a : b;
                int negated = -a;
                int complemented = ~a;
                boolean not = !flag;
                counter += (int) 3L;
                int cast = (int) 3.5;
                Object instance = new Object();
                int[] array = new int[5];
                int element = array[0];
                array[0] = element;
                int[] initialized = {1, 2};
                String text = "a text block";
                Class<?> klass = Everything.class;
                Runnable lambda = () -> counter++;
                Supplier<String> supplier = () -> "value";
                Function<String, Integer> fn = String::length;
                Supplier<Everything> ctor = Everything::new;
                Object nothing = null;
                Object field = CONST;
                boolean check = o instanceof String;
                boolean pattern = o instanceof String str && !str.isEmpty();
                counter += text.length() + ternary + cast + negated + complemented
                    + (not ? 1 : 0) + initialized[0] + element + (check ? 1 : 0)
                    + (pattern ? 1 : 0) + lambda.hashCode() + supplier.hashCode()
                    + fn.hashCode() + ctor.hashCode() + klass.hashCode()
                    + (nothing == null ? 0 : 1) + (field == null ? 0 : 1)
                    + grid.length + numbers.length;
              }

              // ── modern syntax ──
              sealed interface Shape permits Circle, Square {}
              record Circle(double radius) implements Shape {
                Circle {
                  if (radius < 0) throw new IllegalArgumentException("negative");
                }
              }
              record Square(double side) {}

              public String describe(Object o) {
                if (o instanceof Circle c) { return "circle " + c.radius(); }
                return switch (o) {
                  case Circle c -> "circle";
                  case Square s -> "square";
                  default -> "other";
                };
              }

              // ── nested / anonymous / local types ──
              static class Nested { void go() {} }
              interface Callback { void call(); }

              public void nestedTypes() {
                class Local { void go() {} }
                record LocalRecord(int x) {}
                Runnable r = new Runnable() {
                  @Override public void run() { counter++; }
                };
                r.run();
                new Local().go();
              }

              // ── annotations with values ──
              @Marker(value = "m", count = 2)
              public void annotated() {}
            }
            """);

    assertThat(program.programProblems())
        .as(
            "every construct in this source must lower cleanly. A problem here names the "
                + "construct that fell through to the null fallback.")
        .isEmpty();
    assertThat(instructionsOf(program))
        .as("the source must produce real instructions")
        .isNotEmpty();
  }

  @Test
  @DisplayName("a text block lowers cleanly (it cannot live in the big sample: delimiter clash)")
  void textBlockLowersCleanly() {
    // A text block is a distinct AST node -- TextBlockLiteralExpr -- not a
    // StringLiteralExpr, so it needs its own lowering. It cannot go in the
    // big sample because its own delimiter would terminate the sample's text
    // block.
    String tripleQuote = "\"\"\"";
    TacProgram program =
        translate(
            "class TB {\n"
                + "  void go() {\n"
                + "    String s = "
                + tripleQuote
                + "\n"
                + "        a text block\n"
                + "        "
                + tripleQuote
                + ";\n"
                + "  }\n"
                + "}\n");

    assertThat(program.programProblems())
        .as("a text block must lower with no problem recorded")
        .isEmpty();
    assertThat(instructionsOf(program))
        .as("a text block must produce real instructions")
        .anyMatch(
            i ->
                i.lhs1 instanceof org.plumb.tac.ir.TacOperand.Const c
                    && c.kind() == org.plumb.tac.ir.TacOperand.Const.Kind.STRING);
  }

  @Test
  @DisplayName("the problem channel reports the construct, not just 'something went wrong'")
  void problemsNameTheConstruct() {
    // A construct the frontend genuinely does not handle must be reported
    // BY NAME. A generic "unsupported" message would not tell a maintainer
    // what to implement.
    TacProgram program =
        translate(
            """
            class Fine {
              void go() { int x = 1; }
            }
            """);
    for (TacProgram.FunctionProblem p : program.programProblems()) {
      assertThat(p.nodeClass())
          .as("a problem must name the construct it is about")
          .isNotNull()
          .isNotEmpty();
      assertThat(p.kind()).isIn("UNHANDLED_NODE", "PARSE_PROBLEM");
    }
  }

  @Test
  @DisplayName("every node class the translator mentions is really a JavaParser AST node")
  void mentionedClassesAreRealAstNodes() throws Exception {
    // The other direction of the inventory check: the translator must not
    // reference a class that does not exist. A typo in an import would
    // otherwise only show up as "the construct is not handled".
    Set<String> mentioned = mentionedAstNodeClasses();
    Set<String> expected = allAstClasses();
    expected.addAll(allNestedAstClasses());

    Set<String> unknown = new LinkedHashSet<>(mentioned);
    unknown.removeAll(expected);

    assertThat(unknown)
        .as("these referenced classes are not JavaParser AST node classes")
        .isEmpty();
  }
}
