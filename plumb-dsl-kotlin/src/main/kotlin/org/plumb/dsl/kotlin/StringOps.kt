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

/**
 * The `contain` operator, for a receiver that may be a string OR a collection.
 *
 * <p>The upstream defines one generic infix function:
 *
 * <pre>
 *   infix fun &lt;T&gt; Collection&lt;T&gt;.contain(that: T): Boolean
 * </pre>
 *
 * so `supers contain "java.util.logging.Logger"` is a MEMBERSHIP test on a list, and a string
 * receiver is not part of the upstream's vocabulary at all. But a rule author writes
 * `parameterTypes[0].resolveType contains "java.util"` when they mean "the type name contains
 * this substring", and routing that to the collection version fails to compile: the receiver is a
 * String and the collection overload wants a Collection.
 *
 * <p>So the operator is resolved on the RECEIVER's type:
 *
 * <ul>
 *   <li>a string receiver is a SUBSTRING test -- the string's own `contains`;
 *   <li>a collection receiver is an EXACT membership test -- the upstream's semantics.
 * </ul>
 *
 * <p><b>Why the collection case stays exact.</b> A substring test on a collection would make
 * `supers contain "java.lang.String"` match a `java.lang.Comparable<java.lang.String>` in the
 * ancestor list, and the rule would fire on a type it was not written for. The upstream's
 * membership test is exact, and the difference between the two semantics is exactly what the
 * `supers` / `strictSupers` pair exists to expose.
 */
object Contain {

    /** `receiver contain operand` -- a substring test for a string, a membership test otherwise. */
    @JvmStatic
    fun contains(receiver: Any?, operand: Any?): Boolean {
        if (receiver == null || operand == null) return false
        if (receiver is String) {
            return receiver.contains(operand.toString())
        }
        if (receiver is Collection<*>) {
            // The upstream's membership test: an exact match on an element.
            return receiver.contains(operand)
        }
        return false
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
