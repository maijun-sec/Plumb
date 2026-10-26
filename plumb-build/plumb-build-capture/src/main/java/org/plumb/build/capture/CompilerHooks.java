package org.plumb.build.capture;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * The {@link Advice} hooks the agent weaves into the compiler.
 *
 * <p><b>This class must be public.</b> ByteBuddy's {@code Advice} inlines the hook's bytecode
 * into the target method, and the inlined code calls the advice class's method. A package-private
 * advice class cannot be called from the instrumented class, and ByteBuddy fails to apply the
 * advice -- <b>silently</b>, because the failure surfaces as a transform error that a default
 * agent swallows.
 *
 * <p><b>Why Advice and not a hand-written ASM visitor.</b> {@code Advice} is ByteBuddy's
 * <em>inlining</em> instrumentation: the hook's body is copied into the target method, so it needs
 * neither a super method nor a delegation target. It works on a plain static method such as
 * {@code Main#compile(String[])} exactly as well as on an instance method.
 *
 * <p>The alternative -- a raw {@code MethodVisitor} that emits {@code ALOAD} + {@code INVOKESTATIC}
 * by hand -- is more code and more fragile (the argument's local slot depends on whether the
 * method is static, and a mistake there corrupts the compiler's stack). {@code Advice} handles
 * both. This module contains no {@code net.bytebuddy.jar.asm} import at all.
 *
 * <p><b>One advice class per argument position.</b> {@code @Advice.Argument(N)} takes a constant
 * ordinal, so a hook that reads the compiler options from a different argument needs a different
 * advice class. There are only a few positions in play, so there are a few classes.
 */
public final class CompilerHooks {

  private CompilerHooks() {}

  /**
   * Hook for the entry points whose compiler options are argument 0.
   *
   * <p>Used by {@code com.sun.tools.javac.Main#compile(String[])}, by the ECJ driver and by the
   * sjavac drivers.
   */
  public static final class ArgvAtZero {

    private ArgvAtZero() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) String[] arguments) {
      CaptureRecorder.onArgv(arguments);
    }
  }

  /**
   * Hook for the entry points whose compiler options are argument 1.
   *
   * <p>Kept for completeness: the {@code javax.tools} API's {@code getTask} family is bound
   * separately (see {@link ApiOptions}), but an entry point that carries the options in the
   * second position would use this.
   */
  public static final class ArgvAtOne {

    private ArgvAtOne() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(1) String[] arguments) {
      CaptureRecorder.onArgv(arguments);
    }
  }

  /**
   * Hook for the entry points whose compiler options are argument 3.
   *
   * <p>Used by {@code javax.tools.JavaCompiler#run(InputStream, OutputStream, OutputStream,
   * String[])}.
   */
  public static final class ArgvAtThree {

    private ArgvAtThree() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(3) String[] arguments) {
      CaptureRecorder.onArgv(arguments);
    }
  }

  /**
   * Hook for the {@code javax.tools} API entry point.
   *
   * <p>{@code JavacTool#getTask(Writer, FileManager, DiagnosticListener, Iterable options,
   * Iterable classes, Iterable files)} receives the compiler options in argument 3 and the source
   * files in argument 5.
   */
  public static final class ApiOptions {

    private ApiOptions() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(3) Iterable<?> options,
        @Advice.Argument(5) Iterable<?> sources) {
      CaptureRecorder.onApiOptions(options, sources);
    }
  }

  /**
   * Install the agent.
   *
   * @param inst the instrumentation interface
   */
  static void install(Instrumentation inst) {
    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(
            new AgentBuilder.Listener.Adapter() {
              @Override
              public void onError(
                  String typeName,
                  ClassLoader classLoader,
                  net.bytebuddy.utility.JavaModule module,
                  boolean loaded,
                  Throwable throwable) {
                org.slf4j.LoggerFactory.getLogger(CompilerHooks.class)
                    .warn("Capture: could not instrument {}: {}", typeName, throwable.toString());
              }
            })
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(ElementMatchers.namedOneOf(CompilerEntryPoints.types().toArray(new String[0])))
        .transform(CompilerHooks::instrument)
        .installOn(inst);
  }
  /**
   * Weave the hooks into one type.
   *
   * <p>For each entry point declared on that type, the matching method gets the appropriate
   * advice.
   */
  private static DynamicType.Builder<?> instrument(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      java.security.ProtectionDomain pd) {

    DynamicType.Builder<?> result = builder;
    for (CompilerEntryPoints.EntryPoint entry : CompilerEntryPoints.forType(typeDescription.getName())) {
      ElementMatcher.Junction<MethodDescription> matcher =
          ElementMatchers.named(entry.methodName());
      if (entry.argumentCount() >= 0) {
        matcher = matcher.and(ElementMatchers.takesArguments(entry.argumentCount()));
      }
      switch (entry.kind()) {
        case ARGV -> {
          Class<?> advice =
              switch (entry.optionsIndex()) {
                case 0 -> ArgvAtZero.class;
                case 1 -> ArgvAtOne.class;
                case 3 -> ArgvAtThree.class;
                default -> null;
              };
          if (advice != null) {
            result = result.visit(Advice.to(advice).on(matcher));
          }
        }
        case FILE_OBJECTS -> {
          result = result.visit(Advice.to(ApiOptions.class).on(matcher));
        }
        default -> {
          // no other kinds
        }
      }
    }
    return result;
  }
}
