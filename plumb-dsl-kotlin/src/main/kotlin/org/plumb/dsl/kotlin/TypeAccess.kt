package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.resolution.types.ResolvedType

/**
 * Type-resolution helpers: the "type" side of the upstream DSL.
 *
 * <p><b>The `supers` semantics.</b> The upstream project defines
 *
 * <pre>
 *   val ResolvedType.supers: List&lt;String&gt;
 *       get() = this.asReferenceType().allAncestors.map { it.typeName }
 * </pre>
 *
 * `getAllAncestors()` returns the <em>strict</em> ancestors and does NOT
 * include the type itself. Measured against a field declared as
 * `java.util.logging.Logger`, the upstream list is `[java.lang.Object]` --
 * so the upstream rule
 *
 * <pre>
 *   (type.supers contain "java.util.logging.Logger")
 * </pre>
 *
 * never fires. A subclass (`MyLogger extends Logger`) does match, which is
 * the opposite of what the rule's author intended.
 *
 * <p>So {@link #supers} here is the "is-a" closure: the type itself plus
 * its strict ancestors. {@link #strictSupers} keeps the upstream behaviour
 * for anyone who wants it. The difference is documented in
 * `docs/tech/KOTLIN-DSL.md`.
 */
object TypeAccess {

    /**
     * The "is-a" closure: the resolved type itself followed by its strict
     * ancestors, all as fully qualified names.
     */
    @JvmStatic
    fun supers(node: Node?): List<String> {
        val resolved = resolve(node) ?: return emptyList()
        return supersOf(resolved)
    }

    /**
     * The strict ancestors only -- the upstream `ResolvedType.supers`
     * semantics, kept so a rule can be written to match it exactly.
     */
    @JvmStatic
    fun strictSupers(node: Node?): List<String> {
        val resolved = resolve(node) ?: return emptyList()
        return strictSupersOf(resolved)
    }

    /** The resolved type's fully qualified name. */
    @JvmStatic
    fun typeName(node: Node?): String? = resolve(node)?.describe()

    /**
     * The resolved type of a field declaration, or of a type node.
     */
    @JvmStatic
    fun resolve(node: Node?): ResolvedType? {
        if (node == null) return null
        return try {
            when (node) {
                is FieldDeclaration -> node.elementType.resolve()
                is com.github.javaparser.ast.type.Type -> node.resolve()
                else -> null
            }
        } catch (ex: RuntimeException) {
            null
        }
    }

    /** The "is-a" closure of an already-resolved type. */
    internal fun supersOf(resolved: ResolvedType): List<String> {
        if (!resolved.isReferenceType) return listOf(resolved.describe())
        val out = ArrayList<String>()
        out.add(resolved.describe())
        for (ancestor in resolved.asReferenceType().getAllAncestors()) {
            out.add(ancestor.describe())
        }
        return out
    }

    /** The strict ancestors of an already-resolved type. */
    internal fun strictSupersOf(resolved: ResolvedType): List<String> {
        if (!resolved.isReferenceType) return emptyList()
        return resolved.asReferenceType().getAllAncestors().map { it.describe() }
    }

    // ─── explicit-call forms used by the rewritten scripts ─────────────

    /** The "is-a" closure of a type reference. */
    @JvmStatic
    fun supersOf(typeRef: TypeRef?): List<String> {
        val resolved = typeRef?.resolvedType ?: return emptyList()
        return supersOf(resolved)
    }

    /** The strict ancestors of a type reference. */
    @JvmStatic
    fun strictSupersOf(typeRef: TypeRef?): List<String> {
        val resolved = typeRef?.resolvedType ?: return emptyList()
        return strictSupersOf(resolved)
    }

    /** The fully qualified name of a type reference. */
    @JvmStatic
    fun typeNameOf(typeRef: TypeRef?): String? = typeRef?.typeName

    /**
     * The fully qualified name of a type, resolved from a node.
     *
     * <p>The upstream rule reads `exception.type.resolveType`, where
     * `resolveType` resolves a declared type to its qualified name.
     */
    @JvmStatic
    fun resolveTypeName(typeRef: TypeRef?): String? = typeRef?.typeName

    /**
     * The declared type at the given index of a list of types.
     *
     * <p>The upstream rule reads `parameterTypes[0].resolveType`, which is
     * the resolved name of the method's first parameter type. The list
     * comes from [NodeAccess.parameterTypes].
     */
    @JvmStatic
    fun typeAt(types: List<com.github.javaparser.ast.type.Type>?, index: Int): TypeRef? {
        if (types == null || index < 0 || index >= types.size) return null
        val resolved = try {
            types[index].resolve()
        } catch (ex: RuntimeException) {
            return null
        }
        return TypeRef(resolved)
    }

    /**
     * The resolved name of a declared type.
     *
     * <p>The upstream `Type.resolveType` extension resolves a declared
     * type to its qualified name.
     */
    @JvmStatic
    fun resolveDeclaredTypeName(typeRef: TypeRef?): String? = typeRef?.typeName
}
