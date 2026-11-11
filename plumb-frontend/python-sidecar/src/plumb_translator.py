"""The translator: Python 3 source -> Plumb's TAC IR.

WHAT THIS IS. A frontend lowers a source language into the TAC, the
three-address instruction stream the analysis engine consumes. The TAC is
language-neutral: it says "move", "call", "read a field", "index a
container", and nothing about what a variable means.

THE PARSER. CPython's own ``ast`` module parses the source. That is the
reference implementation's parser -- the one that decides what Python 3
means -- and it is in the standard library, so the sidecar needs nothing
installed.

THE ONE LANGUAGE-SPECIFIC DECISION. In Python an object is a NAMESPACE,
not a record: ``obj.user_id`` and ``obj["user_id"]`` are the same location,
and ``items[0]`` is ``items["0"]``. So an attribute access is lowered as a
CONTAINER ACCESS with a string key -- an ARRAYLOAD / ARRAYSTORE whose index
operand is the key -- and not as a field access. The engine's keyed
namespace is what gives that access its identity: the pair (object, key).

THE OTHER LANGUAGE-SPECIFIC DECISION. Python resolves a call by NAME, and
the name is what the sink catalogue matches on. ``os.system(cmd)`` and
``from os import system; system(cmd)`` are the same function, so both
lower to the same callee signature ``os.system/1``. The import statements
are therefore read, not merely executed: they are what makes a name
resolvable.

EVERY FAILURE IS REPORTED. A construct this translator cannot lower
becomes a problem on the function, and the affected value becomes a HAVOC.
It never becomes a silent "nothing to report": a sidecar that drops a
construct quietly would leave the engine believing the program had no
findings.
"""

import ast
import builtins

from plumb_wire import Const, Kind, Precision, Problem, Type, const_op, var_op

# The names Python's own runtime provides. A call to one of them is a call
# into the ``builtins`` module, and that is the owner the callee signature
# carries.
BUILTIN_NAMES = frozenset(dir(builtins))

# The owner of a CALL that stands in for something the TAC has no
# counterpart for.
#
# It is not a real package: a sink catalogue written against "os.system/1"
# must not match "<python>.len/1" by accident, and a checker that prints
# the callee can see at a glance that the call is a stand-in.
SYNTHETIC_OWNER = "<python>"

# The owner of a REAL call whose owner the front end could not determine.
#
# This is not the same thing as SYNTHETIC_OWNER. A stand-in stands in for a
# construct the TAC has no counterpart for; this owner says that the call is
# real and its receiver's owner is simply not knowable.
#
# The owner it used to carry was the MODULE the file belongs to, and that
# shape had two faults:
#
#   * it looked like a module path, so a sink catalogue written against a
#     module could claim the program's own file. A file called "logging.py"
#     whose "logger.error(value)" reached the catalogue's
#     "^logging\.(debug|info|...)\b" -- a finding against a program that
#     never called the logging module at all.
#   * it was a GUESS dressed as a fact. The owner of that call is not the
#     module the file belongs to; it is whatever the logger is, and the
#     front end could not tell.
#
# The angle brackets are the point: no module path can contain one, and every
# sink pattern in the catalogue is anchored at the start of the callee key,
# so a key that begins with one cannot be claimed by any of them.
#
# It is deliberately a different word from SYNTHETIC_OWNER: the two mean
# different things, and a report that shows one where the other is meant
# sends the reader to the wrong place.
UNKNOWN_OWNER = "<unknown>"

# The TAC's binary operators, by the AST's operator node.
#
# The key is the operator's NODE, not its spelling. A table keyed by the
# spelling has to be looked up with a string, and a lookup that always
# misses does not look like a lookup at all: every binary expression would
# become an unknown value, and the taint that flowed through it would stop
# there.
BINARY_OPERATORS = {
    ast.Add: "ADD",
    ast.Sub: "SUB",
    ast.Mult: "MUL",
    ast.Div: "DIV",
    ast.FloorDiv: "DIV",
    ast.Mod: "MOD",
    ast.Pow: "MUL",
    ast.LShift: "SHL",
    ast.RShift: "SHR",
    ast.BitOr: "OR",
    ast.BitXor: "XOR",
    ast.BitAnd: "AND",
}

# The operators the TAC does not have.
#
# Each is mapped onto the closest thing the TAC has and REPORTED: the
# function is still lowered -- the taint of the operands reaches the result
# either way -- but the reader is told that the operator in the TAC is not
# the operator in the source.
APPROXIMATED_OPERATORS = {
    ast.Pow: (
        "MUL",
        "the TAC has no power operator, so the power is modelled as a multiplication",
    ),
    ast.MatMult: (
        None,
        "the TAC has no matrix-multiplication operator, so the value is unknown",
    ),
}

# The comparison operators, by the AST's operator node.
COMPARISON_OPERATORS = {
    ast.Eq: "EQ",
    ast.NotEq: "NE",
    ast.Lt: "LT",
    ast.LtE: "LE",
    ast.Gt: "GT",
    ast.GtE: "GE",
    ast.Is: "EQ",
    ast.IsNot: "NE",
}

# The TAC's unary operators, by the AST's operator node.
#
# There is no unary plus in the TAC, and there does not need to be: a
# unary plus is a no-op, so the operand is the result.
UNARY_OPERATORS = {
    ast.USub: "NEG",
    ast.Not: "NOT",
    ast.Invert: "BITNOT",
}

# The parameter names that mean "the receiver" in a method.
RECEIVER_NAMES = ("self", "cls")


def synthetic_signature(name, arity):
    """A synthetic callee key: ``<python>.name/arity``."""
    return "%s.%s/%d" % (SYNTHETIC_OWNER, name, arity)


class FunctionState:
    """One function's lowering state.

    The instruction list, the locals and the problems belong to the
    function being lowered, so they live here rather than on the
    translator.
    """

    def __init__(self, name, signature, enclosing_class, synthetic=False):
        self.name = name
        self.signature = signature
        self.enclosing_class = enclosing_class
        self.synthetic = synthetic
        self.params = []
        self.locals = []
        self.instructions = []
        self.problems = []
        self.problem_counts = {}
        self.temp_counter = 0
        self.label_counter = 0
        self.label_prefix = ""
        # The types this function inferred for its own variables. Python has
        # no static types, so they come from the annotations and from the
        # few assignments that name a class.
        self.types = {}
        # The names this scope binds that shadow a name from an outer scope.
        # Python decides the scope of a name when the function is compiled,
        # not when a statement runs, so a local named after one of the
        # module's own classes is that local EVERYWHERE in the function.
        self.shadowed_names = set()
        self.break_label = None
        self.continue_label = None

    def new_temp(self, type_=None):
        """A fresh temporary."""
        self.temp_counter += 1
        return var_op("t_t%d" % self.temp_counter, type_, True)

    def new_label(self, prefix="L"):
        """A fresh label."""
        self.label_counter += 1
        return "%s%s%d" % (self.label_prefix, prefix, self.label_counter)


class Translator:
    """The translator.

    One instance lowers one source file.
    """

    def __init__(self, module_name, language="PYTHON3", text=""):
        self.module_name = module_name or "<module>"
        self.language = language
        self.text = text
        self.state = None
        # The functions that the module's own code declared. They are lifted
        # out of the module's stream, because a function is a function.
        self.module_functions = []
        # The names the module's imports brought in.
        self.imported_modules = {}
        self.imported_names = {}
        # The classes and the functions the module declares.
        self.defined_classes = set()
        self.defined_functions = {}
        # The methods the module's classes declare. They are kept apart
        # from the module's own functions: a method's receiver is not one
        # of its declared parameters.
        self.defined_methods = {}
        # The function nodes that are methods, so that the receiver is not
        # counted twice in a method's arity.
        self.method_nodes = set()

    # ---- the module ------------------------------------------------------------------------

    def translate_module(self, tree):
        """The functions a module declares, in source order."""
        self.collect_bindings(tree)
        outer = self.state
        state = self.begin_function("<module>", self.module_name, None, synthetic=True)
        state.shadowed_names = self.scope_shadowed_names(tree.body, module_scope=True)
        self.guard(tree, lambda: self.lower_body(tree.body))
        functions = [self.finish_function(state)]
        self.state = outer
        functions.extend(self.module_functions)
        return functions

    def collect_bindings(self, tree):
        """Read the module's names before lowering it.

        The imports are what make a callee resolvable, and a callee that
        does not resolve is a callee signature that matches nothing -- a
        missing finding rather than a missing precision. So they are read
        first, over the whole module, including the ones inside a function.

        The walk knows each node's parent, because a function's place
        decides what it is: a function declared in a class body is a
        method, and a method's first parameter is the receiver.
        """
        self.walk_bindings(tree, None)

    def walk_bindings(self, node, parent):
        """Read one node's names, and then its children's."""
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.asname:
                    self.imported_modules[alias.asname] = alias.name
                else:
                    # `import a.b` binds the name `a`.
                    head = alias.name.split(".")[0]
                    self.imported_modules[head] = head
        elif isinstance(node, ast.ImportFrom):
            module = node.module or ""
            for alias in node.names:
                if alias.name == "*":
                    # A star import brings in names this translator cannot
                    # know. Nothing is bound, and the calls that use those
                    # names are reported as unresolved.
                    continue
                local = alias.asname or alias.name
                qualified = "%s.%s" % (module, alias.name) if module else alias.name
                self.imported_names[local] = qualified
                # `from os import path` binds a MODULE path, and
                # `path.join(...)` is a call on it.
                self.imported_modules.setdefault(local, qualified)
        elif isinstance(node, ast.ClassDef):
            self.defined_classes.add(node.name)
        elif isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if isinstance(parent, ast.ClassDef):
                # A method's first parameter is the receiver, so the method
                # is kept apart from the module's own functions.
                self.method_nodes.add(id(node))
                self.defined_methods.setdefault(node.name, node)
            else:
                self.defined_functions.setdefault(node.name, node)
        for child in ast.iter_child_nodes(node):
            self.walk_bindings(child, node)

    # ---- scope ----------------------------------------------------------------------------

    def scope_shadowed_names(self, body, module_scope=False):
        """The names a scope binds that shadow a name from an outer scope.

        <p>Python decides the scope of a name when the function is compiled,
        not when a statement runs: a name a function assigns is local to that
        function for its WHOLE body, whatever the order of the statements is.
        So a local named after one of the module's own classes is that local
        everywhere in the function, and a call of that name is a call of a
        VALUE -- not a construction of the class.

        <p>The names a nested class binds are not shadows. A class is still a
        class, and a call of that name still constructs one.

        <p>A ``global`` or ``nonlocal`` statement says the name belongs to an
        outer scope, so it does not bind it here.
        """
        bound = set()
        outer = set()
        self.walk_scope_bindings(body, bound, outer, module_scope)
        return bound - outer

    def walk_scope_bindings(self, nodes, bound, outer, module_scope):
        """Collect the names one scope binds.

        ``bound`` receives the names the scope binds to a value that is not a
        class; ``outer`` receives the names a ``global`` or ``nonlocal``
        statement gives to an outer scope.
        """
        for node in nodes:
            if isinstance(node, ast.ClassDef):
                # A class binds its name to a class, so it shadows nothing,
                # and its body is a scope of its own.
                continue
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                # A function binds its name to a function, and its body is a
                # scope of its own. At the module level the name is the
                # module's own declaration, which the module's names already
                # record.
                if not module_scope:
                    bound.add(node.name)
                continue
            if isinstance(node, ast.Lambda):
                # A lambda binds nothing, and its body is a scope of its own.
                continue
            if isinstance(
                node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)
            ):
                # A comprehension has a scope of its own, so its target binds
                # nothing here. A walrus inside it binds the scope that
                # encloses the comprehension.
                self.collect_walrus_targets(node, bound)
                continue
            if isinstance(node, (ast.Global, ast.Nonlocal)):
                outer.update(node.names)
                continue
            if isinstance(node, ast.ExceptHandler):
                # `except E as name` binds the name.
                if node.name:
                    bound.add(node.name)
                continue
            if isinstance(node, (ast.MatchAs, ast.MatchStar)):
                # A capture pattern binds the name it captures.
                if node.name:
                    bound.add(node.name)
                continue
            if isinstance(node, ast.Import):
                # `import a.b` binds the name `a`.
                for alias in node.names:
                    bound.add(alias.asname or alias.name.split(".")[0])
                continue
            if isinstance(node, ast.ImportFrom):
                for alias in node.names:
                    bound.add(alias.asname or alias.name.split(".")[0])
                continue
            if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
                bound.add(node.id)
                continue
            for child in ast.iter_child_nodes(node):
                self.walk_scope_bindings([child], bound, outer, False)

    def collect_walrus_targets(self, node, bound):
        """The names a walrus binds in the scope that encloses it.

        A walrus inside a comprehension binds the scope that ENCLOSES the
        comprehension, and not the comprehension's own scope.
        """
        for child in ast.walk(node):
            if isinstance(child, ast.NamedExpr) and isinstance(child.target, ast.Name):
                bound.add(child.target.id)

    # ---- functions ------------------------------------------------------------------------

    def begin_function(self, name, signature, enclosing_class, synthetic=False):
        """Start a function's lowering."""
        state = FunctionState(name, signature, enclosing_class, synthetic)
        self.state = state
        if enclosing_class:
            state.types["self"] = enclosing_class
        return state

    def finish_function(self, state):
        """Finish a function's lowering and produce its wire form."""
        for entry in state.problem_counts.values():
            if entry["count"] > 1:
                entry["problem"]["message"] = "%s (%d occurrences)" % (
                    entry["problem"]["message"],
                    entry["count"],
                )
        problems = state.problems
        return {
            "name": state.name,
            "signature": state.signature,
            "enclosingClass": state.enclosing_class,
            "precision": (
                # ANY problem downgrades the function. A problem means a
                # construct was approximated -- an unhandled node became an
                # unknown value, a break became a jump, an unresolved callee
                # became a black box -- and a function that was approximated
                # did not come out whole.
                Precision.LOWERED if problems else Precision.FULL
            ),
            "synthetic": state.synthetic,
            "params": state.params,
            "locals": state.locals,
            "instructions": state.instructions,
            "problems": problems,
        }

    def lower_function(self, node, enclosing_class):
        """Lower one function into its own stream."""
        outer = self.state
        owner = enclosing_class if enclosing_class else self.module_name
        state = self.begin_function(
            node.name, self.function_signature(node, owner), enclosing_class
        )
        state.shadowed_names = self.scope_shadowed_names(node.body)
        for parameter in self.parameters_of(node):
            state.shadowed_names.add(parameter.arg)
        try:
            self.lower_parameters(node)
            self.report_non_constant_defaults(node)
            self.lower_body(node.body)
        finally:
            self.state = outer
        return self.finish_function(state)

    def lower_parameters(self, node):
        """Declare the parameters, in declaration order."""
        for parameter in self.parameters_of(node):
            name = parameter.arg
            operand = var_op(name, self.tac_type_of_annotation(parameter.annotation))
            self.state.params.append(operand)
            annotation = self.annotation_name(parameter.annotation)
            if annotation:
                self.state.types[name] = annotation

    def parameters_of(self, node):
        """A function's parameters, in declaration order."""
        args = node.args
        out = list(args.posonlyargs) + list(args.args)
        if args.vararg is not None:
            out.append(args.vararg)
        out.extend(args.kwonlyargs)
        if args.kwarg is not None:
            out.append(args.kwarg)
        return out

    def report_non_constant_defaults(self, node):
        """Report a default value that is not a constant.

        A default is evaluated when the function is DEFINED, not when it is
        called, and it is evaluated in the enclosing scope. The TAC has one
        stream per function, so a default that reads a variable cannot be
        lowered into the function's own stream without changing what it
        means.
        """
        args = node.args
        positional = list(args.posonlyargs) + list(args.args)
        defaults = list(args.defaults)
        for parameter, default in zip(positional[len(positional) - len(defaults) :], defaults):
            if not isinstance(default, ast.Constant):
                self.report(
                    Problem.SOURCE_LOWERED,
                    default,
                    "a default value is evaluated where the function is defined, not where it is "
                    "called, so it is not part of the function's own stream",
                )

    def function_signature(self, node, owner):
        """The signature string: ``owner.name(ParamTypes):Return``."""
        types = []
        for parameter in self.parameters_of(node):
            types.append(self.annotation_name(parameter.annotation) or "any")
        return "%s.%s(%s):%s" % (
            owner,
            node.name,
            ",".join(types),
            self.annotation_name(node.returns) or "any",
        )

    def lower_class(self, node):
        """The methods of a class, and the class's own body.

        The class body runs when the class is created, so it is a function
        of its own: a synthetic one, named after the class's constructor.
        """
        functions = []
        class_name = node.name
        outer = self.state
        state = self.begin_function(
            "<init>",
            "%s.<init>():any" % class_name,
            class_name,
            synthetic=True,
        )
        state.shadowed_names = self.scope_shadowed_names(node.body)
        try:
            self.guard(node, lambda: self.lower_class_body(node, functions))
        finally:
            self.state = outer
        functions.insert(0, self.finish_function(state))
        return functions

    def lower_class_body(self, node, functions):
        """Lower the class body: its attributes, and its methods.

        A method's decorators run in the class body's scope, so they are
        emitted into the class body's own stream.
        """
        for statement in node.body:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
                functions.append(self.lower_function_declaration(statement, node.name))
            elif isinstance(statement, ast.ClassDef):
                functions.extend(self.lower_class(statement))
            else:
                self.lower_statement(statement)

    # ---- statements -----------------------------------------------------------------------

    def lower_body(self, body):
        """Lower a list of statements."""
        for statement in body:
            self.guard(statement, lambda s=statement: self.lower_statement(s))

    def lower_statement(self, node):
        """Lower one statement."""
        if isinstance(node, ast.Expr):
            self.lower_expression(node.value)
            return
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            self.module_functions.append(self.lower_function_declaration(node, None))
            return
        if isinstance(node, ast.ClassDef):
            self.module_functions.extend(self.lower_class(node))
            return
        if isinstance(node, ast.Assign):
            self.lower_assign(node)
            return
        if isinstance(node, ast.AnnAssign):
            self.lower_ann_assign(node)
            return
        if isinstance(node, ast.AugAssign):
            self.lower_aug_assign(node)
            return
        if isinstance(node, ast.Return):
            value = self.lower_expression(node.value) if node.value else None
            self.emit({"kind": Kind.RETURN, "lhs1": value, **self.position(node)})
            return
        if isinstance(node, ast.Raise):
            if node.cause:
                self.lower_expression(node.cause)
            value = self.lower_expression(node.exc) if node.exc else None
            self.emit({"kind": Kind.THROW, "lhs1": value, **self.position(node)})
            return
        if isinstance(node, ast.If):
            self.lower_if(node)
            return
        if isinstance(node, (ast.While,)):
            self.lower_while(node)
            return
        if isinstance(node, (ast.For, ast.AsyncFor)):
            self.lower_for(node)
            return
        if isinstance(node, ast.Break):
            if self.state.break_label:
                self.emit({"kind": Kind.JUMP, "label": self.state.break_label, **self.position(node)})
            else:
                self.report(
                    Problem.UNHANDLED_NODE, node, "this break is not inside a loop"
                )
            return
        if isinstance(node, ast.Continue):
            if self.state.continue_label:
                self.emit(
                    {"kind": Kind.JUMP, "label": self.state.continue_label, **self.position(node)}
                )
            else:
                self.report(
                    Problem.UNHANDLED_NODE, node, "this continue is not inside a loop"
                )
            return
        if isinstance(node, (ast.With, ast.AsyncWith)):
            self.lower_with(node)
            return
        if isinstance(node, ast.Try):
            self.lower_try(node)
            return
        if isinstance(node, ast.Assert):
            self.lower_expression(node.test)
            if node.msg:
                self.lower_expression(node.msg)
            return
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            # An import binds a name; the names were read before the module
            # was lowered. Nothing is emitted for it.
            return
        if isinstance(node, (ast.Global, ast.Nonlocal, ast.Pass)):
            return
        if isinstance(node, ast.Delete):
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "a deletion drops a value the analysis cannot see being dropped",
            )
            return
        if isinstance(node, ast.Match):
            self.report(
                Problem.UNHANDLED_NODE, node, "a match statement has no TAC lowering"
            )
            self.lower_expression(node.subject)
            return
        self.report(Problem.UNHANDLED_NODE, node, "this statement has no TAC lowering")

    def lower_function_declaration(self, node, enclosing_class):
        """A function that is declared in the stream being lowered.

        The function itself becomes a function of its own. What stays in the
        stream is its DECORATORS: a decorator runs where the function is
        defined, and it is what the name of the function holds afterwards.
        """
        for decorator in node.decorator_list:
            value = self.lower_expression(decorator)
            holder = var_op(node.name)
            self.declare_local(holder)
            applied = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": applied,
                    "calleeSig": synthetic_signature("decorator", 1),
                    "args": [
                        const_op(Const.STRING, SYNTHETIC_OWNER),
                        value,
                        holder,
                    ],
                    **self.position(node),
                }
            )
            self.emit(
                {
                    "kind": Kind.MOVE,
                    "dest": holder,
                    "lhs1": applied,
                    **self.position(node),
                }
            )
        return self.lower_function(node, enclosing_class)

    def lower_assign(self, node):
        """An assignment: ``a = b``."""
        value = self.lower_expression(node.value)
        for target in node.targets:
            self.lower_write(target, value, node)

    def lower_ann_assign(self, node):
        """An annotated assignment: ``a: T = b``."""
        value = self.lower_expression(node.value) if node.value else None
        annotation = self.annotation_name(node.annotation)
        if isinstance(node.target, ast.Name) and annotation:
            self.state.types[node.target.id] = annotation
        if value is not None:
            self.lower_write(node.target, value, node)

    def lower_aug_assign(self, node):
        """A compound assignment: ``a += b``.

        It reads the target, combines it, and writes it back. The read and
        the write are separate instructions, because the engine tracks the
        value of a location, not the statement that changed it.
        """
        current = self.lower_expression(node.target)
        right = self.lower_expression(node.value)
        operator = BINARY_OPERATORS.get(type(node.op))
        if operator is None:
            self.report(
                Problem.UNHANDLED_NODE, node, "this operator has no TAC lowering"
            )
            return
        value = self.state.new_temp(self.tac_type_of_expression(node.target))
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": value,
                "lhs1": current,
                "lhs2": right,
                "binop": operator,
                **self.position(node),
            }
        )
        self.lower_write(node.target, value, node)

    def lower_write(self, target, value, node):
        """A write to a location.

        A name is a local variable; an attribute and a subscript are entries
        of a namespace, so they are container stores with the key as the
        index.
        """
        if isinstance(target, ast.Name):
            variable = var_op(target.id, self.tac_type_of_variable(target.id))
            self.declare_local(variable)
            self.emit(
                {
                    "kind": Kind.MOVE,
                    "dest": variable,
                    "lhs1": value,
                    **self.position(target),
                }
            )
            self.infer_type_of(target.id, node.value if isinstance(node, ast.Assign) else None)
            return
        if isinstance(target, ast.Attribute):
            receiver = self.lower_expression(target.value)
            key = const_op(Const.STRING, target.attr)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": receiver,
                    "lhs2": key,
                    "dest": value,
                    **self.position(target),
                }
            )
            return
        if isinstance(target, ast.Subscript):
            receiver = self.lower_expression(target.value)
            key = self.lower_key(target.slice)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": receiver,
                    "lhs2": key,
                    "dest": value,
                    **self.position(target),
                }
            )
            return
        if isinstance(target, (ast.Tuple, ast.List)):
            # A tuple that is unpacked: the value holds the elements, and
            # each target reads its own position out of it.
            container = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.MOVE,
                    "dest": container,
                    "lhs1": value,
                    **self.position(target),
                }
            )
            for index, element in enumerate(target.elts):
                item = self.state.new_temp()
                self.emit(
                    {
                        "kind": Kind.ARRAYLOAD,
                        "dest": item,
                        "lhs1": container,
                        "lhs2": const_op(Const.INT, str(index)),
                        **self.position(target),
                    }
                )
                self.lower_write(element, item, node)
            return
        self.report(
            Problem.UNHANDLED_NODE, target, "this assignment target has no TAC lowering"
        )

    def lower_key(self, node):
        """The key of a subscript.

        A constant key names one entry; a key the analysis cannot resolve
        stands for every key.
        """
        if isinstance(node, ast.Constant):
            return self.lower_expression(node)
        if isinstance(node, ast.Slice):
            self.report(
                Problem.UNHANDLED_NODE, node, "a slice has no TAC lowering, so its key is unknown"
            )
            return self.emit_havoc(node)
        return self.lower_expression(node)

    def infer_type_of(self, name, value):
        """Record the type a variable was given, when the source says so.

        Python has no static types, so this is the narrow set of cases
        where the source names a class: a construction of a class that the
        module declares, and an annotation.
        """
        if value is None:
            return
        self.state.types.pop(name, None)
        if isinstance(value, ast.Call):
            function = value.func
            if isinstance(function, ast.Name) and function.id in self.defined_classes:
                self.state.types[name] = function.id
            elif isinstance(function, ast.Attribute) and function.attr in self.defined_classes:
                self.state.types[name] = function.attr

    def lower_if(self, node):
        """An if statement."""
        condition = self.lower_expression(node.test)
        then_label = self.state.new_label("L")
        else_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit(
            {
                "kind": Kind.BRANCH,
                "lhs1": condition,
                "trueLabel": then_label,
                "falseLabel": else_label,
                **self.position(node),
            }
        )
        self.emit({"kind": Kind.LABEL, "label": then_label, **self.position(node)})
        self.lower_body(node.body)
        self.emit({"kind": Kind.JUMP, "label": end_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": else_label, **self.position(node)})
        if node.orelse:
            self.lower_body(node.orelse)
        self.emit({"kind": Kind.LABEL, "label": end_label, **self.position(node)})

    def lower_while(self, node):
        """A while loop."""
        condition_label = self.state.new_label("L")
        body_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit({"kind": Kind.JUMP, "label": condition_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": condition_label, **self.position(node)})
        condition = self.lower_expression(node.test)
        self.emit(
            {
                "kind": Kind.BRANCH,
                "lhs1": condition,
                "trueLabel": body_label,
                "falseLabel": end_label,
                **self.position(node),
            }
        )
        self.emit({"kind": Kind.LABEL, "label": body_label, **self.position(node)})
        outer_break, outer_continue = self.state.break_label, self.state.continue_label
        self.state.break_label = end_label
        self.state.continue_label = condition_label
        self.lower_body(node.body)
        self.state.break_label, self.state.continue_label = outer_break, outer_continue
        self.emit({"kind": Kind.JUMP, "label": condition_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": end_label, **self.position(node)})
        if node.orelse:
            self.lower_body(node.orelse)

    def lower_for(self, node):
        """A for loop.

        The loop reads the iterable by index. The length is a call to the
        language's own ``len``, under a synthetic owner: the TAC has no
        length, and a stand-in says so.
        """
        iterable = self.lower_expression(node.iter)
        length = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.CALL,
                "dest": length,
                "calleeSig": synthetic_signature("len", 1),
                "args": [const_op(Const.STRING, SYNTHETIC_OWNER), iterable],
                **self.position(node),
            }
        )
        index = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.MOVE,
                "dest": index,
                "lhs1": const_op(Const.INT, "0"),
                **self.position(node),
            }
        )
        condition_label = self.state.new_label("L")
        body_label = self.state.new_label("L")
        post_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit({"kind": Kind.JUMP, "label": condition_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": condition_label, **self.position(node)})
        comparison = self.state.new_temp(Type.BOOLEAN)
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": comparison,
                "lhs1": index,
                "lhs2": length,
                "binop": "LT",
                **self.position(node),
            }
        )
        self.emit(
            {
                "kind": Kind.BRANCH,
                "lhs1": comparison,
                "trueLabel": body_label,
                "falseLabel": end_label,
                **self.position(node),
            }
        )
        self.emit({"kind": Kind.LABEL, "label": body_label, **self.position(node)})
        element = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.ARRAYLOAD,
                "dest": element,
                "lhs1": iterable,
                "lhs2": index,
                **self.position(node),
            }
        )
        self.lower_write(node.target, element, node)
        outer_break, outer_continue = self.state.break_label, self.state.continue_label
        self.state.break_label = end_label
        self.state.continue_label = post_label
        self.lower_body(node.body)
        self.state.break_label, self.state.continue_label = outer_break, outer_continue
        self.emit({"kind": Kind.LABEL, "label": post_label, **self.position(node)})
        incremented = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": incremented,
                "lhs1": index,
                "lhs2": const_op(Const.INT, "1"),
                "binop": "ADD",
                **self.position(node),
            }
        )
        self.emit(
            {
                "kind": Kind.MOVE,
                "dest": index,
                "lhs1": incremented,
                **self.position(node),
            }
        )
        self.emit({"kind": Kind.JUMP, "label": condition_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": end_label, **self.position(node)})
        if node.orelse:
            self.lower_body(node.orelse)

    def lower_with(self, node):
        """A with statement.

        The context manager's value is bound to the target. What its
        ``__enter__`` and ``__exit__`` do is not modelled: the TAC has no
        protocol for them.
        """
        for item in node.items:
            value = self.lower_expression(item.context_expr)
            if item.optional_vars is None:
                continue
            # A `with` item has no position of its own, so the statement's
            # position is the one that is reported.
            position = self.position(node)
            if isinstance(item.optional_vars, ast.Name):
                variable = var_op(item.optional_vars.id)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.MOVE,
                        "dest": variable,
                        "lhs1": value,
                        **position,
                    }
                )
            else:
                self.lower_write(item.optional_vars, value, node)
        self.lower_body(node.body)

    def lower_try(self, node):
        """A try / except / else / finally.

        The IR has explicit instructions for this: a CATCH_HANDLER binds
        the exception to the handler's name, and a FINALLY_ENTER marks the
        block every exit of the region runs through.
        """
        else_label = self.state.new_label("L")
        after_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        finally_label = self.state.new_label("L_FINALLY") if node.finalbody else None

        self.lower_body(node.body)
        self.emit({"kind": Kind.JUMP, "label": else_label, **self.position(node)})

        for handler in node.handlers:
            type_name = self.exception_type_name(handler.type)
            label = self.state.new_label("L_CATCH_%s" % type_name)
            self.emit({"kind": Kind.LABEL, "label": label, **self.position(handler)})
            if handler.name:
                variable = var_op(handler.name)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.CATCH_HANDLER,
                        "dest": variable,
                        "ctorType": self.tac_type_of_annotation(handler.type),
                        **self.position(handler),
                    }
                )
            else:
                self.emit(
                    {"kind": Kind.CATCH_HANDLER, **self.position(handler)}
                )
            self.lower_body(handler.body)
            self.emit({"kind": Kind.JUMP, "label": after_label, **self.position(handler)})

        self.emit({"kind": Kind.LABEL, "label": after_label, **self.position(node)})
        self.emit({"kind": Kind.JUMP, "label": end_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": else_label, **self.position(node)})
        if node.orelse:
            self.lower_body(node.orelse)
            self.emit({"kind": Kind.JUMP, "label": end_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": end_label, **self.position(node)})

        if node.finalbody:
            self.emit({"kind": Kind.LABEL, "label": finally_label, **self.position(node)})
            self.emit(
                {
                    "kind": Kind.FINALLY_ENTER,
                    "label": finally_label,
                    **self.position(node),
                }
            )
            self.lower_body(node.finalbody)

    # ---- expressions ----------------------------------------------------------------------

    def lower_expression(self, node):
        """Lower an expression to an operand.

        The result is an operand, not a value: an expression that produces
        a value gets a temporary, and the temporary is what the caller
        uses.
        """
        if node is None:
            return None
        if isinstance(node, ast.Constant):
            return self.lower_constant(node)
        if isinstance(node, ast.Name):
            return var_op(node.id, self.tac_type_of_variable(node.id))
        if isinstance(node, ast.Attribute):
            return self.lower_attribute_read(node)
        if isinstance(node, ast.Subscript):
            return self.lower_subscript_read(node)
        if isinstance(node, ast.BinOp):
            return self.lower_binary(node)
        if isinstance(node, ast.UnaryOp):
            return self.lower_unary(node)
        if isinstance(node, ast.BoolOp):
            return self.lower_boolop(node)
        if isinstance(node, ast.Compare):
            return self.lower_compare(node)
        if isinstance(node, ast.Call):
            return self.lower_call(node)
        if isinstance(node, ast.IfExp):
            return self.lower_ifexp(node)
        if isinstance(node, ast.Dict):
            return self.lower_dict(node)
        if isinstance(node, (ast.List, ast.Tuple)):
            return self.lower_sequence(node)
        if isinstance(node, ast.Set):
            return self.lower_set(node)
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.GeneratorExp)):
            return self.lower_comprehension(node)
        if isinstance(node, ast.DictComp):
            return self.lower_dict_comprehension(node)
        if isinstance(node, ast.Lambda):
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "a lambda has no TAC lowering, so the value it produces is unknown",
            )
            return self.emit_havoc(node)
        if isinstance(node, ast.Await):
            # The awaited value's result is what the expression produces.
            # The TAC has no coroutine, so the awaited value stands for it.
            return self.lower_expression(node.value)
        if isinstance(node, (ast.Yield, ast.YieldFrom)):
            self.report(
                Problem.UNHANDLED_NODE,
                node,
                "a generator has no TAC lowering, so the value it produces is unknown",
            )
            return self.emit_havoc(node)
        if isinstance(node, ast.NamedExpr):
            value = self.lower_expression(node.value)
            self.lower_write(node.target, value, node)
            return value
        if isinstance(node, ast.JoinedStr):
            return self.lower_joined_string(node)
        if isinstance(node, ast.FormattedValue):
            return self.lower_expression(node.value)
        if isinstance(node, ast.Starred):
            self.report(
                Problem.UNHANDLED_NODE, node, "a starred expression has no TAC lowering"
            )
            return self.emit_havoc(node)
        self.report(
            Problem.UNHANDLED_NODE, node, "this expression has no TAC lowering"
        )
        return self.emit_havoc(node)

    def lower_constant(self, node):
        """A literal."""
        value = node.value
        if value is None:
            return const_op(Const.NULL)
        if isinstance(value, bool):
            # A bool is an int in Python, so it has to be checked first.
            return const_op(Const.BOOL, "true" if value else "false")
        if isinstance(value, int):
            if -(2 ** 31) <= value < 2 ** 31:
                return const_op(Const.INT, str(value))
            if -(2 ** 63) <= value < 2 ** 63:
                return const_op(Const.LONG, str(value))
            return const_op(Const.STRING, str(value))
        if isinstance(value, float):
            return const_op(Const.DOUBLE, repr(value))
        if isinstance(value, str):
            return const_op(Const.STRING, value)
        if isinstance(value, bytes):
            return const_op(Const.STRING, value.decode("utf-8", "replace"))
        if isinstance(value, complex):
            return const_op(Const.STRING, repr(value))
        return const_op(Const.STRING, repr(value))

    def lower_attribute_read(self, node):
        """A read of an attribute.

        The key is a NAME in a namespace, so this is a container access with
        a string key and not a field access. ``obj.name`` and
        ``obj["name"]`` are the same location, and the engine's keyed
        namespace is what keeps them together.
        """
        receiver = self.lower_expression(node.value)
        key = const_op(Const.STRING, node.attr)
        dest = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.ARRAYLOAD,
                "dest": dest,
                "lhs1": receiver,
                "lhs2": key,
                **self.position(node),
            }
        )
        return dest

    def lower_subscript_read(self, node):
        """A read of an item: ``obj[key]``."""
        receiver = self.lower_expression(node.value)
        key = self.lower_key(node.slice)
        dest = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.ARRAYLOAD,
                "dest": dest,
                "lhs1": receiver,
                "lhs2": key,
                **self.position(node),
            }
        )
        return dest

    def lower_binary(self, node):
        """A binary operator."""
        operator = BINARY_OPERATORS.get(type(node.op))
        approximation = APPROXIMATED_OPERATORS.get(type(node.op))
        if approximation is not None:
            operator, note = approximation
            self.report(Problem.SOURCE_LOWERED, node, note)
        left = self.lower_expression(node.left)
        right = self.lower_expression(node.right)
        if operator is None:
            self.report(
                Problem.UNHANDLED_NODE, node, "this operator has no TAC lowering"
            )
            return self.emit_havoc(node)
        dest = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": dest,
                "lhs1": left,
                "lhs2": right,
                "binop": operator,
                **self.position(node),
            }
        )
        return dest

    def lower_unary(self, node):
        """A unary operator."""
        operator = UNARY_OPERATORS.get(type(node.op))
        operand = self.lower_expression(node.operand)
        if operator is None:
            if isinstance(node.op, ast.UAdd):
                # A unary plus is a no-op: the operand is the result.
                return operand
            self.report(
                Problem.UNHANDLED_NODE, node, "this operator has no TAC lowering"
            )
            return self.emit_havoc(node)
        dest = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.UNARYOP,
                "dest": dest,
                "lhs1": operand,
                "unop": operator,
                **self.position(node),
            }
        )
        return dest

    def lower_boolop(self, node):
        """A short-circuit operator: ``and`` or ``or``.

        The TAC's LAND and LOR take both operands, and the taint of either
        reaches the result -- which is what the short-circuit operators
        mean for a value that is read afterwards.
        """
        operator = "LAND" if isinstance(node.op, ast.And) else "LOR"
        result = None
        for value in node.values:
            operand = self.lower_expression(value)
            if result is None:
                result = operand
                continue
            dest = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.BINOP,
                    "dest": dest,
                    "lhs1": result,
                    "lhs2": operand,
                    "binop": operator,
                    **self.position(node),
                }
            )
            result = dest
        return result

    def lower_compare(self, node):
        """A comparison."""
        result = None
        left = self.lower_expression(node.left)
        for operator_node, comparator in zip(node.ops, node.comparators):
            operator = COMPARISON_OPERATORS.get(type(operator_node))
            right = self.lower_expression(comparator)
            if operator is None:
                self.report(
                    Problem.UNHANDLED_NODE,
                    node,
                    "this comparison has no TAC lowering, so its result is unknown",
                )
                return self.emit_havoc(node)
            dest = self.state.new_temp(Type.BOOLEAN)
            self.emit(
                {
                    "kind": Kind.BINOP,
                    "dest": dest,
                    "lhs1": left,
                    "lhs2": right,
                    "binop": operator,
                    **self.position(node),
                }
            )
            if result is None:
                result = dest
            else:
                combined = self.state.new_temp(Type.BOOLEAN)
                self.emit(
                    {
                        "kind": Kind.BINOP,
                        "dest": combined,
                        "lhs1": result,
                        "lhs2": dest,
                        "binop": "LAND",
                        **self.position(node),
                    }
                )
                result = combined
            left = right
        return result

    def lower_ifexp(self, node):
        """A conditional expression: ``a if test else b``."""
        condition = self.lower_expression(node.test)
        then_label = self.state.new_label("L")
        else_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        dest = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.BRANCH,
                "lhs1": condition,
                "trueLabel": then_label,
                "falseLabel": else_label,
                **self.position(node),
            }
        )
        self.emit({"kind": Kind.LABEL, "label": then_label, **self.position(node)})
        value = self.lower_expression(node.body)
        self.emit({"kind": Kind.MOVE, "dest": dest, "lhs1": value, **self.position(node)})
        self.emit({"kind": Kind.JUMP, "label": end_label, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": else_label, **self.position(node)})
        value = self.lower_expression(node.orelse)
        self.emit({"kind": Kind.MOVE, "dest": dest, "lhs1": value, **self.position(node)})
        self.emit({"kind": Kind.LABEL, "label": end_label, **self.position(node)})
        return dest

    def lower_sequence(self, node):
        """A list or a tuple literal."""
        dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
        for index, element in enumerate(node.elts):
            value = self.lower_expression(element)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": dest,
                    "lhs2": const_op(Const.INT, str(index)),
                    "dest": value,
                    **self.position(element),
                }
            )
        return dest

    def lower_set(self, node):
        """A set literal.

        A set has no order, so the entries are stored under indices that
        say nothing about which entry is which.
        """
        self.report(
            Problem.SOURCE_LOWERED,
            node,
            "a set has no order, so its entries are stored under indices that say nothing about "
            "which entry is which",
        )
        dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
        for index, element in enumerate(node.elts):
            value = self.lower_expression(element)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": dest,
                    "lhs2": const_op(Const.INT, str(index)),
                    "dest": value,
                    **self.position(element),
                }
            )
        return dest

    def lower_dict(self, node):
        """A dict literal.

        A dict is a keyed container: an entry is a namespace entry, and
        the key is part of its identity.
        """
        dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
        for key_node, value_node in zip(node.keys, node.values):
            value = self.lower_expression(value_node)
            if key_node is None:
                # The `**` spread: the keys are not known here.
                self.report(
                    Problem.SOURCE_LOWERED,
                    node,
                    "a spread into a dict has no TAC lowering, so its entries are not stored",
                )
                continue
            key = self.lower_expression(key_node)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": dest,
                    "lhs2": key,
                    "dest": value,
                    **self.position(value_node),
                }
            )
        return dest

    def lower_comprehension(self, node):
        """A list, a set or a generator comprehension.

        A comprehension has a scope of its own, and the TAC has no scopes.
        The loop variables are bound once, from the first element, and the
        element is evaluated once: the entries after the first are not.
        """
        self.report(
            Problem.SOURCE_LOWERED,
            node,
            "a comprehension has a scope of its own, so its entries after the first are not "
            "modelled",
        )
        for generator in node.generators:
            iterable = self.lower_expression(generator.iter)
            element = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.ARRAYLOAD,
                    "dest": element,
                    "lhs1": iterable,
                    "lhs2": const_op(Const.INT, "0"),
                    **self.position(generator),
                }
            )
            self.lower_write(generator.target, element, generator)
        dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
        value = self.lower_expression(node.elt)
        self.emit(
            {
                "kind": Kind.ARRAYSTORE,
                "lhs1": dest,
                "lhs2": const_op(Const.INT, "0"),
                "dest": value,
                **self.position(node),
            }
        )
        return dest

    def lower_dict_comprehension(self, node):
        """A dict comprehension."""
        self.report(
            Problem.SOURCE_LOWERED,
            node,
            "a comprehension has a scope of its own, so its entries after the first are not "
            "modelled",
        )
        for generator in node.generators:
            iterable = self.lower_expression(generator.iter)
            element = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.ARRAYLOAD,
                    "dest": element,
                    "lhs1": iterable,
                    "lhs2": const_op(Const.INT, "0"),
                    **self.position(generator),
                }
            )
            self.lower_write(generator.target, element, generator)
        dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
        key = self.lower_expression(node.key)
        value = self.lower_expression(node.value)
        self.emit(
            {
                "kind": Kind.ARRAYSTORE,
                "lhs1": dest,
                "lhs2": key,
                "dest": value,
                **self.position(node),
            }
        )
        return dest

    def lower_joined_string(self, node):
        """An f-string.

        The string is the concatenation of its parts, and the taint of a
        hole reaches the string.
        """
        parts = []
        for value_node in node.values:
            if isinstance(value_node, ast.Constant):
                parts.append(const_op(Const.STRING, value_node.value))
            else:
                parts.append(self.lower_expression(value_node))
        if not parts:
            return const_op(Const.STRING, "")
        result = parts[0]
        for part in parts[1:]:
            dest = self.state.new_temp(Type.STRING)
            self.emit(
                {
                    "kind": Kind.BINOP,
                    "dest": dest,
                    "lhs1": result,
                    "lhs2": part,
                    "binop": "ADD",
                    **self.position(node),
                }
            )
            result = dest
        return result

    def lower_call(self, node):
        """A call.

        The receiver slot is reserved whether or not the call has a
        receiver object: ``args[0]`` is the receiver, ``args[1..]`` are the
        arguments. A free function has no receiver object, so the slot
        holds the MODULE's name as a constant -- the same shape the other
        front ends use for a static call.
        """
        construction = self.resolve_construction(node)
        if construction is not None:
            return self.lower_construction(node, construction)
        callee = self.resolve_callee(node)
        arguments = []
        for argument in node.args:
            arguments.append(self.lower_argument(argument))
        for keyword in node.keywords:
            arguments.append(self.lower_argument(keyword.value))
        if node.keywords:
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "a keyword argument is passed as a positional one, so the arguments' order is "
                "not the order the callee sees",
            )
        args = [callee["receiver"]] + arguments
        dest = self.state.new_temp()
        kind = Kind.CALL if callee["resolved"] else Kind.UNINTERPRETED
        if not callee["resolved"]:
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "the callee could not be resolved, so the call is modelled as a black box: %s"
                % callee["reason"],
            )
        self.emit(
            {
                "kind": kind,
                "dest": dest,
                "calleeSig": "%s.%s/%d" % (callee["owner"], callee["method"], callee["arity"]),
                "args": args,
                **self.position(node),
            }
        )
        return dest

    def resolve_construction(self, node):
        """The class a call CONSTRUCTS, when the call constructs one.

        <p>Python has no ``new`` keyword: ``Store(x)`` and ``store(x)`` are
        spelled the same way, and only what the name resolves to tells them
        apart. A name this module DECLARES as a class is a construction; a
        name it declares as a function is a call. A name from an import is
        neither -- the import does not say which of the two it binds -- so it
        stays a call, because the catalogue's Python entries are written for
        calls.

        <p>The answer is a fact about the module's own declarations, so it is
        the same for every call of that name in the module -- UNLESS the scope
        being lowered binds the name to something of its own.
        """
        function = node.func
        if not isinstance(function, ast.Name):
            return None
        name = function.id
        if name not in self.defined_classes:
            return None
        if name in self.defined_functions:
            # The module declares both a class and a function under this
            # name, so the name alone cannot say which one the call means.
            return None
        if name in self.state.shadowed_names:
            # The scope being lowered binds this name to a value of its own --
            # a local, a parameter, an import. Python decides the scope of a
            # name when the function is compiled, so the local shadows the
            # class for the WHOLE body, and the call is a call of a value
            # and not a construction of the class.
            return None
        return name

    def lower_construction(self, node, class_name):
        """A construction: a NEW whose callee key names the constructor.

        <p>The key is ``<unknown>.<Class>.<init>/<arity>``. The
        ``<init>`` part is what the JVM, TypeScript, ArkTS, C# and PHP front
        ends all use for a construction, so a catalogue entry written for one
        of them can match a Python construction too -- the catalogue has no
        Python constructor entries yet, and this is the shape they will have
        to use when it gets them.

        <p>The owner stays ``<unknown>``, and that is deliberate: a file
        called ``subprocess.py`` that declares ``class Popen`` must not
        produce a key the catalogue can claim for the real
        ``subprocess.Popen``. The owner of a class this module declares
        cannot be named, because the file's name is arbitrary.

        <p>The arity counts the POSITIONAL arguments only, which is what the
        catalogue's Python entries are written against: ``dict(a=x)`` is
        ``builtins.dict/0`` and ``dict(x)`` is ``builtins.dict/1``. The
        keyword argument is still lowered into the argument list, because
        that is where the tainted value lands.
        """
        arguments = []
        for argument in node.args:
            arguments.append(self.lower_argument(argument))
        for keyword in node.keywords:
            arguments.append(self.lower_argument(keyword.value))
        if node.keywords:
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "a keyword argument is passed as a positional one, so the arguments' order is "
                "not the order the callee sees",
            )
        dest = self.state.new_temp(self.tac_type_of_name(class_name))
        self.emit(
            {
                "kind": Kind.NEW,
                "dest": dest,
                "calleeSig": "%s.%s.<init>/%d"
                % (UNKNOWN_OWNER, class_name, self.passed_arity(node)),
                "ctorType": self.tac_type_of_name(class_name),
                "args": arguments,
                **self.position(node),
            }
        )
        return dest

    def lower_argument(self, node):
        """One argument of a call.

        A starred argument is unpacked by the callee, and the TAC has no
        way to say that, so the value is passed as one argument and the
        difference is reported.
        """
        if isinstance(node, ast.Starred):
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "a starred argument is unpacked by the callee, and the TAC has no way to say "
                "that, so the value is passed as one argument",
            )
            return self.lower_expression(node.value)
        return self.lower_expression(node)

    def resolve_callee(self, node):
        """The callee of a call: its owner, its name and its arity.

        Python resolves a call by NAME, and the name is what the sink
        catalogue matches on. So the imports are read: ``os.system(cmd)`` and
        ``from os import system; system(cmd)`` are the same function and
        must produce the same callee signature.
        """
        function = node.func
        if isinstance(function, ast.Name):
            return self.resolve_free_function(function, node)
        if isinstance(function, ast.Attribute):
            return self.resolve_method(function, node)
        return {
            "owner": UNKNOWN_OWNER,
            "method": "<call>",
            "receiver": const_op(Const.STRING, UNKNOWN_OWNER),
            "arity": self.passed_arity(node),
            "resolved": False,
            "reason": "the callee is not a name",
        }

    def passed_arity(self, node):
        """The number of POSITIONAL arguments a call passes.

        A keyword argument is not a positional one: the callee takes it by
        name, so it does not occupy a position. And a Python API's arity is
        the number of parameters it takes positionally -- ``subprocess.run``
        takes one, whatever else it accepts by keyword.
        """
        return len(node.args)

    def resolve_free_function(self, function, node):
        """A call of a name: a function of the module, or of an import."""
        name = function.id
        qualified = self.imported_names.get(name)
        if qualified and "." in qualified:
            owner, method = qualified.rsplit(".", 1)
            return {
                "owner": owner,
                "method": method,
                "receiver": const_op(Const.STRING, owner),
                # An imported function's declared parameters are not visible
                # here, so the arity is the number of arguments passed.
                "arity": self.passed_arity(node),
                "resolved": True,
                "reason": None,
            }
        if name in BUILTIN_NAMES:
            return {
                "owner": "builtins",
                "method": name,
                "receiver": const_op(Const.STRING, "builtins"),
                "arity": self.passed_arity(node),
                "resolved": True,
                "reason": None,
            }
        definition = self.defined_functions.get(name)
        if definition is not None:
            return {
                "owner": self.module_name,
                "method": name,
                "receiver": const_op(Const.STRING, self.module_name),
                "arity": self.declared_arity(definition),
                "resolved": True,
                "reason": None,
            }
        if name in self.imported_modules:
            reason = "this name is a module, so calling it could not be resolved"
        else:
            reason = (
                "the name is neither a builtin, nor an import, nor a function of this module"
            )
        return {
            "owner": UNKNOWN_OWNER,
            "method": name,
            "receiver": const_op(Const.STRING, UNKNOWN_OWNER),
            "arity": self.passed_arity(node),
            "resolved": False,
            "reason": reason,
        }

    def resolve_method(self, function, node):
        """A call of an attribute: a method, or a function of a module."""
        method = function.attr
        owner = self.owner_of_dotted(self.dotted_name(function.value))
        if owner is not None:
            return {
                "owner": owner,
                "method": method,
                "receiver": const_op(Const.STRING, owner),
                "arity": self.arity_of_name(method, node),
                "resolved": True,
                "reason": None,
            }
        receiver = self.lower_expression(function.value)
        receiver_type = self.type_of_receiver(function.value)
        if receiver_type is None:
            return {
                "owner": UNKNOWN_OWNER,
                "method": method,
                "receiver": receiver,
                "arity": self.passed_arity(node),
                "resolved": False,
                "reason": (
                    "the receiver's type could not be resolved, so the callee's owner is unknown"
                ),
            }
        return {
            "owner": receiver_type,
            "method": method,
            "receiver": receiver,
            "arity": self.arity_of_name(method, node),
            "resolved": True,
            "reason": None,
        }

    def owner_of_dotted(self, dotted):
        """The module a dotted name names, or None.

        ``import os`` makes ``os`` a module; ``import numpy as np`` makes
        ``np`` one, and the module it names is ``numpy``. The owner of a
        call is that module, with the path that follows it.
        """
        if not dotted:
            return None
        parts = dotted.split(".")
        for count in range(len(parts), 0, -1):
            prefix = ".".join(parts[:count])
            module = self.imported_modules.get(prefix)
            if module is not None:
                suffix = ".".join(parts[count:])
                return "%s.%s" % (module, suffix) if suffix else module
        return None

    def dotted_name(self, node):
        """The dotted name of a name or of an attribute chain."""
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            base = self.dotted_name(node.value)
            if base is None:
                return None
            return "%s.%s" % (base, node.attr)
        return None

    def type_of_receiver(self, node):
        """The type of a receiver, when the source says so."""
        if isinstance(node, ast.Name):
            return self.state.types.get(node.id)
        return None

    def arity_of_name(self, method, node):
        """The arity of a call.

        The arity counts the DECLARED parameters of a function the module
        declares, because that is the signature the sink catalogue matches
        on. A function from elsewhere has no declared parameters the
        analysis can see, so the arity is the number of positional
        arguments the call passes.
        """
        definition = self.defined_methods.get(method)
        if definition is None:
            definition = self.defined_functions.get(method)
        if definition is not None:
            return self.declared_arity(definition)
        return self.passed_arity(node)

    def declared_arity(self, definition):
        """The declared parameters of a function, without the receiver.

        The receiver is not one of them: a method with one parameter is
        ``UserStore.find/1``, with the receiver at ``args[0]``.
        """
        args = definition.args
        count = len(args.posonlyargs) + len(args.args)
        if args.vararg is not None:
            count += 1
        if id(definition) in self.method_nodes:
            count -= 1
        return max(count, 0)

    # ---- helpers --------------------------------------------------------------------------

    def emit(self, instruction):
        """Add one instruction to the current function."""
        self.state.instructions.append(instruction)
        return instruction

    def declare_local(self, variable):
        """Declare a local, once."""
        if not variable or not variable.get("name"):
            return
        for local in self.state.locals:
            if local.get("name") == variable["name"]:
                return
        self.state.locals.append(variable)

    def guard(self, node, body):
        """Lower one statement or function, reporting anything that goes wrong.

        The lowering of ONE function must not take the whole file down, and
        it must not be silent either. A construct the translator does not
        understand is a problem on that function -- the function's
        precision is downgraded and the reader is told what went wrong.

        So the failure is caught here and REPORTED. It is deliberately not
        swallowed: a function that was only half lowered looks exactly like
        a function that was lowered whole, and the difference is a finding.
        """
        try:
            body()
        except Exception as exc:  # noqa: BLE001 - the point is to report it
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                "this could not be lowered completely: %s" % exc,
            )

    def emit_havoc(self, node):
        """A value the translator could not lower.

        HAVOC says "this value is unknown". It is neither tainted nor
        clean, and the checkers read it as unknown rather than as safe.
        """
        dest = self.state.new_temp({"kind": Type.UNKNOWN})
        self.emit({"kind": Kind.HAVOC, "dest": dest, **self.position(node)})
        return dest

    def report(self, kind, node, message):
        """Report a problem on the current function.

        The same problem in the same function is reported ONCE, with the
        number of times it happened: a file with fifty calls to a name the
        analysis cannot resolve has one problem, not fifty.
        """
        key = (kind, message)
        entry = self.state.problem_counts.get(key)
        if entry is not None:
            entry["count"] += 1
            return
        problem = {
            "kind": kind,
            "message": message,
            "nodeClass": type(node).__name__ if node is not None else None,
        }
        problem.update(self.position(node))
        self.state.problem_counts[key] = {"problem": problem, "count": 1}
        self.state.problems.append(problem)

    def position(self, node):
        """The node's one-based line and column."""
        if node is None:
            return {"line": -1, "column": -1}
        line = getattr(node, "lineno", -1)
        column = getattr(node, "col_offset", -1)
        return {"line": line if line else -1, "column": column + 1 if column >= 0 else -1}

    def annotation_name(self, node):
        """The name an annotation gives, or None."""
        if node is None:
            return None
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            return node.attr
        if isinstance(node, ast.Subscript):
            return self.annotation_name(node.value)
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.Constant) and node.value is None:
            return "None"
        return None

    def exception_type_name(self, node):
        """The name of the exception a handler catches.

        A handler that catches several types names them in a tuple; the
        first one is the one the label carries.
        """
        if node is None:
            # A bare `except` catches everything.
            return "Exception"
        if isinstance(node, ast.Tuple) and node.elts:
            return self.exception_type_name(node.elts[0])
        return self.annotation_name(node) or "Exception"

    def tac_type_of_annotation(self, node):
        """The TAC type of an annotation."""
        return self.tac_type_of_name(self.annotation_name(node))

    def tac_type_of_variable(self, name):
        """The TAC type of a variable, when the function inferred one."""
        if name is None or self.state is None:
            return {"kind": Type.UNKNOWN}
        return self.tac_type_of_name(self.state.types.get(name))

    def tac_type_of_expression(self, node):
        """The TAC type of an expression."""
        if isinstance(node, ast.Name):
            return self.tac_type_of_variable(node.id)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            if node.func.id in self.defined_classes:
                return self.tac_type_of_name(node.func.id)
        return {"kind": Type.UNKNOWN}

    def tac_type_of_name(self, name):
        """The TAC type of a Python type name."""
        if not name:
            return {"kind": Type.UNKNOWN}
        simple = name.split(".")[-1]
        if simple == "int":
            return {"kind": Type.INT}
        if simple == "str":
            return {"kind": Type.STRING}
        if simple == "bool":
            return {"kind": Type.BOOLEAN}
        if simple == "float":
            return {"kind": Type.DOUBLE}
        if simple == "bytes":
            return {"kind": Type.STRING}
        if simple in ("None", "NoneType"):
            return {"kind": Type.NULL}
        if simple in ("list", "tuple", "set", "frozenset", "dict"):
            return {"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}}
        return {"kind": Type.CLASS, "name": name}
