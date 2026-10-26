package org.plumb.build.capture;

import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

/**
 * Hooks that make a Gradle build observable.
 *
 * <p>Gradle is the awkward case for a capture agent, for two reasons.
 *
 * <ol>
 *   <li><b>The daemon.</b> Gradle runs the build in a long-lived daemon process. The agent is
 *       attached to whatever JVM the user launched, and the compile happens somewhere else --
 *       in a daemon that may have been started hours earlier, with none of the agent's
 *       configuration. The compile is therefore invisible.
 *   <li><b>The build cache.</b> A cached compilation is not performed at all, so there is nothing
 *       to capture -- and the analyser then has no classpath for those sources.
 * </ol>
 *
 * <p>Coverity solves both by hooking Gradle's own configuration API and <em>changing</em> it:
 * the build cache is switched off, and the daemon is switched off so the build runs in the
 * foreground process the agent is attached to. The hooks are in Coverity's
 * {@code common-capture.properties}.
 *
 * <p>This class does the same thing. The two hooks are:
 *
 * <ul>
 *   <li>{@code org.gradle.StartParameter#setBuildCacheEnabled(boolean)} — forced to {@code false}.
 *   <li>{@code org.gradle.launcher.daemon.configuration.DaemonParameters#isEnabled()} — forced to
 *       {@code false}.
 * </ul>
 *
 * <p><b>The hooks are opt-in.</b> Turning the daemon off makes the build slower, and a user who
 * did not ask for a capture should not pay for it. The system property
 * {@code plumb.capture.gradle.disable-daemon} turns them on.
 */
final class GradleHooks {

  /** System property that enables the Gradle hooks. */
  static final String PROP_DISABLE_DAEMON = "plumb.capture.gradle.disable-daemon";

  /** The class whose build-cache flag is turned off. */
  private static final String START_PARAMETER = "org.gradle.StartParameter";

  /** The class whose daemon flag is turned off. */
  private static final String DAEMON_PARAMETERS =
      "org.gradle.launcher.daemon.configuration.DaemonParameters";

  private GradleHooks() {}

  /**
   * Forces the build cache off.
   *
   * <p>Advice rewrites the argument before the method body runs, so the method sees {@code false}
   * whatever the build asked for.
   */
  public static final class DisableBuildCache {

    private DisableBuildCache() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void enter(@Advice.Argument(value = 0, readOnly = false) boolean enabled) {
      enabled = false;
    }
  }

  /**
   * Forces the daemon off.
   *
   * <p>Advice rewrites the return value, so the caller sees {@code false} even though the build
   * asked for a daemon.
   */
  public static final class DisableDaemon {

    private DisableDaemon() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void exit(@Advice.Return(readOnly = false) boolean enabled) {
      enabled = false;
    }
  }

  /**
   * Install the Gradle hooks, if they are enabled.
   *
   * @param inst the instrumentation interface
   * @return true when the hooks were installed
   */
  static boolean install(Instrumentation inst) {
    if (!enabled()) {
      return false;
    }
    new AgentBuilder.Default()
        .disableClassFormatChanges()
        .with(AgentBuilder.Listener.NoOp.INSTANCE)
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(
            ElementMatchers.namedOneOf(
                START_PARAMETER, DAEMON_PARAMETERS))
        .transform(GradleHooks::instrument)
        .installOn(inst);
    return true;
  }

  /** True when the Gradle hooks are enabled. */
  static boolean enabled() {
    return Boolean.parseBoolean(System.getProperty(PROP_DISABLE_DAEMON, "false"));
  }

  private static DynamicType.Builder<?> instrument(
      DynamicType.Builder<?> builder,
      TypeDescription typeDescription,
      ClassLoader classLoader,
      JavaModule module,
      java.security.ProtectionDomain pd) {

    DynamicType.Builder<?> result = builder;
    if (START_PARAMETER.equals(typeDescription.getName())) {
      result =
          result.visit(
              Advice.to(DisableBuildCache.class)
                  .on(ElementMatchers.named("setBuildCacheEnabled")));
    } else if (DAEMON_PARAMETERS.equals(typeDescription.getName())) {
      result =
          result.visit(
              Advice.to(DisableDaemon.class)
                  .on(ElementMatchers.named("isEnabled").and(ElementMatchers.returns(boolean.class))));
    }
    return result;
  }

  /** The Gradle classes the hooks target, for the documentation and the tests. */
  static List<String> targetClasses() {
    return List.of(START_PARAMETER, DAEMON_PARAMETERS);
  }
}
