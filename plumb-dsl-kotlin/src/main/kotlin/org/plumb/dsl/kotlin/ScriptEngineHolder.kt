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
package org.plumb.dsl.kotlin

import org.slf4j.LoggerFactory
import javax.script.Compilable
import javax.script.CompiledScript
import javax.script.ScriptEngine

/**
 * Owns the Kotlin script engine and compiles rules against it.
 *
 * <p><b>One engine per process.</b> Creating a Kotlin script engine costs
 * roughly 600 ms because it boots the embedded compiler. The engine is
 * therefore created lazily and shared.
 *
 * <p><b>One compilation per rule.</b> A compiled script is reused for every
 * node the rule matches. The alternative, compiling per node, costs about
 * 236 ms per evaluation; reusing a compiled script costs about 0.5 ms. Both
 * figures were measured on this codebase.
 *
 * <p><b>The classpath.</b> The script compiler must see the module's own
 * classes (for [NodeAccess] and friends) and JavaParser. The
 * `kotlin.jsr223.experimental.resolve.dependencies.from.context.classloader`
 * switch makes the script compiler resolve from the thread's context
 * classloader, which is what puts those classes in scope.
 *
 * <p><b>THE CLASSLOADER QUESTION, AND WHY THE ANSWER IS "TWO, BUT NARROWLY".</b>
 * The Kotlin source frontend (plumb-frontend) needs the Analysis API, and the
 * Analysis API's jars bundle a second Kotlin compiler: 594 classes under
 * `org.jetbrains.kotlin.cli`, 302 under `org.jetbrains.kotlin.scripting`, and
 * the unshaded `com.intellij` platform. On a shared classpath those
 * 2.5.0-Beta2 copies shadow the 2.2.0 scripting classes this engine links
 * against, and the engine dies at construction with a `NoSuchMethodError` (same
 * method, same arity, different parameter type: the shaded versus the unshaded
 * IntelliJ) or a `ClassCastException` between the two IntelliJ copies.
 *
 * <p><b>ISOLATION ALONE IS NOT ENOUGH, AND THE REASON IS WORTH RECORDING.</b>
 * The obvious fix is to give the script engine its own classloader. But the
 * standard library has to be SHARED: `kotlin.coroutines.CoroutineContext`,
 * `kotlin.Unit` and the rest must be one class in the JVM, or the compiler's
 * own code casts across the boundary and the JVM throws a loader constraint
 * violation. And the boundary cannot be drawn inside `org.jetbrains.kotlin.*`
 * either, because Kotlin's compiler classes reference each other across that
 * whole namespace.
 *
 * <p>So the boundary is drawn around the two packages the stacks genuinely
 * disagree about, and around nothing else: the script engine's loader owns
 * `org.jetbrains.kotlin.cli.*`, `org.jetbrains.kotlin.idea.*` and
 * `org.jetbrains.kotlin.scripting.*`, and the application loader owns
 * everything else, the standard library included. See [ScriptEngineClassLoader].
 *
 * <p>The other half of the fix is the plumb-kotlin-analysis-api module, which
 * removes the Analysis API's bundled script API from the classpath. The
 * analysis path never references it, so nothing is lost.
 */
object ScriptEngineHolder {

    private val LOG = LoggerFactory.getLogger(ScriptEngineHolder::class.java)

    /** The property that makes the script compiler use the context classloader. */
    const val RESOLVE_FROM_CLASSLOADER_PROPERTY =
        "kotlin.jsr223.experimental.resolve.dependencies.from.context.classloader"

    @Volatile
    private var engine: ScriptEngine? = null

    /** The classloader that owns the script engine's Kotlin compiler classes. */
    @Volatile
    private var scriptLoader: ScriptEngineClassLoader? = null

    /** The shared engine, created on first use. */
    fun engine(): ScriptEngine {
        val existing = engine
        if (existing != null) return existing
        synchronized(this) {
            val current = engine
            if (current != null) return current
            System.setProperty(RESOLVE_FROM_CLASSLOADER_PROPERTY, "true")
            val created = createEngine()
            if (created == null) {
                throw IllegalStateException(
                    "The Kotlin script engine is not available. " +
                        "org.jetbrains.kotlin:kotlin-scripting-jsr223 must be on the classpath."
                )
            }
            if (created !is Compilable) {
                throw IllegalStateException(
                    "The Kotlin script engine does not support compilation; " +
                        "the DSL engine needs a Compilable engine."
                )
            }
            LOG.info("Kotlin script engine ready: {}", created.javaClass.name)
            engine = created
            return created
        }
    }

    /** True when the script engine can be created. Used by the self-test. */
    fun isAvailable(): Boolean = try {
        engine()
        true
    } catch (ex: RuntimeException) {
        false
    }

    /**
     * Creates the script engine inside an ISOLATED classloader.
     *
     * <p>The engine is built by the factory, loaded from the isolated loader, so the
     * Kotlin compiler classes it links against are the ones in the script engine's
     * own jars.
     *
     * @return the script engine, or null when it cannot be created
     */
    private fun createEngine(): ScriptEngine? {
        val appLoader = ScriptEngineHolder::class.java.classLoader
        val owned = ScriptEngineClassLoader.selectOwnedJars(classpathUrls(appLoader))
        if (owned.isEmpty()) {
            // Not a fallback: a warning here is how every DSL rule silently stops
            // matching. Without the isolated loader the script engine would load the
            // Analysis API's Kotlin compiler instead of its own, and the scan would
            // look healthy while the user's rules were gone.
            throw IllegalStateException(
                "No Kotlin script-engine jars were found on the classpath, so the " +
                    "script engine cannot be isolated from the Analysis API's Kotlin " +
                    "compiler. This is a build/classpath problem, not a rule problem."
            )
        }
        val loader = ScriptEngineClassLoader(appLoader, owned)
        scriptLoader = loader
        LOG.info(
            "Script engine classloader owns {} Kotlin jar(s): {}",
            owned.size,
            owned.map { it.file.substringAfterLast('/') }.joinToString(", "),
        )
        // The factory is instantiated DIRECTLY rather than through
        // ScriptEngineManager's service lookup, because the lookup reads
        // META-INF/services from the loader it is given, and the script engine's
        // service file lives in the APPLICATION loader.
        val previous = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = loader
            val factoryClass = loader.loadClass(KOTLIN_ENGINE_FACTORY_CLASS)
            val factory = factoryClass.getDeclaredConstructor().newInstance()
                    as javax.script.ScriptEngineFactory
            factory.scriptEngine
        } catch (ex: Throwable) {
            LOG.warn("Could not create the Kotlin script engine: {}", ex.toString())
            ex.cause?.let { LOG.warn("  caused by: {}", it.toString()) }
            null
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /**
     * The classpath entries of the process, as URLs.
     *
     * <p>Read from the {@code java.class.path} system property rather than from the
     * loader: on JDK 9+ the application loader is not a [java.net.URLClassLoader], and
     * neither Surefire's loader nor the shaded jar's loader is.
     *
     * <p><b>THE MANIFEST EXPANSION IS NOT OPTIONAL.</b> Surefire runs the tests with
     * a "manifest-only" jar: {@code java.class.path} then holds ONE jar, and the
     * real classpath is listed in that jar's {@code Class-Path} manifest attribute.
     * Without expanding it, the isolated loader would find no script-engine jars
     * at all, and the engine would fall back to the application classloader -- which
     * is exactly the collision the isolation exists to prevent.
     *
     * @param loader the application loader
     * @return its classpath entries
     */
    private fun classpathUrls(loader: ClassLoader?): List<java.net.URL> {
        val fromProperty = System.getProperty("java.class.path")
            ?.split(java.io.File.pathSeparator)
            ?.filter { it.isNotBlank() }
            ?.mapNotNull { path ->
                try {
                    java.io.File(path).toURI().toURL()
                } catch (ex: Exception) {
                    null
                }
            }
            ?: emptyList()
        val fromLoader = (loader as? java.net.URLClassLoader)?.urLs?.toList() ?: emptyList()
        val expanded = fromProperty.flatMap { url -> expandManifestClassPath(url) }
        return (expanded + fromLoader).distinct()
    }

    /**
     * Expands a jar's {@code Class-Path} manifest attribute into URLs.
     *
     * <p>Manifest {@code Class-Path} entries are space-separated, relative to the
     * jar that declares them, and may themselves point at jars with their own
     * {@code Class-Path}.
     *
     * @param url the jar URL
     * @return the URL itself followed by everything it refers to
     */
    private fun expandManifestClassPath(url: java.net.URL): List<java.net.URL> {
        val result = mutableListOf(url)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<java.net.URL>()
        queue.add(url)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!visited.add(current.toExternalForm())) {
                continue
            }
            val file = try {
                java.io.File(current.toURI())
            } catch (ex: Exception) {
                continue
            }
            if (!file.isFile) {
                continue
            }
            val referenced = try {
                java.util.jar.JarFile(file).use { jar ->
                    jar.manifest?.mainAttributes?.getValue(java.util.jar.Attributes.Name.CLASS_PATH)
                }
            } catch (ex: Exception) {
                null
            } ?: continue
            for (entry in referenced.split(' ', '\t', '\n', '\r').filter { it.isNotBlank() }) {
                val resolved = try {
                    java.net.URL(current, entry)
                } catch (ex: Exception) {
                    continue
                }
                result.add(resolved)
                queue.add(resolved)
            }
        }
        return result
    }

    /**
     * Compiles a script, returning the compiled form.
     *
     * <p>The script compiler resolves the script's dependencies from the CONTEXT
     * classloader, so the context classloader is set to the script engine's own
     * loader for the duration of the call. That loader delegates everything outside
     * the compiler packages to the application loader, so the script still sees
     * [NodeAccess] and JavaParser.
     *
     * @throws ScriptCompilationException when the script does not compile
     */
    fun compile(script: String, ruleId: String): CompiledScript {
        val target = engine()
        val previous = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = scriptLoader ?: previous
            (target as Compilable).compile(script)
        } catch (ex: Exception) {
            throw ScriptCompilationException(ruleId, ex.message ?: ex.toString(), ex)
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /**
     * Evaluates a compiled script with the script engine's classloader in place.
     *
     * <p><b>THE CONTEXT CLASSLOADER MATTERS AT EVALUATION TIME, NOT ONLY AT
     * COMPILATION TIME.</b> The compiled script's own class loader is created
     * lazily, when the script is first evaluated, by
     * [kotlin.script.experimental.jvm.impl.KJvmCompiledScript.getOrCreateActualClassloader],
     * and it takes its parent from the CURRENT THREAD'S CONTEXT CLASSLOADER. If
     * that is the application loader, the script class resolves
     * {@code org.jetbrains.kotlin.*} against the application classpath -- where
     * the Analysis API's second Kotlin compiler lives -- and the evaluation dies
     * with
     *
     * <pre>
     * ClassCastException: Cannot cast org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
     * to org.jetbrains.kotlin.cli.common.repl.AggregatedReplStageState
     * </pre>
     *
     * <p>Same class name, two loaders. Switching the context classloader around
     * the evaluation is what keeps the script inside the engine's own world.
     *
     * @param compiled the compiled script
     * @return whatever the script returned
     */
    fun eval(compiled: CompiledScript): Any? {
        val previous = Thread.currentThread().contextClassLoader
        return try {
            Thread.currentThread().contextClassLoader = scriptLoader ?: previous
            compiled.eval()
        } finally {
            Thread.currentThread().contextClassLoader = previous
        }
    }

    /**
     * The Kotlin script engine's factory.
     *
     * <p>Named directly rather than discovered through
     * [javax.script.ScriptEngineManager]'s service lookup, because the lookup reads
     * `META-INF/services` from the loader it is handed, and that file lives in the
     * application loader.
     */
    private const val KOTLIN_ENGINE_FACTORY_CLASS =
        "kotlin.script.experimental.jsr223.KotlinJsr223DefaultScriptEngineFactory"
}

/** Thrown when a rule's script does not compile. */
class ScriptCompilationException(
    val ruleId: String,
    val detail: String,
    cause: Throwable? = null
) : RuntimeException("Rule '$ruleId' failed to compile: $detail", cause)
