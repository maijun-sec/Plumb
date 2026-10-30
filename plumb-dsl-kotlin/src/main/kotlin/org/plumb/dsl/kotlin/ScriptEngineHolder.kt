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
import javax.script.ScriptEngineManager

/**
 * Owns the Kotlin script engine and compiles rules against it.
 *
 * <p><b>One engine per process.</b> Creating a Kotlin script engine costs
 * roughly 600 ms because it boots the embedded compiler. The engine is
 * therefore created lazily and shared.
 *
 * <p><b>One compilation per rule.</b> A compiled script is reused for every
 * node the rule matches. The alternative -- compiling per node -- costs
 * about 236 ms per evaluation; reusing a compiled script costs about
 * 0.5 ms. Both figures were measured on this codebase.
 *
 * <p><b>The classpath.</b> The script compiler must see the module's own
 * classes (for [NodeAccess] and friends) and JavaParser. The
 * `kotlin.jsr223.experimental.resolve.dependencies.from.context.classloader`
 * switch makes the script compiler resolve from the thread's context
 * classloader, which is what puts those classes in scope.
 */
object ScriptEngineHolder {

    private val LOG = LoggerFactory.getLogger(ScriptEngineHolder::class.java)

    /** The property that makes the script compiler use the context classloader. */
    const val RESOLVE_FROM_CLASSLOADER_PROPERTY =
        "kotlin.jsr223.experimental.resolve.dependencies.from.context.classloader"

    @Volatile
    private var engine: ScriptEngine? = null

    /** The shared engine, created on first use. */
    fun engine(): ScriptEngine {
        val existing = engine
        if (existing != null) return existing
        synchronized(this) {
            val current = engine
            if (current != null) return current
            System.setProperty(RESOLVE_FROM_CLASSLOADER_PROPERTY, "true")
            val created = ScriptEngineManager().getEngineByName("kotlin")
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
     * Compiles a script, returning the compiled form.
     *
     * @throws ScriptCompilationException when the script does not compile
     */
    fun compile(script: String, ruleId: String): CompiledScript {
        val target = engine()
        try {
            return (target as Compilable).compile(script)
        } catch (ex: Exception) {
            throw ScriptCompilationException(ruleId, ex.message ?: ex.toString(), ex)
        }
    }
}

/** Thrown when a rule's script does not compile. */
class ScriptCompilationException(
    val ruleId: String,
    val detail: String,
    cause: Throwable? = null
) : RuntimeException("Rule '$ruleId' failed to compile: $detail", cause)
