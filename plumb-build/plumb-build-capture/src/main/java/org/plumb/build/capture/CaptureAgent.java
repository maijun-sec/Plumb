package org.plumb.build.capture;

import java.lang.instrument.Instrumentation;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ByteBuddy agent that captures the real javac compile.
 *
 * <p>Some projects cannot be introspected statically: the classpath may be assembled by a script,
 * a plugin, or an IDE. For those, the ground truth is the compile the compiler actually performed.
 * This agent hooks the compiler's entry points and records it.
 *
 * <h2>Which entry points</h2>
 *
 * <p>See {@link CompilerEntryPoints} for the list. The short version: a "javac command line" is
 * eight different methods depending on how the compiler was reached, and hooking only one
 * of them means the capture silently misses every build that takes a different path.
 *
 * <h2>How</h2>
 *
 * <p>ByteBuddy's {@link net.bytebuddy.asm.Advice} does the instrumentation. Advice is
 * <em>inlining</em>: the hook body is copied into the target method, so it needs no super method
 * and no delegation target. That is what makes it work on a plain static method like {@code
 * Main#compile(String[])}.
 *
 * <p>The original project this descends from used btrace, which needs elevated privileges and a
 * native agent. ByteBuddy needs neither: it attaches through the standard {@code
 * java.lang.instrument} API, so the capture runs as an ordinary user process.
 *
 * <h2>Usage</h2>
 *
 * <pre>
 *   java -javaagent:plumb-build-capture.jar \
 *        -DPLUMB_CAPTURE_DIR=/tmp/captures \
 *        -cp ... com.sun.tools.javac.Main -d out Foo.java
 * </pre>
 *
 * <p>or, to attach to a running compiler:
 *
 * <pre>
 *   java -cp plumb-build-capture.jar org.plumb.build.capture.CaptureAgent attach &lt;pid&gt;
 * </pre>
 */
public final class CaptureAgent {

  private static final Logger LOG = LoggerFactory.getLogger(CaptureAgent.class);

  /**
   * The classes that go into the bootstrap jar.
   *
   * <p><b>This list has to be the recorder's whole dependency closure, including nested
   * classes.</b> The recorder runs on the bootstrap class loader, which sees nothing but the JDK.
   * If it references a class that is not in the jar, the call fails with {@code
   * NoClassDefFoundError} -- and because the advice that calls it suppresses {@code Throwable},
   * the failure is completely silent: the capture simply never appears.
   *
   * <p>That is not hypothetical. It happened three times:
   *
   * <ul>
   *   <li>the recorder grew a dependency on {@link JavacOptions} while the jar carried only the
   *       recorder class;
   *   <li>the jar then carried {@code JavacOptions} but not {@code JavacOptions$Builder}, its
   *       nested builder;
   *   <li>the recorder grew a dependency on {@link CompilerOption} when the option table became
   *       data-driven, and the jar did not follow.
   * </ul>
   *
   * <p>Every class listed here must be free of external dependencies (no SLF4J, no JSON library)
   * and free of nested classes that are not listed too.
   */
  private static final String[] BOOTSTRAP_CLASSES = {
    "org/plumb/build/capture/CaptureRecorder",
    "org/plumb/build/capture/CaptureEntry",
    "org/plumb/build/capture/JavacOptions",
    "org/plumb/build/capture/JavacOptions$Builder",
    "org/plumb/build/capture/CompilerOption",
    "org/plumb/build/capture/CompilerOption$Compiler",
    "org/plumb/build/capture/CompilerOption$Value",
    "org/plumb/build/capture/CompilerOption$Repeat",
    "org/plumb/build/capture/CompilerOption$Effect",
  };

  private CaptureAgent() {}

  /**
   * Agent entry point for {@code -javaagent:}.
   *
   * @param agentArgs agent arguments
   * @param inst the instrumentation interface
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    install(inst, agentArgs);
  }

  /**
   * Agent entry point for dynamic attach.
   *
   * @param agentArgs agent arguments
   * @param inst the instrumentation interface
   */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    install(inst, agentArgs);
  }

  /**
   * Install the instrumentation.
   *
   * @param inst the instrumentation interface
   * @param agentArgs the agent's own arguments, or {@code null}
   */
  public static void install(Instrumentation inst) {
    install(inst, null);
  }

  /**
   * Install the instrumentation.
   *
   * @param inst the instrumentation interface
   * @param agentArgs the agent's own arguments, or {@code null}
   */
  public static void install(Instrumentation inst, String agentArgs) {
    applyAgentArguments(agentArgs);
    CaptureSink.get().configureFromEnvironment();
    injectRecorder(inst);
    LOG.info("Capture: installing agent");
    CompilerHooks.install(inst);
    LOG.info(
        "Capture: agent installed, hooking {} entry point(s)", CompilerEntryPoints.all().size());
    if (GradleHooks.install(inst)) {
      LOG.info(
          "Capture: Gradle hooks installed (build cache and daemon disabled), so the "
              + "compile happens in this JVM");
    }
  }

  /**
   * Apply the agent's own arguments as system properties.
   *
   * <p><b>Why the agent needs its own arguments.</b> Gradle does not forward system properties to
   * the JVM it builds in. Measured on Gradle 9.2.0: {@code org.gradle.jvmargs} keeps the {@code
   * -javaagent} and drops every {@code -D}; {@code systemProp.} in {@code gradle.properties} does
   * not reach the forked daemon either. So a system property is not a way to configure the agent
   * inside Gradle.
   *
   * <p>The agent's own arguments are. {@code -javaagent:jar=key=value,key2=value2} hands them to
   * {@code premain}, and {@code -javaagent} is the one JVM argument Gradle does forward. So the
   * agent's arguments become system properties, which makes the agent configurable inside Gradle
   * without a system property ever being set.
   *
   * @param agentArgs the agent's arguments, or {@code null}
   */
  static void applyAgentArguments(String agentArgs) {
    if (agentArgs == null || agentArgs.isBlank()) {
      return;
    }
    for (String pair : agentArgs.split(",")) {
      int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      String key = pair.substring(0, equals).trim();
      String value = pair.substring(equals + 1).trim();
      if (key.isEmpty()) {
        continue;
      }
      if (System.getProperty(key) == null) {
        // An explicit system property still wins: it was set deliberately.
        System.setProperty(key, value);
      }
    }
  }

  /**
   * Make the recorder visible to the compiler's own classes.
   *
   * <p>The instrumented bytecode lives in {@code com.sun.tools.javac.Main}, which belongs to the
   * {@code jdk.compiler} module and is loaded by the application class loader. The agent jar, meanwhile,
   * is only visible to the agent's own class loader -- so a direct call from the compiler's code to
   * the recorder would fail with {@code NoClassDefFoundError}.
   *
   * <p>Appending a small jar to the <b>bootstrap</b> class loader solves it: the bootstrap loader is
   * the ancestor of every other loader, so the recorder is reachable from the compiler's code, from
   * the application, and from the agent alike.
   */
  private static void injectRecorder(Instrumentation inst) {
    try {
      java.io.File jar = buildRecorderJar();
      if (jar == null) {
        LOG.warn("Capture: could not build the recorder jar, the capture may fail");
        return;
      }
      inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar));
      LOG.debug("Capture: injected {} into the bootstrap class loader", jar);
    } catch (Exception e) {
      LOG.warn("Capture: could not inject the recorder class: {}", e.getMessage());
    }
  }

  /**
   * Build a jar containing the recorder and everything it needs, for the bootstrap loader.
   *
   * <p>Only the recorder's own classes go in: they are the ones the instrumented bytecode calls,
   * and they are the ones that have to resolve on the bootstrap loader. The recorder must not
   * reference anything outside the bootstrap loader, which is why it has no logging and no
   * dependency on the rest of the module.
   *
   * @return the jar file, or {@code null} when it could not be built
   */
  private static java.io.File buildRecorderJar() {
    try {
      java.io.File jar = java.io.File.createTempFile("plumb-capture-recorder", ".jar");
      jar.deleteOnExit();
      try (java.util.jar.JarOutputStream out =
          new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jar))) {
        for (String classResource : bootstrapJarClasses()) {
          byte[] bytes = readClassBytes(classResource);
          if (bytes == null) {
            LOG.warn("Capture: {} is missing from the agent jar", classResource);
            continue;
          }
          out.putNextEntry(new java.util.zip.ZipEntry(classResource));
          out.write(bytes);
          out.closeEntry();
        }
      }
      return jar;
    } catch (Exception e) {
      LOG.debug("Capture: could not build the recorder jar: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The class resources that go into the bootstrap jar.
   *
   * <p>Each one is looked up in the agent's own class loader. A class that cannot be found is
   * reported rather than silently skipped, because a missing class in this jar means the capture
   * fails silently at run time.
   */
  static java.util.List<String> bootstrapJarClasses() {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (String name : BOOTSTRAP_CLASSES) {
      out.add(name + ".class");
    }
    return out;
  }

  /** The bytes of one class, or {@code null} when it is not on the agent's classpath. */
  private static byte[] readClassBytes(String classResource) {
    try (java.io.InputStream in =
        CaptureAgent.class.getClassLoader().getResourceAsStream(classResource)) {
      if (in == null) {
        return null;
      }
      return in.readAllBytes();
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Attach this agent to a running JVM.
   *
   * <p>The target is identified by its process id; the agent jar is located from this class's own
   * code source, so the caller only needs the pid.
   *
   * @param args the target process id
   * @throws Exception if the attachment fails
   */
  public static void main(String[] args) throws Exception {
    if (args == null || args.length == 0) {
      System.err.println("usage: CaptureAgent <pid>");
      System.err.println("  attaches the javac capture agent to the given process");
      System.exit(2);
    }
    long pid = Long.parseLong(args[0]);

    java.net.URI location =
        CaptureAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
    java.io.File agentJar = new java.io.File(location);
    if (!agentJar.isFile()) {
      System.err.println("Capture: cannot locate the agent jar at " + agentJar);
      System.exit(2);
    }

    net.bytebuddy.agent.ByteBuddyAgent.attach(agentJar, Long.toString(pid));
    System.out.println("Capture: attached to pid " + pid);
  }

  /** The entry points the agent hooks. Exposed for the tests and for documentation. */
  public static List<CompilerEntryPoints.EntryPoint> entryPoints() {
    return CompilerEntryPoints.all();
  }
}
