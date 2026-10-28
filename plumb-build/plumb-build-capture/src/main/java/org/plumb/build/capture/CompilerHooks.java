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
 * <p><b>This class must be public.</b> ByteBuddy's {@code Advice} inlines the hook's bytecode into
 * the target method, and the inlined code calls the advice class's method. A package-private advice
 * class cannot be called from the instrumented class, and ByteBuddy fails to apply the advice --
 * <b>silently</b>, because the failure surfaces as a transform error that a default agent swallows.
 *
 * <p><b>Why Advice and not a hand-written ASM visitor.</b> {@code Advice} is ByteBuddy's
 * <em>inlining</em> instrumentation: the hook's body is copied into the target method, so it needs
 * neither a super method nor a delegation target. It works on a plain static method such as {@code
 * Main#compile(String[])} exactly as well as on an instance method.
 *
 * <p>The alternative -- a raw {@code MethodVisitor} that emits {@code ALOAD} + {@code INVOKESTATIC}
 * by hand -- is more code and more fragile (the argument's local slot depends on whether the method
 * is static, and a mistake there corrupts the compiler's stack). {@code Advice} handles both. This
 * module contains no {@code net.bytebuddy.jar.asm} import at all.
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
   * Hook for Eclipse's compiler.
   *
   * <p>ECJ's options are not javac's options: it spells the classpath {@code --class-path}, and it
   * has options javac does not have ({@code -src}, {@code -profile}, {@code -log}). Parsing them
   * with javac's table would drop the classpath, which is the one thing a static analyser cannot do
   * without.
   */
  public static final class EcjArgv {

    private EcjArgv() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) String[] arguments) {
      CaptureRecorder.onArgv(arguments, CompilerOption.Compiler.ECJ);
    }
  }

  /**
   * Hook for the sjavac server.
   *
   * <p>sjavac has its own option set, and it normalizes how the arguments are attached to the
   * options ({@code SjavacOptions#normalizeArgumentAttachment}).
   */
  public static final class SjavacArgv {

    private SjavacArgv() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(0) String[] arguments) {
      CaptureRecorder.onArgv(arguments, CompilerOption.Compiler.SJAVAC);
    }
  }

  /**
   * Hook for the entry points whose compiler options are argument 1.
   *
   * <p>Kept for completeness: the {@code javax.tools} API's {@code getTask} family is bound
   * separately (see {@link ApiOptions}), but an entry point that carries the options in the second
   * position would use this.
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
   * <p>{@code JavacTool#getTask(Writer, FileManager, DiagnosticListener, Iterable options, Iterable
   * classes, Iterable files)} receives the compiler options in argument 3 and the source files in
   * argument 5.
   *
   * <p><b>The returned task is remembered too.</b> The task is what an IDE later drives with {@code
   * call()} or {@code generate()}, and those methods carry no options. Remembering the task here is
   * what lets the IDE path be captured at all -- the options cannot be read back off the task
   * later, because the {@code jdk.compiler} module does not export the package the task lives in.
   */
  public static final class ApiOptions {

    private ApiOptions() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(
        @Advice.Argument(3) Iterable<?> options, @Advice.Argument(5) Iterable<?> sources) {
      CaptureRecorder.onApiOptions(options, sources);
    }
  }

  /**
   * Hook for the task-creating {@code javax.tools} API entry point.
   *
   * <p>Same method as {@link ApiOptions}, but this one also remembers the task that was created, so
   * that the options can be attached to the compile the task eventually performs.
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
   * Hook for the IDE path.
   *
   * <p>{@code JavacTaskImpl#call()} and {@code JavacTaskImpl#generate()} take no options: they were
   * set on the task when it was created, by {@code JavacTool#getTask}. The options are looked up
   * from the ones remembered then.
   *
   * <p>This hook does not write the capture. It stages the compile's options, and the capture is
   * written when the compile itself ends -- by {@link CompileFinished}. A capture written here
   * would report the sources the compile was <em>given</em>, not the ones it really compiled.
   */
  public static final class TaskOptions {

    private TaskOptions() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.This Object task) {
      CaptureRecorder.onTask(task);
    }
  }

  /**
   * Hook for the compiler's compile step.
   *
   * <p>{@code JavaCompiler#compile(Collection, Collection, Iterable, Collection)} is the one
   * compile step both the command line and the {@code javax.tools} API go through. When it returns,
   * the compile has run, so the sources it really compiled are known -- including the ones it found
   * on the source path -- and the capture can be written.
   *
   * <p><b>Why the depth is counted.</b> The compiler's generate step is inside its compile step:
   * {@code compile} desugars and then calls {@code generate}. Both are hooked -- the IDE's {@code
   * JavacTaskImpl#generate()} path does not go through {@code compile} at all -- so without the
   * depth a compile that goes through {@code compile} would write two captures for one compile.
   * Only the outermost step writes.
   */
  public static final class CompileFinished {

    private CompileFinished() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter() {
      CaptureRecorder.enterCompileStep();
    }

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit() {
      if (!CaptureRecorder.exitCompileStep()) {
        // An inner step: the outer one writes the capture.
        return;
      }
      CaptureRecorder.onCompileFinished(CompilerOption.Compiler.JAVAC);
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
                // The logger is not enough on its own. The agent is often
                // attached to a build that has no SLF4J provider, and then
                // the warning goes nowhere -- and an advice that failed to
                // apply means the capture is silently incomplete. So the
                // failure is also reported on the compiler's own stderr.
                System.err.println(
                    "[plumb-capture] could not instrument " + typeName + ": " + throwable);
              }
            })
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(ElementMatchers.namedOneOf(CompilerEntryPoints.types().toArray(new String[0])))
        .transform(CompilerHooks::instrument)
        .installOn(inst);
  }

  /**
   * The advice class for an entry point that carries its options in argument 0.
   *
   * <p>The compiler decides, because the option tables differ: ECJ spells the classpath {@code
   * --class-path}, and parsing its arguments with javac's table would drop the classpath.
   */
  private static Class<?> argvAdvice(CompilerOption.Compiler compiler) {
    if (compiler == null) {
      return ArgvAtZero.class;
    }
    return switch (compiler) {
      case ECJ -> EcjArgv.class;
      case SJAVAC -> SjavacArgv.class;
      case JAVAC -> ArgvAtZero.class;
    };
  }

  /**
   * Weave the hooks into one type.
   *
   * <p>For each entry point declared on that type, the matching method gets the appropriate advice.
   */
  private static DynamicType.Builder<?> instrument(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      java.security.ProtectionDomain pd) {

    DynamicType.Builder<?> result = builder;
    for (CompilerEntryPoints.EntryPoint entry :
        CompilerEntryPoints.forType(typeDescription.getName())) {
      ElementMatcher.Junction<MethodDescription> matcher =
          ElementMatchers.named(entry.methodName());
      if (entry.argumentCount() >= 0) {
        matcher = matcher.and(ElementMatchers.takesArguments(entry.argumentCount()));
      }
      switch (entry.kind()) {
        case ARGV -> {
          Class<?> advice =
              switch (entry.optionsIndex()) {
                case 0 -> argvAdvice(entry.compiler());
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
          // The IDE path: the task carries no options of its own, so they
          // are looked up from the ones remembered at getTask.
          result = result.visit(Advice.to(TaskOptions.class).on(matcher));
        }
        case PARSED_SOURCE -> {
          // The compiler's parse step: the file being parsed is the source
          // the compiler really compiled.
          result = result.visit(Advice.to(CompiledSourcesAdvice.ParsedSource.class).on(matcher));
        }
        case COMPILE_END -> {
          // The compiler's compile step: the compile has run, so this is
          // where the capture is written with the sources it compiled.
          result = result.visit(Advice.to(CompileFinished.class).on(matcher));
        }
        default -> {
          // no other kinds
        }
      }
    }
    return result;
  }
}
