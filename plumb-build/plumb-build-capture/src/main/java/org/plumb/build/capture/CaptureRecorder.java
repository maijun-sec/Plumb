package org.plumb.build.capture;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The class the instrumented compiler calls into.
 *
 * <p><b>Why this class cannot be a normal part of the agent.</b> The instrumented bytecode lives
 * in the compiler's own classes ({@code com.sun.tools.javac.Main} and friends), which are loaded
 * by the application class loader. The agent jar, meanwhile, is only visible to the agent's class
 * loader. A direct call from the compiler's code to an agent class would fail with {@code
 * NoClassDefFoundError}.
 *
 * <p>Appending a small jar to the <b>bootstrap</b> class loader solves it: the bootstrap loader is
 * the ancestor of every other loader, so a class placed there is reachable from the compiler, from
 * the application and from the agent alike.
 *
 * <p>That visibility comes with a constraint: <b>this class must not reference anything that is
 * not on the bootstrap loader</b>. It cannot use SLF4J, and it cannot call {@link CaptureSink}. It
 * writes the capture file itself. Keep this class dependency-free.
 *
 * <p><b>What is recorded.</b> Not just the argv. Coverity's {@code JavacOptionParser} goes further:
 * it reconstructs the <em>effective</em> compile — the classpath that was actually used, the
 * source path, the encoding, the release level — because the argv alone does not say what the
 * compiler saw. {@code -classpath ""} and no {@code -classpath} at all mean different things, and
 * only the second is knowable from the environment.
 */
public final class CaptureRecorder {

  /** Environment variable naming the directory captures are written to. */
  public static final String ENV_OUTPUT_DIR = "PLUMB_CAPTURE_DIR";

  /**
   * System property naming the directory captures are written to.
   *
   * <p>The environment variable is the right way to configure the agent for a real build: the
   * agent is started by the build tool, and the build tool can set an environment variable but
   * not a system property. The system property exists for the case where the agent is installed
   * <em>into a running JVM</em> (the dynamic-attach path, and the tests): the environment of a
   * running process cannot be changed, but a system property can.
   *
   * <p>The system property wins when both are set.
   */
  public static final String PROP_OUTPUT_DIR = "plumb.capture.dir";

  /**
   * The identity of the compile most recently recorded, used to suppress duplicates.
   *
   * <p>A single compile reaches several hooked entry points: the command-line driver goes through
   * {@code com.sun.tools.javac.Main#compile} and then {@code com.sun.tools.javac.main.Main
   * #compile}; the API path goes through {@code JavacTool#getTask} and then {@code
   * JavacTaskImpl#call}. Each of them would otherwise write its own file, and the analyser would
   * see one compile several times over.
   *
   * <p><b>The key is the compile's identity, not its argv.</b> The argv differs between the CLI
   * and the API path even for the same compile, and it is identical for two genuinely different
   * compiles that happen to be invoked with the same options. The identity -- sources, output
   * directory, classpath, encoding, language level -- is what actually says "same compile".
   */
  private static volatile String lastIdentity;

  /**
   * How many distinct compiles are remembered for duplicate detection.
   *
   * <p>More than one, because a build compiles many modules in a row and the identity of the
   * previous compile is not enough to tell a repeat from a new one.
   */
  private static final int IDENTITY_HISTORY = 8;

  /** The identities of the compiles seen so far, most recent first. */
  private static final java.util.Set<String> SEEN_IDENTITIES =
      java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

  /**
   * The compiler options of the tasks that have been created but not yet compiled.
   *
   * <p><b>This is how the IDE path gets its options.</b> {@code JavacTaskImpl#call()} and {@code
   * JavacTaskImpl#generate()} carry no options -- they were set on the task when it was created.
   * And they cannot be read back off the task: {@code com.sun.tools.javac.api} is not exported by
   * the {@code jdk.compiler} module, so every reflective access to the task's {@code options} field
   * fails with {@code InaccessibleObjectException} unless the JVM is started with {@code
   * --add-opens jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED}. Requiring that flag would make
   * the agent unusable in a normal build.
   *
   * <p>So the options are remembered at the point where they are still visible -- {@code
   * JavacTool#getTask}, which receives them as an argument -- and are looked up again when the
   * task is driven. The lookup is by identity, so two tasks never share options.
   */
  private static final Map<Object, List<String>> TASK_OPTIONS = new IdentityHashMap<>();

  /**
   * The source files of the tasks that have been created but not yet compiled.
   *
   * <p>Remembered for the same reason as the options.
   */
  private static final Map<Object, List<String>> TASK_SOURCES = new IdentityHashMap<>();

  private CaptureRecorder() {}

  /**
   * Record a captured CLI invocation.
   *
   * @param arguments the argv the compiler received
   */
  public static void onArgv(String[] arguments) {
    if (arguments == null || arguments.length == 0) {
      return;
    }
    List<String> argv = new ArrayList<>(arguments.length);
    for (String a : arguments) {
      argv.add(a);
    }
    record(buildCapture(argv, List.of(), "cli"));
  }

  /**
   * Remember a task's options and sources.
   *
   * <p>Called from the {@code getTask} hook, which has both.
   *
   * @param task the task that was created
   * @param options the compiler options
   * @param sources the source files
   */
  public static void rememberTask(Object task, List<String> options, List<String> sources) {
    if (task == null || options == null || options.isEmpty()) {
      return;
    }
    synchronized (TASK_OPTIONS) {
      TASK_OPTIONS.put(task, options);
      TASK_SOURCES.put(task, sources == null ? List.of() : sources);
    }
  }

  /**
   * The compiler options of a {@code getTask} call, as strings.
   *
   * @param options the options iterable, may be null
   * @return the options, empty when there are none
   */
  public static List<String> toStrings(Iterable<?> options) {
    List<String> out = new ArrayList<>();
    if (options == null) {
      return out;
    }
    for (Object o : options) {
      if (o != null) {
        out.add(String.valueOf(o));
      }
    }
    return out;
  }

  /**
   * The source files of a {@code getTask} call, as paths.
   *
   * @param sources the sources iterable, may be null
   * @return the source paths, empty when there are none
   */
  public static List<String> toPaths(Iterable<?> sources) {
    List<String> out = new ArrayList<>();
    if (sources == null) {
      return out;
    }
    for (Object o : sources) {
      String path = toPath(o);
      if (path != null) {
        out.add(path);
      }
    }
    return out;
  }

  /**
   * Record a compile driven through a task object, as an IDE does.
   *
   * <p>{@code JavacTaskImpl#call()} and {@code JavacTaskImpl#generate()} carry no compiler
   * options: they were set on the task when it was created, by {@code JavacTool#getTask}. So the
   * options are looked up from the ones remembered then.
   *
   * @param task the task object, or {@code null}
   */
  public static void onTask(Object task) {
    if (task == null) {
      return;
    }
    try {
      doRecordTask(task);
    } catch (Throwable t) {
      // A capture must never break the compile.
      System.err.println("[plumb-capture] could not record the compile: " + t);
    }
  }

  private static void doRecordTask(Object task) {
    List<String> options;
    List<String> sourcePaths;
    synchronized (TASK_OPTIONS) {
      options = TASK_OPTIONS.get(task);
      sourcePaths = TASK_SOURCES.get(task);
      // The task is consumed: it will not be driven again, and holding on
      // to it would keep the compiler's file manager alive.
      TASK_OPTIONS.remove(task);
      TASK_SOURCES.remove(task);
    }
    if (options == null || options.isEmpty()) {
      return;
    }
    if (sourcePaths == null) {
      sourcePaths = List.of();
    }
    record(buildCapture(options, sourcePaths, "api"));
  }

  /**
   * Record a compile, unless this compile has already been recorded.
   *
   * <p>The duplicate check is on the compile's identity, not on its argv. One compile reaches
   * several entry points -- the CLI driver through two of them, the API path through two others
   * -- and each would otherwise write a file. The identity says whether two captures describe the
   * same compile.
   *
   * <p><b>The history is bounded.</b> A build compiles module after module; keeping every
   * identity forever would grow without limit, and a build that compiles the same sources twice
   * with different options is two different compiles.
   *
   * @param entries the capture to record
   */
  private static void record(List<CaptureEntry> entries) {
    if (entries == null || entries.isEmpty()) {
      return;
    }
    CaptureEntry entry = entries.get(0);
    String identity = entry.compileIdentity();
    synchronized (SEEN_IDENTITIES) {
      if (SEEN_IDENTITIES.contains(identity)) {
        // The same compile, seen through another entry point.
        return;
      }
      SEEN_IDENTITIES.add(identity);
      while (SEEN_IDENTITIES.size() > IDENTITY_HISTORY) {
        java.util.Iterator<String> it = SEEN_IDENTITIES.iterator();
        it.next();
        it.remove();
      }
    }
    lastIdentity = identity;
    write(entries);
  }

  /**
   * Record a captured {@code javax.tools} API invocation.
   *
   * @param options the compiler options, as passed to {@code getTask}
   * @param sources the source files, as passed to {@code getTask}
   */
  public static void onApiOptions(Iterable<?> options, Iterable<?> sources) {
    try {
      doRecordApi(options, sources);
    } catch (Throwable t) {
      // A capture must never break the compile. Report it, but do not
      // propagate: the advice suppresses Throwable anyway, and a stack
      // trace on the compiler's stderr is noise the user cannot act on.
      System.err.println("[plumb-capture] could not record the compile: " + t);
    }
  }

  private static void doRecordApi(Iterable<?> options, Iterable<?> sources) {
    List<String> argv = new ArrayList<>();
    if (options != null) {
      for (Object o : options) {
        if (o != null) {
          argv.add(String.valueOf(o));
        }
      }
    }
    if (argv.isEmpty()) {
      return;
    }
    List<String> sourcePaths = new ArrayList<>();
    if (sources != null) {
      for (Object o : sources) {
        String path = toPath(o);
        if (path != null) {
          sourcePaths.add(path);
        }
      }
    }
    record(buildCapture(argv, sourcePaths, "api"));
  }

  /**
   * Assemble the capture.
   *
   * <p>This is where the argv is turned into the <em>effective</em> compile: the classpath is
   * resolved against the environment the way the compiler would have resolved it, the source path
   * is filled in from {@code -sourcepath} or the current directory, and the encoding defaults to
   * the platform's.
   */
  private static List<CaptureEntry> buildCapture(
      List<String> argv, List<String> extraSources, String api) {
    JavacOptions options = JavacOptions.parse(argv);

    // The classpath the compiler actually used. `-classpath` wins; when it
    // is absent the compiler falls back to the CLASSPATH environment
    // variable, and then to the current directory. Coverity reads the
    // same three sources (JavacOptionParser consults `java.class.path`
    // and `CLASSPATH`).
    List<Path> classpath = new ArrayList<>(options.classpath());
    if (classpath.isEmpty()) {
      classpath.addAll(environmentClasspath());
    }

    // The source files. The CLI passes them as bare arguments; the API
    // passes them as JavaFileObjects.
    Set<Path> sources = new LinkedHashSet<>(options.sourceFiles());
    for (String s : extraSources) {
      try {
        sources.add(Path.of(s));
      } catch (RuntimeException ignored) {
        // not a path -- skip
      }
    }

    List<CaptureEntry> entries = new ArrayList<>();
    entries.add(
        new CaptureEntry(
            api,
            argv,
            options.encodingName(),
            options.languageLevel(),
            options.outputDirectory(),
            classpath,
            options.sourcePath(),
            options.modulePath(),
            new ArrayList<>(sources),
            System.getProperty("java.version"),
            System.getProperty("java.home"),
            System.getProperty("user.dir")));
    return entries;
  }

  /**
   * The classpath the compiler would use when the command line does not say.
   *
   * <p>{@code CLASSPATH} is the documented source; {@code java.class.path} is what the JVM
   * actually started with, and it is what a build tool's forked compiler inherits.
   */
  private static List<Path> environmentClasspath() {
    List<Path> out = new ArrayList<>();
    String cp = System.getenv("CLASSPATH");
    if (cp == null || cp.isBlank()) {
      cp = System.getProperty("java.class.path");
    }
    if (cp == null || cp.isBlank()) {
      return out;
    }
    // The separator is platform-dependent: ';' on Windows, ':' elsewhere.
    // Splitting on both would cut a Windows drive letter in half.
    char separator =
        System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
            ? ';'
            : ':';
    for (String part : cp.split(java.util.regex.Pattern.quote(String.valueOf(separator)))) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        try {
          out.add(Path.of(trimmed));
        } catch (RuntimeException ignored) {
          // not a path -- skip
        }
      }
    }
    return out;
  }

  /**
   * Turn a source object into a path.
   *
   * <p>The API hands the compiler a mix of {@code PathFileObject}s, {@code JavaFileObjects} and
   * plain strings, so all three are handled.
   *
   * <p><b>The object is handled reflectively, on purpose.</b> The recorder runs on the bootstrap
   * class loader, which sees only the JDK's bootstrap classes. {@code javax.tools.FileObject}
   * lives in the {@code java.compiler} module, which the <em>platform</em> loader owns -- so a
   * direct reference to it would fail with {@code NoClassDefFoundError} on the bootstrap copy of
   * this class. A reflective call is resolved against the <em>calling</em> object's class loader,
   * which is the one that can see {@code javax.tools}.
   */
  private static String toPath(Object source) {
    if (source == null) {
      return null;
    }
    if (source instanceof Path p) {
      return p.toString();
    }
    // A JavaFileObject.
    //
    // `toUri()` is the obvious accessor, but it is not reliable: the
    // JDK's own SimpleFileObject throws UnsupportedOperationException
    // from it. `getName()` always works -- it is the path the file
    // manager was given.
    String name = callString(source, "getName");
    if (name != null && !name.isBlank()) {
      return name;
    }
    // Fall back to the URI.
    String uri = callString(source, "toUri");
    if (uri == null) {
      return null;
    }
    try {
      URI u = URI.create(uri);
      String path = u.getPath();
      if (path == null || path.isBlank()) {
        return null;
      }
      // A Windows URI is "/C:/dir/File.java"; the leading slash is part
      // of the drive, not of the path.
      if (path.length() > 2
          && path.charAt(0) == '/'
          && Character.isLetter(path.charAt(1))
          && path.charAt(2) == ':') {
        path = path.substring(1);
      }
      return path;
    } catch (IllegalArgumentException e) {
      return uri.startsWith("file:") ? uri.substring("file:".length()) : uri;
    }
  }

  /**
   * Call a no-argument method that returns a String, reflectively.
   *
   * <p>Reflective because the bootstrap class loader cannot see {@code javax.tools}, and
   * because the method may not exist on a given implementation.
   *
   * <p><b>The method is looked up on the interface, not on the implementation class.</b> The
   * implementation is {@code com.sun.tools.javac.file.PathFileObject$SimpleFileObject}, and the
   * {@code jdk.compiler} module does not export {@code com.sun.tools.javac.file} to unnamed
   * modules. Calling {@code getName()} on the implementation class therefore fails with {@code
   * IllegalAccessException}, even though the method is public. The interface {@code
   * javax.tools.JavaFileObject} <em>is</em> exported, so the call has to go through it.
   *
   * @return the value, or {@code null} when the call failed
   */
  private static String callString(Object target, String methodName) {
    try {
      java.lang.reflect.Method m = findPublicMethod(target, methodName);
      if (m == null) {
        return null;
      }
      Object value = m.invoke(target);
      return value == null ? null : String.valueOf(value);
    } catch (ReflectiveOperationException | RuntimeException e) {
      return null;
    }
  }

  /**
   * Find a method that this class is allowed to call.
   *
   * <p>The implementation class is not accessible from the bootstrap loader, so the method is
   * looked up on every interface the object implements, and on {@code Object}. An interface
   * method is found by name, which is enough here because {@code getName} and {@code toUri} are
   * not overloaded on {@code JavaFileObject}.
   */
  private static java.lang.reflect.Method findPublicMethod(Object target, String methodName) {
    for (Class<?> iface : target.getClass().getInterfaces()) {
      try {
        return iface.getMethod(methodName);
      } catch (NoSuchMethodException ignored) {
        // try the next interface
      }
    }
    // Some implementations do not implement the interface directly; walk
    // the superclasses too.
    for (Class<?> c = target.getClass().getSuperclass(); c != null; c = c.getSuperclass()) {
      for (Class<?> iface : c.getInterfaces()) {
        try {
          return iface.getMethod(methodName);
        } catch (NoSuchMethodException ignored) {
          // try the next interface
        }
      }
    }
    try {
      return target.getClass().getMethod(methodName);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  /**
   * Write the capture files.
   *
   * <p>Never throws: a broken capture must not break the compile.
   */
  private static void write(List<CaptureEntry> entries) {
    String dir = outputDirectory();
    if (dir == null || dir.isBlank()) {
      // No destination configured: the capture stays in this JVM's memory.
      return;
    }
    try {
      Path out = Path.of(dir);
      Files.createDirectories(out);
      for (CaptureEntry entry : entries) {
        Path file = out.resolve("javac-" + System.nanoTime() + ".json");
        Files.writeString(file, entry.toJson(), StandardCharsets.UTF_8);
      }
    } catch (IOException | RuntimeException e) {
      System.err.println("[plumb-capture] could not write capture: " + e.getMessage());
    }
  }

  /**
   * Where the captures go.
   *
   * <p>The system property wins over the environment variable, because it can be set from inside
   * a running JVM and the environment cannot.
   */
  static String outputDirectory() {
    String prop = System.getProperty(PROP_OUTPUT_DIR);
    if (prop != null && !prop.isBlank()) {
      return prop;
    }
    return System.getenv(ENV_OUTPUT_DIR);
  }
}
