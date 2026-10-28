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
package org.plumb.build.capture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for the bootstrap class loader injection.
 *
 * <p><b>The bug this guards against is silent.</b> The recorder is injected into the bootstrap
 * class loader, which sees nothing but the JDK. If it references a class that is not in the
 * injected jar, the call fails with {@code NoClassDefFoundError} -- and the advice that calls it
 * suppresses {@code Throwable}, so the failure produces no output at all. The capture simply never
 * appears, and the agent looks installed and healthy.
 *
 * <p>That is not hypothetical: it happened when the recorder grew a dependency on {@link
 * JavacOptions} while the jar still carried only the recorder class.
 *
 * <p>The check is therefore structural: every type the bootstrap classes reference must be either
 * in the jar, in the JDK, or in the same package.
 */
class BootstrapInjectionTest {

  @Test
  void theBootstrapJarCarriesEveryClassTheRecorderNeeds() {
    // The list the agent actually injects must name the same classes.
    assertThat(CaptureAgent.bootstrapJarClasses())
        .containsExactly(
            "org/plumb/build/capture/CaptureRecorder.class",
            // The recorder's nested record is a class of its own, and the
            // bootstrap loader will not find it unless it is carried too.
            "org/plumb/build/capture/CaptureRecorder$PendingCompile.class",
            "org/plumb/build/capture/CaptureEntry.class",
            "org/plumb/build/capture/JavacOptions.class",
            "org/plumb/build/capture/JavacOptions$Builder.class",
            "org/plumb/build/capture/CompilerOption.class",
            "org/plumb/build/capture/CompilerOption$Compiler.class",
            "org/plumb/build/capture/CompilerOption$Value.class",
            "org/plumb/build/capture/CompilerOption$Repeat.class",
            "org/plumb/build/capture/CompilerOption$Effect.class",
            // The bridge the inlined advice calls: the advice runs inside
            // jdk.compiler, which cannot see the agent's own classes.
            "org/plumb/build/capture/CompiledSourcesBridge.class");
  }

  @Test
  void theBootstrapClassesExistInTheAgentJar() {
    for (String resource : CaptureAgent.bootstrapJarClasses()) {
      assertThat(readResource(resource)).as("the agent jar must contain %s", resource).isNotNull();
    }
  }

  @Test
  void theBootstrapClassesDoNotReferenceAnythingOutsideTheJar() throws Exception {
    Set<String> inJar = new LinkedHashSet<>();
    for (String resource : CaptureAgent.bootstrapJarClasses()) {
      inJar.add(
          resource.substring(resource.lastIndexOf('/') + 1, resource.length() - ".class".length()));
    }

    List<String> problems = new ArrayList<>();
    for (String resource : CaptureAgent.bootstrapJarClasses()) {
      String simpleName =
          resource.substring(resource.lastIndexOf('/') + 1, resource.length() - ".class".length());
      for (String referenced : referencedTypes(simpleName)) {
        String type = normalise(referenced);
        if (inJar.contains(type) || isJdkType(type) || isOwnPackageType(type)) {
          continue;
        }
        problems.add(
            simpleName + " references " + referenced + ", which is not in the bootstrap jar");
      }
    }
    assertThat(problems)
        .as(
            "a class the bootstrap loader cannot see fails with NoClassDefFoundError, "
                + "and the advice suppresses the error, so the capture silently never appears")
        .isEmpty();
  }

  @Test
  void theBootstrapClassesDoNotUseLogging() throws Exception {
    for (String resource : CaptureAgent.bootstrapJarClasses()) {
      String simpleName =
          resource.substring(resource.lastIndexOf('/') + 1, resource.length() - ".class".length());
      // A nested class has no source file of its own; it lives in its
      // outer class's file.
      String sourceName =
          simpleName.contains("$") ? simpleName.substring(0, simpleName.indexOf('$')) : simpleName;
      String source = readSource(sourceName);
      assertThat(source).as("%s must not use SLF4J", simpleName).doesNotContain("slf4j");
      assertThat(source).as("%s must not use a Logger", simpleName).doesNotContain("LoggerFactory");
    }
  }

  // ---------------- helpers ----------------

  private static byte[] readResource(String resource) {
    try (var in = BootstrapInjectionTest.class.getClassLoader().getResourceAsStream(resource)) {
      return in == null ? null : in.readAllBytes();
    } catch (IOException e) {
      return null;
    }
  }

  /** The source of one class in this package, or null. */
  private static String readSource(String simpleName) {
    java.nio.file.Path path =
        java.nio.file.Path.of(
            "src", "main", "java", "org", "plumb", "build", "capture", simpleName + ".java");
    try {
      return java.nio.file.Files.readString(path);
    } catch (IOException e) {
      return null;
    }
  }

  /**
   * The types a compiled class references, read from its constant pool.
   *
   * <p>The constant pool is the exact answer: a type mentioned only in a comment or a string
   * literal does not show up, and a type used as a field, a parameter, a local or a cast always
   * does. Scanning the source text instead produces false positives on every constant and every
   * javadoc -- the first version of this test did exactly that and reported 26 problems, none of
   * them real.
   */
  private static Set<String> referencedTypes(String simpleName) throws IOException {
    byte[] bytes = readResource("org/plumb/build/capture/" + simpleName + ".class");
    if (bytes == null) {
      Set<String> missing = new LinkedHashSet<>();
      missing.add("MISSING:" + simpleName);
      return missing;
    }
    Set<String> out = new LinkedHashSet<>();

    DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
    in.readInt(); // magic
    in.readUnsignedShort(); // minor version
    in.readUnsignedShort(); // major version
    int count = in.readUnsignedShort();

    String[] utf8 = new String[count];
    int[] classNameIndex = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> { // CONSTANT_Utf8
          int length = in.readUnsignedShort();
          byte[] raw = new byte[length];
          in.readFully(raw);
          utf8[i] = new String(raw, StandardCharsets.UTF_8);
        }
        case 7 -> classNameIndex[i] = in.readUnsignedShort(); // CONSTANT_Class
        case 8, 16, 19, 20 -> in.skipBytes(2); // String, MethodType, Module, Package
        case 15 -> in.skipBytes(3); // MethodHandle
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
        case 5, 6 -> {
          in.skipBytes(8);
          i++; // Long and Double occupy two slots.
        }
        default -> throw new IOException("unknown constant pool tag " + tag + " in " + simpleName);
      }
    }
    for (int i = 1; i < count; i++) {
      int nameIndex = classNameIndex[i];
      if (nameIndex == 0 || utf8[nameIndex] == null) {
        continue;
      }
      String internal = utf8[nameIndex];
      int slash = internal.lastIndexOf('/');
      out.add(slash >= 0 ? internal.substring(slash + 1) : internal);
    }
    return out;
  }

  /**
   * Reduce a constant-pool entry to the type name to check.
   *
   * <p>Three shapes have to be normalised:
   *
   * <ul>
   *   <li>an array descriptor — {@code [Ljava/lang/String;} becomes {@code String[]};
   *   <li>a nested class — {@code JavacOptions$Builder} keeps the {@code $}, because a nested class
   *       is a <em>separate class file</em> that has to be in the jar too. Stripping the {@code $}
   *       here is exactly the mistake that let {@code JavacOptions$Builder} go missing from the jar
   *       while {@code JavacOptions} was in it.
   *   <li>a plain class — {@code java/lang/String} becomes {@code String}.
   * </ul>
   */
  private static String normalise(String entry) {
    String name = entry;
    if (name.startsWith("[")) {
      // An array type: the element type is what matters.
      while (name.startsWith("[")) {
        name = name.substring(1);
      }
      if (name.startsWith("L") && name.endsWith(";")) {
        name = name.substring(1, name.length() - 1);
      }
      int slash = name.lastIndexOf('/');
      String element = slash >= 0 ? name.substring(slash + 1) : name;
      // A primitive element type is spelled with its descriptor letter, and
      // the letter is not the type's name.
      element =
          switch (element) {
            case "I" -> "int";
            case "J" -> "long";
            case "Z" -> "boolean";
            case "B" -> "byte";
            case "C" -> "char";
            case "S" -> "short";
            case "F" -> "float";
            case "D" -> "double";
            default -> element;
          };
      return element + "[]";
    }
    // A CONSTANT_Class for an array type carries the descriptor without the
    // leading '[' when the element is a primitive or when the pool entry is
    // the "String;" form.
    if (name.endsWith(";")) {
      name = name.substring(0, name.length() - 1);
    }
    return name;
  }

  /** The JDK types the bootstrap classes are allowed to use. */
  private static final List<String> JDK_TYPES =
      List.of(
          "String",
          "Integer",
          "Long",
          "Double",
          "Float",
          "Short",
          "Byte",
          "Character",
          "Boolean",
          "Number",
          "Object",
          "Class",
          "Exception",
          "RuntimeException",
          "IllegalArgumentException",
          "IllegalStateException",
          "NullPointerException",
          "IOException",
          "Throwable",
          "Error",
          "System",
          "Thread",
          "ThreadLocal",
          "Path",
          "Paths",
          "Files",
          "Optional",
          "Objects",
          "Arrays",
          "Collections",
          "List",
          "Map",
          "Set",
          // Map.values() returns a Collection, so a class that reads a map's
          // values names it.
          "Collection",
          "ArrayList",
          "LinkedHashSet",
          "LinkedHashMap",
          "HashMap",
          "HashSet",
          "Iterable",
          "URI",
          "IdentityHashMap",
          "WeakHashMap",
          "TreeMap",
          "TreeSet",
          "EnumMap",
          "EnumSet",
          "FileObject",
          "StandardCharsets",
          "Charset",
          "Void",
          "Override",
          "SuppressWarnings",
          "Deprecated",
          "StringBuilder",
          "Iterator",
          "FileAttribute",
          "OpenOption",
          "PrintStream",
          "StringConcatFactory",
          "MethodHandles",
          "Record",
          "ObjectMethods",
          "String[]",
          "StringBuilder[]",
          "Object[]",
          "Method",
          "ReflectiveOperationException",
          "MethodHandles$Lookup",
          "Locale",
          "Pattern",
          "NoSuchMethodException",
          // The bridge looks the recorder up on the bootstrap loader, which
          // is what makes the two copies of the recorder distinguishable.
          "ClassLoader",
          "ClassNotFoundException",
          // The pending compiles are held in a concurrent deque: a build that
          // compiles modules in parallel stages more than one compile.
          "Deque",
          "ConcurrentLinkedDeque",
          // The compile depth is counted in an int array, so the recorder
          // names the array type.
          "[I",
          "int[]",
          // Every enum extends java.lang.Enum, so an enum in the bootstrap
          // classes names it. LambdaMetafactory is what the compiler generates
          // for a switch expression over an enum.
          "Enum",
          "Enum[]",
          "LambdaMetafactory",
          "SwitchBootstraps");

  private static boolean isJdkType(String type) {
    return JDK_TYPES.contains(type);
  }

  /**
   * A type in this module's own package that is not in the jar would also be a problem.
   *
   * <p>Note that this is deliberately narrow: a NEW class in this package still has to be listed in
   * the bootstrap jar, because the bootstrap loader cannot see the package's other classes. The
   * earlier version of this test waved through anything starting with "Capture", which would have
   * let exactly the bug it was written to catch slip through.
   */
  private static boolean isOwnPackageType(String type) {
    return false;
  }
}
