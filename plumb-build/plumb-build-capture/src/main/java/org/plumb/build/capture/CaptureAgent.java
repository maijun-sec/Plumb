package org.plumb.build.capture;

import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A ByteBuddy agent that records the javac command line.
 *
 * <p>It hooks {@code com.sun.tools.javac.main.Main#compile(String[])} -- the
 * entry point the {@code javac} command line driver reaches. That method
 * receives the raw argv, which is exactly what a static analyser needs to
 * reconstruct the compile.
 *
 * <h2>Why raw ASM instead of Advice</h2>
 *
 * <p>{@code Advice} and {@code MethodDelegation} both work by delegating to a
 * super method. {@code Main#compile} overrides nothing, so there is no super
 * method to delegate to and ByteBuddy raises
 * {@code Cannot call super (or default) method}. Instrumenting the bytecode
 * directly sidesteps that entirely: the original body is left untouched and a
 * call to {@link CaptureHook#onArgs(String[])} is inserted in front of it.
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

  /** The class whose {@code compile} method is hooked. */
  /**
   * The classes whose {@code compile} method is hooked.
   *
   * <p>There are two, and they cover different entry points:
   *
   * <ul>
   *   <li>{@code com.sun.tools.javac.Main} -- the {@code javac} command-line
   *       driver. Its {@code compile(String[])} is static.
   *   <li>{@code com.sun.tools.javac.main.Main} -- the class behind the {@code
   *       javax.tools} API. Its {@code compile(String[])} is an instance method.
   * </ul>
   *
   * <p>The two paths do not overlap: a command-line compile never reaches the
   * second, and an API compile never reaches the first. Both are hooked so the
   * capture works no matter how the compiler was invoked.
   */
  private static final String[] JAVAC_MAIN_CLASSES = {
    "com.sun.tools.javac.Main", "com.sun.tools.javac.main.Main"
  };

  /** The hook method the instrumented bytecode calls into. */
  private static final String HOOK_CLASS = "org/plumb/build/capture/CaptureHook";

  private static final String HOOK_METHOD = "onArgs";

  private static final String HOOK_DESCRIPTOR = "([Ljava/lang/String;)V";

  private CaptureAgent() {}

  /**
   * Agent entry point for {@code -javaagent:}.
   *
   * @param agentArgs agent arguments
   * @param inst the instrumentation interface
   */
  public static void premain(String agentArgs, Instrumentation inst) {
    install(inst);
  }

  /**
   * Agent entry point for dynamic attach.
   *
   * @param agentArgs agent arguments
   * @param inst the instrumentation interface
   */
  public static void agentmain(String agentArgs, Instrumentation inst) {
    install(inst);
  }

  /** Install the instrumentation. */
  public static void install(Instrumentation inst) {
    CaptureSink.get().configureFromEnvironment();
    injectHookClass(inst);
    LOG.info("Capture: installing agent");

    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.Listener.NoOp.INSTANCE)
        // The compiler's classes are usually already loaded when the agent
        // attaches, so a redefinition pass is required to reach them.
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(ElementMatchers.namedOneOf(JAVAC_MAIN_CLASSES))
        .transform(CaptureAgent::instrument)
        .installOn(inst);

    LOG.info("Capture: agent installed");
  }

  /**
   * Make the hook class visible to the compiler's own classes.
   *
   * <p>The instrumented bytecode lives in {@code com.sun.tools.javac.Main},
   * which belongs to the {@code jdk.compiler} module and is loaded by the
   * application class loader. The agent jar, meanwhile, is only visible to the
   * agent's own class loader -- so a direct call from the compiler's code to
   * the hook would fail with {@code NoClassDefFoundError}.
   *
   * <p>Appending a small jar to the <b>bootstrap</b> class loader solves it:
   * the bootstrap loader is the ancestor of every other loader, so the hook is
   * reachable from the compiler's code, from the application, and from the
   * agent alike.
   */
  private static void injectHookClass(Instrumentation inst) {
    try {
      java.io.File jar = buildHookJar();
      if (jar == null) {
        LOG.warn("Capture: could not build the hook jar, the capture may fail");
        return;
      }
      inst.appendToBootstrapClassLoaderSearch(new java.util.jar.JarFile(jar));
      LOG.debug("Capture: injected {} into the bootstrap class loader", jar);
    } catch (Exception e) {
      LOG.warn("Capture: could not inject the hook class: {}", e.getMessage());
    }
  }

  /**
   * Build a jar containing only the hook class, for the bootstrap loader.
   *
   * @return the jar file, or {@code null} when it could not be built
   */
  private static java.io.File buildHookJar() {
    try {
      String classResource = HOOK_CLASS.replace('.', '/') + ".class";
      java.io.InputStream in = CaptureAgent.class.getClassLoader().getResourceAsStream(classResource);
      if (in == null) {
        return null;
      }
      byte[] bytes;
      try (in) {
        bytes = in.readAllBytes();
      }

      java.io.File jar = java.io.File.createTempFile("plumb-capture-hook", ".jar");
      jar.deleteOnExit();
      try (java.util.jar.JarOutputStream out =
          new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jar))) {
        out.putNextEntry(new java.util.zip.ZipEntry(classResource));
        out.write(bytes);
        out.closeEntry();
      }
      return jar;
    } catch (Exception e) {
      LOG.debug("Capture: could not build the hook jar: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Build the bytecode transformation that inserts the capture hook.
   *
   * @param builder the type builder
   * @param typeDescription the type being transformed
   * @param classLoader its class loader
   * @param module its module
   * @param pd its protection domain
   * @return the builder, with the hook applied
   */
  private static net.bytebuddy.dynamic.DynamicType.Builder<?> instrument(
      net.bytebuddy.dynamic.DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      java.security.ProtectionDomain pd) {
    return builder.visit(
        new AsmVisitorWrapper.ForDeclaredMethods()
            .method(
                ElementMatchers.named("compile")
                    .and(ElementMatchers.takesArguments(String[].class)),
                new AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper() {
                  @Override
                  public MethodVisitor wrap(
                      TypeDescription instrumentedType,
                      MethodDescription methodDescription,
                      MethodVisitor methodVisitor,
                      Implementation.Context implementationContext,
                      TypePool typePool,
                      int writerFlags,
                      int readerFlags) {
                    LOG.debug("Capture: instrumenting {}", methodDescription);
                    // The argument's local slot depends on whether the method
                    // is static: slot 0 for a static method, slot 1 for an
                    // instance method (where slot 0 holds `this`).
                    int argumentSlot = methodDescription.isStatic() ? 0 : 1;
                    return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                      @Override
                      public void visitCode() {
                        super.visitCode();
                        super.visitVarInsn(Opcodes.ALOAD, argumentSlot);
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC, HOOK_CLASS, HOOK_METHOD, HOOK_DESCRIPTOR, false);
                      }
                    };
                  }
                }));
  }

  /**
   * Attach this agent to a running JVM.
   *
   * <p>The target is identified by its process id; the agent jar is located
   * from this class's own code source, so the caller only needs the pid.
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
}
