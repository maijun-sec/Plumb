package org.plumb.dsl.kotlin

import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.comments.Comment
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.TryStmt
import com.github.javaparser.ast.type.Type
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration
import com.github.javaparser.resolution.types.ResolvedType

/**
 * The node-access facade.
 *
 * <p><b>Why a facade and not Kotlin extension properties.</b> The upstream
 * project exposes its node helpers as Kotlin extension properties
 * (`val Node.simpleName: String get() = ...`). A JSR-223 script cannot see
 * them: the script compiler resolves extensions from the facade class's
 * `@Metadata`, and it only reads that from the script's <em>compile</em>
 * classpath -- which is not the runtime classpath the module's own classes
 * live on. This was verified against Kotlin 2.2.0: with the script
 * compiled against the module's classes, `n.simpleName` fails with
 * "Unresolved reference: simpleName" while a plain static call
 * `NodeAccess.simpleName(n)` resolves and runs.
 *
 * <p>So the helpers are exposed as static methods. The rule text keeps the
 * upstream feel because the engine rewrites `a.b` into `NodeAccess.b(a)`
 * before compiling. See [RuleMatcher] for the rewriter.
 *
 * <p><b>Chained access.</b> The upstream DSL chains properties off derived
 * values -- `method.name`, `type.supers`, `exception.type`. To keep that
 * shape, the helpers that return another node-ish value return a small
 * wrapper ([MethodRef], [TypeRef], [ParameterRef]) whose own properties are
 * what the upstream chain reads. The rewriter maps those wrapper properties
 * onto the same facade calls, so `method.name` becomes
 * `NodeAccess.methodName(NodeAccess.method(mce))`.
 *
 * <p>All methods are null-tolerant: a helper that does not apply to the
 * node's type returns `null` (or `false`) rather than throwing, so a rule
 * that matches a node it was not written for degrades to "no match"
 * instead of blowing up the scan.
 */
object NodeAccess {

    /** The node's simple class name, e.g. `CatchClause`. */
    @JvmStatic
    fun simpleName(node: Node?): String? = node?.javaClass?.simpleName

    /**
     * The node's qualified CLASS name.
     *
     * <p>This is the upstream's own definition:
     *
     * <pre>
     *   val Node.qualifiedName: String
     *       get() = this.javaClass.name
     * </pre>
     *
     * so it is the fully qualified name of the node's JavaParser class --
     * `com.github.javaparser.ast.stmt.MethodDeclaration`. That is the node's
     * *shape*, and it is the same for every method in every file.
     *
     * <p><b>Why the node's own name is a different property.</b> The name a
     * rule author means by "the node's qualified name" is the declaring type
     * plus the member -- `com.example.Fixture.noParameters`. That is
     * [ownQualifiedName], and it is a separate property so that a rule written
     * against the upstream keeps the upstream's meaning.
     */
    @JvmStatic
    fun qualifiedName(node: Node?): String? = node?.javaClass?.name

    /**
     * The node's OWN qualified name -- the declaring type plus the member.
     *
     * <p>For a method in `com.example.Fixture`, that is
     * `com.example.Fixture.noParameters`. For a node that is not a member of
     * a named type, the declaring type's qualified name is used on its own.
     *
     * <p>This is the Plumb extension: the upstream has no such property,
     * because its `qualifiedName` is the class name.
     */
    @JvmStatic
    fun ownQualifiedName(node: Node?): String? {
        if (node == null) return null
        val memberName = when (node) {
            is MethodDeclaration -> node.nameAsString
            is ConstructorDeclaration -> node.nameAsString
            is FieldDeclaration -> node.variables.firstOrNull()?.nameAsString
            is com.github.javaparser.ast.expr.VariableDeclarationExpr ->
                node.variables.firstOrNull()?.nameAsString
            else -> null
        }
        val type = enclosingClass(node) ?: return node.javaClass.name
        return if (memberName == null) type else "$type.$memberName"
    }

    /** The method's name; null when the node is not a method declaration. */
    @JvmStatic
    fun methodName(node: Node?): String? =
        (node as? MethodDeclaration)?.nameAsString

    /** The constructor's name; null when the node is not a constructor. */
    @JvmStatic
    fun constructorName(node: Node?): String? =
        (node as? ConstructorDeclaration)?.nameAsString

    /** The parameter types of a method declaration; empty otherwise. */
    @JvmStatic
    fun parameterTypes(node: Node?): List<Type> =
        (node as? MethodDeclaration)?.parameters?.map { it.type } ?: emptyList()

    /** The parameters of a method declaration; empty otherwise. */
    @JvmStatic
    fun parameters(node: Node?): List<Parameter> =
        (node as? MethodDeclaration)?.parameters ?: emptyList()

    /**
     * The parameter at the given index of a method's parameter list.
     *
     * <p>The upstream rules read `parameterTypes[0].resolveType`, which is the
     * resolved name of the method's first parameter type. This is the same
     * shape for the parameter itself rather than its type: `parameters[0].name`
     * is the parameter's declared name.
     */
    @JvmStatic
    fun parameterAt(parameters: List<Parameter>?, index: Int): ParameterRef? {
        if (parameters == null || index < 0 || index >= parameters.size) return null
        return ParameterRef(parameters[index])
    }

    /** The caught exception's parameter; null when the node is not a catch clause. */
    @JvmStatic
    fun exception(node: Node?): ParameterRef? {
        val parameter = (node as? CatchClause)?.parameter ?: return null
        return ParameterRef(parameter)
    }

    /** The resolved type of a caught exception. */
    @JvmStatic
    fun exceptionType(parameterRef: ParameterRef?): TypeRef? = parameterRef?.type

    /** The name of a caught exception's parameter. */
    @JvmStatic
    fun exceptionName(parameterRef: ParameterRef?): String? = parameterRef?.name

    /**
     * The declared type of a parameter from the parameter list.
     *
     * <p>The counterpart of [exceptionType] for a parameter that came from a
     * method's parameter list rather than from a catch clause. The two
     * parameters are both `Parameter`s, so they carry the same properties --
     * they just arrive through different accessors.
     */
    @JvmStatic
    fun parameterType(parameterRef: ParameterRef?): TypeRef? = parameterRef?.type

    /**
     * The name of a parameter from the parameter list.
     *
     * <p>The counterpart of [exceptionName] for a parameter that came from a
     * method's parameter list rather than from a catch clause.
     */
    @JvmStatic
    fun parameterName(parameterRef: ParameterRef?): String? = parameterRef?.name

    /** The enclosing method declaration; null when the node is not inside one. */
    @JvmStatic
    fun enclosingFunction(node: Node?): MethodDeclaration? {
        if (node == null) return null
        val found = node.findAncestor(MethodDeclaration::class.java)
        return if (found.isPresent) found.get() else null
    }

    /**
     * The qualified name of the class that encloses the node.
     *
     * <p><b>Why a name and not the declaration.</b> The upstream's `enclosingClass` is a
     * string: the wrapper form is compared against one, as in
     * `method.enclosingClass == "java.lang.Throwable"`. A node-level property that returned the
     * declaration object instead would make `enclosingClass == "..."` a compile error --
     * "Operator '==' cannot be applied to 'ClassOrInterfaceDeclaration?' and 'String'" -- so a
     * rule that looked reasonable would fail to compile for a reason the rule author cannot see.
     *
     * <p>The name is the qualified one, so it is the same shape as the wrapper's.
     *
     * @return the enclosing class's qualified name, or null when the node is not inside one
     */
    @JvmStatic
    fun enclosingClass(node: Node?): String? {
        val declaration = enclosingClassDeclaration(node) ?: return null
        return try {
            declaration.resolve().qualifiedName
        } catch (ex: RuntimeException) {
            // The class cannot be resolved -- it may be in a source file the
            // symbol solver does not know about.  The name is then unknown.
            null
        }
    }

    /**
     * The class declaration that encloses the node.
     *
     * <p>For the rare rule that needs the declaration itself. Most rules want the NAME, which
     * is what [enclosingClass] returns.
     */
    @JvmStatic
    fun enclosingClassDeclaration(node: Node?): ClassOrInterfaceDeclaration? {
        if (node == null) return null
        val found = node.findAncestor(ClassOrInterfaceDeclaration::class.java)
        return if (found.isPresent) found.get() else null
    }

    /** True when the field / method / class is `static`. */
    @JvmStatic
    fun isStatic(node: Node?): Boolean =
        when (node) {
            is FieldDeclaration -> node.isStatic
            is MethodDeclaration -> node.isStatic
            is ClassOrInterfaceDeclaration -> node.isStatic
            else -> false
        }

    /** True when the field / method / class is `final`. */
    @JvmStatic
    fun isFinal(node: Node?): Boolean =
        when (node) {
            is FieldDeclaration -> node.isFinal
            is MethodDeclaration -> node.isFinal
            is ClassOrInterfaceDeclaration -> node.isFinal
            else -> false
        }

    /** The resolved type of a field declaration; null when it cannot be resolved. */
    @JvmStatic
    fun type(node: Node?): TypeRef? {
        val resolved = (node as? FieldDeclaration)?.elementType?.resolve() ?: return null
        return TypeRef(resolved)
    }

    /** The comment body; null when the node is not a comment. */
    @JvmStatic
    fun content(node: Node?): String? = (node as? Comment)?.content

    /**
     * The resolved method declaration behind a call site; null when the node
     * is not a call site or the symbol solver cannot resolve it.
     */
    @JvmStatic
    fun method(node: Node?): MethodRef? {
        if (node !is MethodCallExpr) return null
        val resolved: ResolvedMethodDeclaration = try {
            node.resolve()
        } catch (ex: RuntimeException) {
            // The symbol solver throws when a type is not on the analysis
            // classpath. A rule that asks "which class declares this call?"
            // should simply not match, not abort the whole scan.
            return null
        }
        return MethodRef(resolved)
    }

    /**
     * The class that declares the resolved method behind a call site.
     *
     * <p>The upstream rule reads `method.enclosingClass`; this is the
     * explicit-call form of it.
     */
    @JvmStatic
    fun methodEnclosingClass(node: Node?): String? =
        method(node)?.enclosingClass

    /**
     * The name of a resolved method reference.
     *
     * <p>The upstream rule reads `method.name`. The explicit-call form
     * takes the [MethodRef] that [method] returns.
     */
    @JvmStatic
    fun resolvedMethodName(methodRef: MethodRef?): String? = methodRef?.name

    /**
     * The class that declares a resolved method reference.
     *
     * <p>The upstream rule reads `method.enclosingClass`.
     */
    @JvmStatic
    fun resolvedMethodEnclosingClass(methodRef: MethodRef?): String? = methodRef?.enclosingClass

    /** The name of a resolved method behind a call site. */
    @JvmStatic
    fun methodNameOf(node: Node?): String? = method(node)?.name

    /**
     * True when the node's body is empty.
     *
     * <p>Covers the three shapes the upstream rules care about: a block
     * statement, a catch clause's body, and a try statement's block.
     */
    @JvmStatic
    fun isEmpty(node: Node?): Boolean =
        when (node) {
            is BlockStmt -> node.statements.isEmpty()
            is CatchClause -> node.body.statements.isEmpty()
            is TryStmt -> node.tryBlock.statements.isEmpty()
            else -> false
        }
}

/**
 * A reference to a resolved method.
 *
 * <p>Carries the two properties the upstream rules read off a method:
 * its name and the class that declares it.
 */
class MethodRef internal constructor(private val declaration: ResolvedMethodDeclaration) {

    /** The method's name. */
    val name: String
        get() = declaration.name

    /** The fully qualified name of the class that declares the method. */
    val enclosingClass: String
        get() = declaration.qualifiedName.substringBeforeLast('.', declaration.qualifiedName)

    override fun toString(): String = declaration.qualifiedSignature
}

/**
 * A reference to a resolved type.
 *
 * <p>Carries the properties the upstream rules read off a type: its own
 * name and its ancestor chain.
 */
class TypeRef internal constructor(internal val resolvedType: ResolvedType) {

    /** The type's fully qualified name. */
    val typeName: String
        get() = resolvedType.describe()

    /**
     * The "is-a" closure: the type itself plus its strict ancestors.
     *
     * <p>See [TypeAccess] for why this differs from the upstream
     * `ResolvedType.supers`.
     */
    val supers: List<String>
        get() = TypeAccess.supersOf(resolvedType)

    /** The strict ancestors only -- the upstream `ResolvedType.supers`. */
    val strictSupers: List<String>
        get() = TypeAccess.strictSupersOf(resolvedType)

    override fun toString(): String = resolvedType.describe()
}

/**
 * A reference to a catch clause's exception parameter.
 */
class ParameterRef internal constructor(private val parameter: Parameter) {

    /** The parameter's declared type. */
    val type: TypeRef? =
        try {
            TypeRef(parameter.type.resolve())
        } catch (ex: RuntimeException) {
            null
        }

    /** The parameter's name. */
    val name: String
        get() = parameter.nameAsString

    override fun toString(): String = parameter.toString()
}
