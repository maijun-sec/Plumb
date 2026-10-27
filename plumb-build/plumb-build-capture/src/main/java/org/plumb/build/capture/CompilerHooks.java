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
   *
   * <p><b>The returned task is remembered too.</b> The task is what an IDE later drives with
   * {@code call()} or {@code generate()}, and those methods carry no options. Remembering the task
   * here is what lets the IDE path be captured at all -- the options cannot be read back off the
   * task later, because the {@code jdk.compiler} module does not export the package the task lives
   * in.
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
   * Hook for the task-creating {@code javax.tools} API entry point.
   *
   * <p>Same method as {@link ApiOptions}, but this one also remembers the task that was created,
   * so that the options can be attached to the compile the task eventually performs.
   *
   * <p><b>Why the return value is needed.</b> {@code JavacTaskImpl#call()} and {@code
   * JavacTaskImpl#generate()} take no options: they were set on the task when it was created. The
   * only place both the options and the task are visible at the same time is inside {@code
   * getTask}.
   */
  public static final class ApiOptionsWithTask {

    private ApiOptionsWithTask() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(
        @Advice.Argument(3) Iterable<?> options,
        @Advice.Argument(5) Iterable<?> sources,
        @Advice.Return Object task) {
      CaptureRecorder.rememberTask(
          task, CaptureRecorder.toStrings(options), CaptureRecorder.toPaths(sources));
    }
  }

  /**
   * Hook for the task-driven entry points, {@code JavacTaskImpl#call()} and {@code
   * JavacTaskImpl#generate()}.
   *
   * <p>Both are instance methods with no arguments, so the task is the receiver. The compiler
   * options were set on the task when it was created, so the hook looks them up from the ones
   * remembered then.
   *
   * <p><b>They cannot be read off the task directly.</b> {@code com.sun.tools.javac.api} is not
   * exported by the {@code jdk.compiler} module, so a reflective read of the task's options field
   * fails with {@code InaccessibleObjectException} unless the JVM runs with {@code --add-opens
   * jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED}. Requiring that flag would make the agent
   * unusable in an ordinary build.
   *
   * <p><b>This is the path an IDE takes.</b> A build tool calls {@code JavacTool#getTask} and then
   * {@code call()}; an IDE calls {@code getTask} and then {@code generate()}. Without this hook
   * the IDE path produced no capture at all.
   */
  public static final class TaskOptions {

    private TaskOptions() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.This Object task) {
      CaptureRecorder.onTask(task);
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
        case FILE_OBJECTS_WITH_TASK -> {
          // Two hooks on the same method: the options are captured on the way in,
          // and the task is remembered with them on the way out.
          result = result.visit(Advice.to(ApiOptions.class).on(matcher));
          result = result.visit(Advice.to(ApiOptionsWithTask.class).on(matcher));
        }
        case NO_OPTIONS -> {
          result = result.visit(Advice.to(TaskOptions.class).on(matcher));
        }
        default -> {
          // no other kinds
        }
      }
    }
    return result;
  }
}
