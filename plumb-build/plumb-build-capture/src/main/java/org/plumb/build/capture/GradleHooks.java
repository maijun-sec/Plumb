package org.plumb.build.capture;

import java.lang.instrument.Instrumentation;
import java.util.List;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
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
 * {@code plumb.capture.gradle.disable-daemon} turns them on, and so does the environment variable
 * {@code PLUMB_CAPTURE_GRADLE_HOOKS}.
 *
 * <p><b>Which channel reaches Gradle's JVM.</b> This is not obvious, and getting it wrong makes
 * the hooks silently inert. Measured on Gradle 9.2.0, by reading the command line of the JVM Gradle
 * forked:
 *
 * <table>
 *   <caption>How each channel reaches the JVM Gradle builds in</caption>
 *   <tr><th>Channel</th><th>Reaches the Gradle JVM?</th></tr>
 *   <tr><td>{@code org.gradle.jvmargs} with {@code -javaagent}</td><td>yes</td></tr>
 *   <tr><td>{@code org.gradle.jvmargs} with {@code -D}</td><td><b>no</b> -- every {@code -D} is
 *       dropped</td></tr>
 *   <tr><td>{@code GRADLE_OPTS}</td><td><b>no</b> -- it applies to the launcher, not the daemon</td></tr>
 *   <tr><td>{@code systemProp.name=value} in {@code gradle.properties}</td><td><b>no</b> -- it
 *       applies to the build JVM, and the compile runs in the forked daemon</td></tr>
 *   <tr><td>the agent's own arguments ({@code -javaagent:jar=key=value})</td><td>yes</td></tr>
 *   <tr><td>the environment</td><td>yes -- the JVM inherits it</td></tr>
 * </table>
 *
 * <p>So the way to enable the hooks inside Gradle is to pass them as the agent's arguments:
 *
 * <pre>
 *   org.gradle.jvmargs=-Xmx1g \
 *     -javaagent:/path/plumb-build-capture.jar=plumb.capture.gradle.disable-daemon=true
 * </pre>
 *
 * <p>The agent turns its arguments into system properties, so the property is then set inside the
 * JVM that actually performs the compile. The environment variable is the second channel, for the
 * case where the agent is not attached through {@code -javaagent}.
 *
 * <p><b>Why there is no Gradle-specific classpath hook.</b> It is tempting to hook
 * {@code DefaultJvmLanguageCompileSpec#setCompileClasspath(List<File>)}, because that is where
 * Gradle hands its resolved classpath to the compiler. The measurement says otherwise.
 *
 * <p>Gradle assembles the compile in the daemon, but <em>performs</em> it in the same JVM through
 * {@code javax.tools}. So once the agent is in that JVM -- which the daemon hook above guarantees --
 * the existing {@code JavacTool#getTask} hook already captures the effective compile, including the
 * fully resolved classpath with the upstream modules' output directories.
 *
 * <p>A Gradle-specific hook would be strictly worse: the compile spec class never loads in the
 * daemon JVM that holds the agent (it lives in the {@code gradle-language-jvm} jar, which is
 * loaded lazily into a separate class loader), so the hook would never fire; and even if it did,
 * it would record a second, worse copy of a compile the compiler hook already recorded exactly.
 * One record per compile is the invariant the capture reader relies on.
 *
 * @see CompilerHooks for the hook that actually records the compile
 */
final class GradleHooks {

  /** System property that enables the Gradle hooks. */
  static final String PROP_DISABLE_DAEMON = "plumb.capture.gradle.disable-daemon";

  /**
   * Environment variable that enables the Gradle hooks.
   *
   * <p>The environment is the one channel that reaches Gradle's own JVM: {@code
   * org.gradle.jvmargs} drops every {@code -D} option, but the JVM inherits the environment of the
   * process that started it.
   */
  static final String ENV_DISABLE_DAEMON = "PLUMB_CAPTURE_GRADLE_HOOKS";

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
        .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
        .type(ElementMatchers.namedOneOf(START_PARAMETER, DAEMON_PARAMETERS))
        .transform(GradleHooks::instrument)
        .installOn(inst);
    return true;
  }

  /**
   * True when the Gradle hooks are enabled.
   *
   * <p><b>Two channels, because one is not enough.</b> The system property is the natural one, but
   * Gradle does not forward arbitrary {@code -D} options to the JVM it builds in: {@code
   * org.gradle.jvmargs} is filtered, and only options Gradle recognises survive. Measured on
   * Gradle 9.2.0: {@code -javaagent} survives, every {@code -D} is dropped.
   *
   * <p>The environment variable survives, because the Gradle JVM inherits the environment of the
   * process that started it. So the property is the way to enable the hooks when the agent runs
   * inside Gradle's own JVM, and the environment variable is the way to enable them from outside.
   *
   * <p>The property must be set with Gradle's {@code systemProp.} prefix in {@code
   * gradle.properties}, not through {@code org.gradle.jvmargs}:
   *
   * <pre>
   *   systemProp.plumb.capture.gradle.disable-daemon=true
   * </pre>
   */
  static boolean enabled() {
    String property = System.getProperty(PROP_DISABLE_DAEMON);
    if (property != null && Boolean.parseBoolean(property)) {
      return true;
    }
    String environment = System.getenv(ENV_DISABLE_DAEMON);
    return environment != null && Boolean.parseBoolean(environment);
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
