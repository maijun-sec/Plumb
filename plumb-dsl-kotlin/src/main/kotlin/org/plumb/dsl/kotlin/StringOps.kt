package org.plumb.dsl.kotlin

/**
 * String and collection helpers -- the upstream `extend_basic.kt`.
 *
 * <p>The upstream versions are Kotlin extensions:
 *
 * <pre>
 *   infix fun String.startWith(that: String): Boolean
 *   infix fun String.matches(that: String): Boolean
 *   infix fun &lt;T&gt; Collection&lt;T&gt;.contain(that: T): Boolean
 * </pre>
 *
 * Extension functions have the same visibility problem as extension
 * properties in a JSR-223 script (see `NodeAccess`), so they are exposed
 * as static methods here.
 */
object StringOps {

    /** `s startsWith p` */
    @JvmStatic
    fun startsWith(text: String?, prefix: String?): Boolean {
        if (text == null || prefix == null) return false
        return text.startsWith(prefix)
    }

    /** `s endsWith p` */
    @JvmStatic
    fun endsWith(text: String?, suffix: String?): Boolean {
        if (text == null || suffix == null) return false
        return text.endsWith(suffix)
    }

    /**
     * `s matches regex`
     *
     * <p>The upstream implementation supports a `(?!` prefix that switches to
     * case-insensitive matching. That convention is preserved.
     */
    @JvmStatic
    fun matches(text: String?, regex: String?): Boolean {
        if (text == null || regex == null) return false
        val caseInsensitive = regex.startsWith("(?!)")
        val pattern = if (caseInsensitive) regex.substring(4) else regex
        val options = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return try {
            Regex(pattern, options).containsMatchIn(text)
        } catch (ex: IllegalArgumentException) {
            // A malformed regex must not abort the scan; it simply does not
            // match. The rule author sees the pattern in their rule file.
            false
        }
    }

    /** `s contains sub` -- plain substring containment. */
    @JvmStatic
    fun contains(text: String?, sub: String?): Boolean {
        if (text == null || sub == null) return false
        return text.contains(sub)
    }
}

/** Collection helpers. */
object Ops {

    /** `collection contain element` */
    @JvmStatic
    fun <T> contains(collection: Collection<T>?, element: T?): Boolean {
        if (collection == null || element == null) return false
        return collection.contains(element)
    }

    /** `collection contain sub` -- does any element contain the given text? */
    @JvmStatic
    fun containsText(collection: Collection<*>?, text: String?): Boolean {
        if (collection == null || text == null) return false
        return collection.any { it is String && it.contains(text) }
    }

    /** The size of a collection; 0 when it is null. */
    @JvmStatic
    fun size(collection: Collection<*>?): Int = collection?.size ?: 0
}
