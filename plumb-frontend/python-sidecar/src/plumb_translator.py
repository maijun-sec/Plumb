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

from plumb_wire import (
    Const,
    DropImpact,
    Guard,
    Kind,
    LabelKind,
    MemberKind,
    Precision,
    Problem,
    ScopeKind,
    Type,
    const_op,
    var_op,
)

# The names Python's own runtime provides. A call to one of them is a call
# into the ``builtins`` module, and that is the owner the callee signature
# carries.
BUILTIN_NAMES = frozenset(dir(builtins))

# The type a call of a BUILT-IN function returns.
#
# These are facts about the language, not declarations: `int(x)` returns an int
# whatever x is, and so does `len(x)`. A call of one of them therefore
# produces a value whose type the analysis knows, and the TAC says so -- the
# destination of the call carries the type.
#
# Only the functions whose result is the SAME TYPE for every argument are
# listed. `abs` is not one of them: `abs(3)` is an int and `abs(3.5)` is a
# float, and a table that had to pick one of the two would be wrong half the
# time. A function that is missing from this table has no known result type,
# and the destination of its call stays untyped -- which is the safe way to be
# wrong, because an untyped value is reported rather than dismissed.
BUILTIN_RETURN_TYPES = {
    # The type constructors.
    "int": "int",
    "str": "str",
    "bool": "bool",
    "float": "float",
    "bytes": "bytes",
    # The counts and the identities: an int, always.
    "len": "int",
    "id": "int",
    "ord": "int",
    "hash": "int",
    # The text: a str, always.
    "chr": "str",
    "repr": "str",
    "ascii": "str",
    "format": "str",
    "input": "str",
    "hex": "str",
    "oct": "str",
    "bin": "str",
    # The questions: a bool, always.
    "isinstance": "bool",
    "callable": "bool",
    "hasattr": "bool",
    # The containers.
    "list": "list",
    "tuple": "tuple",
    "set": "set",
    "frozenset": "frozenset",
    "dict": "dict",
    "sorted": "list",
}


def return_type_of_signature(signature):
    """The type a function's signature declares it returns.

    <p>A signature is ``owner.name(paramTypes):return``. The return part is
    what the source declared, and ``any`` means the source declared nothing.
    """
    if not signature:
        return None
    close = signature.rfind(")")
    if close < 0:
        return None
    tail = signature[close + 1 :]
    if not tail.startswith(":"):
        return None
    name = tail[1:].strip()
    if not name or name == "any":
        return None
    return name

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
    # The membership tests: ``x in items`` asks whether the VALUE is one of
    # the items' values, and it is a comparison like any other. The TAC has
    # no membership operator, so it is modelled as an equality test.
    ast.In: "EQ",
    ast.NotIn: "NE",
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

# The value domain of a Python integer.
#
# Python's int is ARBITRARY precision: there is no width, so there is no
# bound to check against. A checker that treated it as a 64-bit integer
# would report an overflow that Python does not have.
UNBOUNDED_DOMAIN = {"domain": "UNBOUNDED"}

# The value domain of a Python float.
#
# A Python float is an IEEE 754 double, always.
FLOATING_DOMAIN = {"domain": "FLOATING", "bits": 53, "exponentBits": 11}


def precision_of(drops):
    """The precision a function carries, given its drops.

    A drop says what was lost. A drop that cost the function its SEMANTICS
    leaves it DEGRADED; a drop that cost it only its SHAPE leaves it
    LOWERED; a drop that cost one value, or one type, leaves it whole --
    because the analysis still knows everything it needs to know about every
    other value.
    """
    lowered = False
    for drop in drops or ():
        if drop["impact"] == DropImpact.SEMANTICS:
            return Precision.DEGRADED
        if drop["impact"] == DropImpact.STRUCTURE:
            lowered = True
    return Precision.LOWERED if lowered else Precision.FULL


def construct_of(kind, node):
    """The construct a problem names, when the problem does not name one.

    A construct is a stable word, so that a gate can compare the words the
    sidecar actually emits with the words the vocabulary lists.
    """
    node_class = type(node).__name__ if node is not None else "node"
    if kind == Problem.UNHANDLED_NODE:
        return "unhandled.%s" % node_class
    if kind in (Problem.PARSE, Problem.TRANSLATION):
        return "parse.%s" % node_class
    return "lowered.%s" % node_class


def impact_of_problem(kind):
    """The impact a problem kind has, when the problem does not say itself.

    A node the translator does not understand at all costs the function its
    semantics; a construct it understood but could not shape exactly costs
    it only its shape.
    """
    if kind == Problem.SOURCE_LOWERED:
        return DropImpact.STRUCTURE
    return DropImpact.SEMANTICS


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
        # The name of the parameter that holds the CLASS, in a class method.
        # It is the class itself, so a call on it is a call on the class.
        self.class_receiver = None
        self.params = []
        self.locals = []
        self.instructions = []
        self.problems = []
        self.problem_counts = {}
        self.temp_counter = 0
        self.label_counter = 0
        self.label_prefix = ""
        # The AST node of the function being lowered, when there is one. A
        # call has to know which function it is written in: a closure can
        # only be called from inside the scope that created it.
        self.ast_node = None
        # The names this function's scope can see: what it binds, and what
        # the scopes around it bind. A name the MODULE binds is not in
        # here -- a global is not a capture.
        self.scope_names = set()
        # The closures this function's own variables hold, by variable
        # name. A call of such a variable has to reach the closure's body.
        self.lambda_by_var = {}
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
        # What this function IS inside its owner: a method, the constructor,
        # or the function that runs a class body's field initializers.
        self.member_kind = MemberKind.METHOD
        # The declared parameters, with the facts a declaration carries and
        # a positional list cannot: whether a parameter is keyword-only,
        # variadic, or taken by reference.
        self.parameters = []
        # The names this function binds in a scope that is not its own.
        self.scope_bindings = []
        # The exception regions this function contains.
        self.try_regions = []
        # The branch regions: the match dispatches, with their cases.
        self.branch_regions = []
        # The annotations: the five channels the IR has.
        self.class_annotations = []
        self.method_annotations = []
        self.field_annotations = []
        self.param_annotations = []
        self.annotation_values = {}
        # The drops: what this translator could not lower, and what that
        # cost.
        self.drops = []
        self.drop_counts = {}
        # The label kinds, by label name, so that a jump can say what it
        # jumps to.
        self.label_kinds = {}
        # The scope ids, by label name, so that a jump and its target agree.
        self.label_scopes = {}
        # The counter that names the scopes.
        self.scope_counter = 0
        # The stack of enclosing loops, so that a ``break`` knows which one
        # it leaves.
        self.loop_stack = []
        # The ``finally`` blocks this function is inside, innermost last. A
        # ``return`` inside a ``try`` has to run them before it leaves.
        self.finally_stack = []
        # The class this function's code is in, when it is one.
        self.super_type = None
        # The labels a generator resumes at.
        self.generator_states = []
        # The generator's slot: the value it yields.
        self.generator_slot = None

    def new_temp(self, type_=None):
        """A fresh temporary."""
        self.temp_counter += 1
        return var_op("t_t%d" % self.temp_counter, type_, True)

    def new_label(self, prefix="L"):
        """A fresh label."""
        self.label_counter += 1
        return "%s%s%d" % (self.label_prefix, prefix, self.label_counter)

    def new_scope(self):
        """A fresh scope id: one step deeper than the scopes before it."""
        self.scope_counter += 1
        return self.scope_counter


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
        # The methods the module's classes declare, by the class that declares
        # them. The plain table above is keyed by the method's name alone,
        # which is enough to answer "how many parameters does this call
        # pass", but NOT enough to answer "what does this call return": two
        # classes may declare a method of the same name and return different
        # types, and the class that asked is the one that decides.
        self.class_methods = {}
        # The function nodes that are methods, so that the receiver is not
        # counted twice in a method's arity.
        self.method_nodes = set()
        # What each function captures, by its AST node. A capture is a name
        # the function reads from the scope around it.
        self.function_captures = {}
        # The names each function's scope can see, by its AST node.
        self.function_scope = {}
        # The function each function is written inside, by its AST node.
        self.function_enclosing = {}
        # The functions each function is written inside, by its AST node.
        self.function_ancestors = {}
        # The counter that names the lambdas of one module.
        self.lambda_counter = 0
        # The closures of the module, by the AST node they were written
        # as: the name of their own function, the values they capture and
        # how many arguments they take.
        self.lambda_by_node = {}
        # The type declarations the module's classes make. They are the
        # module's type surface; the response's `types` key carries them.
        self.type_declarations = []
        # The counter that names the closures of one module.
        self.closure_counter = 0
        # The declared parameter names, by the callee key. A keyword
        # argument binds by name, so the call site has to know what the
        # callee calls them.
        self.declared_parameter_names = {}
        # The scopes of the comprehensions being lowered. A comprehension
        # has a scope of its own, and its loop variable does not leak into
        # the scope around it.
        self.comp_scope_stack = []
        # The counter that names the comprehensions' own locals.
        self.comp_counter = 0

    # ---- the module ------------------------------------------------------------------------

    def translate_module(self, tree):
        """The functions a module declares, in source order."""
        self.collect_bindings(tree)
        self.collect_scopes(tree)
        outer = self.state
        state = self.begin_function("<module>", self.module_name, None, synthetic=True)
        state.member_kind = MemberKind.MODULE
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
            if node.level:
                # A RELATIVE import: the dots say how far up the package
                # tree the module is, and the number of them is a fact
                # about the name.
                module = "." * node.level + module
            for alias in node.names:
                if alias.name == "*":
                    # A star import brings in names this translator cannot
                    # know. Nothing is bound, and the calls that use those
                    # names are reported as unresolved.
                    continue
                local = alias.asname or alias.name
                if module.endswith("."):
                    qualified = module + alias.name
                elif module:
                    qualified = "%s.%s" % (module, alias.name)
                else:
                    qualified = alias.name
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
                self.class_methods.setdefault((parent.name, node.name), node)
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

    # ---- the scopes of the functions ------------------------------------------------------

    def collect_scopes(self, tree):
        """Read every function's scope before anything is lowered.

        A function that is written inside another function reads names from
        the scope around it. Those names are FREE variables of the inner
        function: they are in neither its parameters nor its locals, and the
        engine has no way to carry the taint of a value into a function that
        has no parameter to carry it in. A sink inside such a body is not a
        finding the report cannot show -- it is never produced at all.

        So the free variables become the inner function's LEADING PARAMETERS,
        which is what the Go side car does for a closure. A parameter is a
        source, so the body is analysed with the captured value already
        tainted, and a finding inside it cannot be lost.

        The scopes are read FIRST, over the whole module, because a call can
        come BEFORE the definition it calls: the call has to know the
        callee's arity, and the arity of a closure counts its captures.
        """
        self.collect_scope(tree.body, None, set(), set())

    def collect_scope(self, body, enclosing, visible, ancestors):
        """Read the scopes of the functions written in one scope.

        `visible` is the set of names the scopes around this one bind. A
        free variable of a function is a capture only when one of those
        scopes binds it: a name the MODULE binds is a global, and a global
        is not a capture -- passing it as an argument would say something
        the program does not say.

        `ancestors` are the functions this one is written inside.
        """
        for statement in body:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
                captures = sorted(self.free_variables(statement) & visible)
                self.function_captures[statement] = captures
                scope = self.bound_names(statement) | visible
                self.function_scope[statement] = scope
                self.function_enclosing[statement] = enclosing
                self.function_ancestors[statement] = set(ancestors)
                self.collect_scope(
                    statement.body, statement, scope, ancestors | {statement}
                )
            elif isinstance(statement, ast.ClassDef):
                # A class body is NOT a scope its methods can see: a method
                # reads the names of the function around it, not the
                # attributes of its class.
                self.collect_scope(statement.body, enclosing, visible, ancestors)

    def bound_names(self, node):
        """The names a function's own scope binds."""
        bound = set()
        self.walk_scope_bindings(node.body, bound, set(), False)
        for parameter in self.parameters_of(node):
            bound.add(parameter.arg)
        bound.add(node.name)
        for statement in node.body:
            if isinstance(statement, ast.ClassDef):
                bound.add(statement.name)
        return bound

    def free_variables(self, node):
        """The names a function reads that it does not bind itself.

        Where such a name comes from -- the scope around the function, or
        the module -- is decided by the scope that has it, not here.
        """
        bound = self.bound_names(node)
        globals_ = set()
        self.collect_declared_globals(node.body, globals_)
        loaded = set()
        self.collect_loaded_names(node.body, loaded)
        return loaded - bound - globals_ - BUILTIN_NAMES

    def collect_loaded_names(self, nodes, out, bound_here=frozenset()):
        """The names a scope reads.

        A nested scope is a scope of its own: the names it reads are not
        the names this scope reads.

        A decorator and a class's BASES are NOT read here. They are
        evaluated where the thing they decorate is defined, and reading them
        as names of the scope AROUND made them free variables -- which were
        then promoted to the leading parameters of the enclosing function:
        a parameter is a source, so a decorator's name became a taint
        source the program never had.
        """
        for node in nodes:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                defaults = list(node.args.defaults)
                defaults.extend(d for d in node.args.kw_defaults if d is not None)
                self.collect_loaded_names(defaults, out, bound_here)
                continue
            if isinstance(node, ast.Lambda):
                defaults = list(node.args.defaults)
                defaults.extend(d for d in node.args.kw_defaults if d is not None)
                self.collect_loaded_names(defaults, out, bound_here)
                continue
            if isinstance(node, ast.ClassDef):
                continue
            if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
                # A comprehension has a scope of its own: its loop variables
                # are bound there, and everything else it reads comes from
                # the scope around it.
                inner = set(bound_here)
                for generator in node.generators:
                    self.collect_target_names(generator.target, inner)
                for child in ast.iter_child_nodes(node):
                    if isinstance(child, ast.comprehension):
                        self.collect_loaded_names([child.iter], out, bound_here)
                        self.collect_loaded_names(child.ifs, out, inner)
                        continue
                    self.collect_loaded_names([child], out, inner)
                continue
            if isinstance(node, ast.Name):
                if isinstance(node.ctx, ast.Load) and node.id not in bound_here:
                    out.add(node.id)
                continue
            for child in ast.iter_child_nodes(node):
                self.collect_loaded_names([child], out, bound_here)

    def collect_target_names(self, target, out):
        """The names a target binds."""
        if isinstance(target, ast.Name):
            out.add(target.id)
            return
        if isinstance(target, (ast.Tuple, ast.List)):
            for element in target.elts:
                self.collect_target_names(element, out)

    def collect_declared_globals(self, nodes, out):
        """The names a scope declares global.

        A ``nonlocal`` statement says the same thing about the scope
        AROUND this one, so it is read here too: a name that was not read
        was a free variable of the function, and a free variable that was
        promoted to a parameter is a source the program never had.
        """
        for node in nodes:
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda, ast.ClassDef)):
                continue
            if isinstance(node, (ast.Global, ast.Nonlocal)):
                out.update(node.names)
                continue
            for child in ast.iter_child_nodes(node):
                self.collect_declared_globals([child], out)

    def can_pass_captures(self, definition):
        """Whether the call being lowered can pass a closure's captures.

        The captures are the names the scope around the closure binds. A
        call inside that scope can pass them; a call outside it cannot,
        because they are not in scope there.
        """
        current = self.state.ast_node if self.state is not None else None
        if current is None:
            return False
        enclosing = self.function_enclosing.get(definition)
        if enclosing is None:
            return True
        if enclosing is current:
            return True
        return enclosing in self.function_ancestors.get(current, set())

    def lower_lambda(self, node):
        """Lower a lambda's body into a function of its own.

        The TAC has no nested function and no function value, so the body's
        instructions cannot live in the enclosing function's stream. The
        body becomes a function of its own, and the value the expression
        produces is the CLOSURE OBJECT -- a container that holds the
        captured values.

        The captured values are the LEADING PARAMETERS of that function,
        the way a nested ``def``'s captures are. Two reasons, and the
        second is the one that decides it:

        * The engine binds a call's user arguments to the callee's
          parameters (user argument N lands on parameter N). A capture
          that lives only in the closure OBJECT is never bound to
          anything, so the call could not hand the captured value over
          and the body would be analysed with the capture missing.
        * A parameter is a source, so the body is analysed with the
          captured value already tainted, and a finding inside it cannot
          be lost. The capture is read out of the object in the body
          instead, and the object is the receiver -- which the engine
          does not bind to the callee -- so that reading would come back
          clean, and the finding inside the closure would disappear.

        The consequence is the one the nested ``def`` already has: a
        capture that is CLEAN is still treated as a source inside the
        closure. That is an over-approximation, and the safe direction.
        """
        outer = self.state
        captures = self.lambda_captures(node)
        self.closure_counter += 1
        name = "closure$%d" % self.closure_counter
        owner = self.module_name
        # The signature is computed while the ENCLOSING scope is still the
        # current one: the types of the captured values are the enclosing
        # scope's types, and they are what the signature names.
        signature = self.lambda_signature(node, owner, captures, name)
        state = self.begin_function(name, "", None)
        state.signature = signature
        state.ast_node = node
        state.scope_names = set(captures)
        state.shadowed_names = self.scope_shadowed_names([node.body])
        for parameter in self.parameters_of(node):
            state.shadowed_names.add(parameter.arg)
        for capture in captures:
            declared = outer.types.get(capture) if outer is not None else None
            if declared:
                state.types[capture] = declared
        try:
            # The captured values are the LEADING parameters, and the
            # declared ones follow them.
            for capture in captures:
                state.params.append(var_op(capture))
                state.parameters.append(
                    {
                        "name": capture,
                        "type": self.tac_type_of_variable(capture),
                        "optional": False,
                        "variadic": False,
                        "byRef": False,
                        "keywordOnly": False,
                        "defaultValue": None,
                    }
                )
            for parameter in self.parameters_of(node):
                state.params.append(var_op(parameter.arg))
                state.parameters.append(
                    {
                        "name": parameter.arg,
                        "type": self.tac_type_of_annotation(parameter.annotation),
                        "optional": False,
                        "variadic": parameter is node.args.vararg
                        or parameter is node.args.kwarg,
                        "byRef": False,
                        "keywordOnly": parameter in list(node.args.kwonlyargs),
                        "defaultValue": None,
                    }
                )
            value = self.lower_expression(node.body)
            self.emit({"kind": Kind.RETURN, "lhs1": value, **self.position(node)})
        finally:
            self.state = outer
        closure = self.finish_function(state)
        self.module_functions.append(closure)

        # The closure object: a container that holds the captured values.
        object_var = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": object_var, **self.position(node)})
        for capture in captures:
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": object_var,
                    "lhs2": const_op(Const.STRING, capture),
                    "dest": var_op(capture, self.tac_type_of_variable(capture)),
                    **self.position(node),
                }
            )
        self.lambda_by_node[node] = {
            "name": name,
            "captures": captures,
            "arity": len(captures) + len(self.parameters_of(node)),
            "object": object_var,
        }
        return object_var

    def lambda_captures(self, node):
        """The names a lambda reads from the scope around it.

        A lambda is a scope of its own, so the names it reads are free
        variables of it -- unless the scope around it binds them. A name
        the MODULE binds is a global, and a global is not a capture:
        passing it as an argument would say something the program does
        not say.
        """
        bound = set()
        for parameter in self.parameters_of(node):
            bound.add(parameter.arg)
        globals_ = set()
        self.collect_declared_globals([node.body], globals_)
        loaded = set()
        self.collect_loaded_names([node.body], loaded)
        free = loaded - bound - globals_ - BUILTIN_NAMES
        visible = self.state.scope_names if self.state is not None else set()
        return sorted(free & visible)

    def lambda_signature(self, node, owner, captures, name):
        """The signature of a lambda's own function: ``owner.name(types):any``.

        The captured values come FIRST: they are the leading parameters of
        the function, so the signature counts them.
        """
        types = []
        for capture in captures:
            types.append(self.state.types.get(capture) or "any")
        for parameter in self.parameters_of(node):
            types.append(self.annotation_name(parameter.annotation) or "any")
        return "%s.%s(%s):any" % (owner, name, ",".join(types))

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
        # A jump to a label that comes LATER in the stream could not say
        # what the label was when it was emitted, because the label did not
        # exist yet. It is filled in now that every label has been seen.
        for instruction in state.instructions:
            ref = instruction.get("labelRef")
            if not ref or not ref.get("name"):
                continue
            kind = state.label_kinds.get(ref["name"])
            if kind:
                ref["kind"] = kind
            scope_id = state.label_scopes.get(ref["name"])
            if scope_id is not None:
                ref["scopeId"] = scope_id
        problems = state.problems
        return {
            "name": state.name,
            "signature": state.signature,
            # The type this function returns. The engine cannot learn it from
            # the body -- a return value's type is a property of the
            # declaration, and the body only says what was returned -- so
            # without this field the return type of every function in the
            # program was unknown to the analysis.
            "returnType": self.tac_type_of_name(return_type_of_signature(state.signature)),
            # The value domain of what this function returns.
            "domain": self.tac_type_of_name(return_type_of_signature(state.signature)).get(
                "domain"
            ),
            "enclosingClass": state.enclosing_class,
            # The precision is DERIVED from the drops, not from whether the
            # function has a problem: a construct whose semantics were lost
            # is a different thing from one whose shape was lost.
            "precision": precision_of(state.drops),
            "synthetic": state.synthetic,
            # What this function IS inside its owner.
            "memberKind": state.member_kind,
            "params": state.params,
            "locals": state.locals,
            "instructions": state.instructions,
            "problems": problems,
            # What this translator could not lower, and what that cost.
            "drops": state.drops,
            # The declared parameters, with the facts a positional list of
            # variables cannot carry.
            "parameters": state.parameters,
            # The exception regions this function contains.
            "tryRegions": state.try_regions,
            # The branch regions: the match dispatches.
            "branchRegions": state.branch_regions,
            # The names this function binds in a scope that is not its own.
            "scopeBindings": state.scope_bindings,
            # The annotations: the five channels the IR has.
            "classAnnotations": state.class_annotations,
            "methodAnnotations": state.method_annotations,
            "fieldAnnotations": state.field_annotations,
            "paramAnnotations": state.param_annotations,
            "annotationValues": state.annotation_values,
            # The labels a generator resumes at.
            "generatorStates": state.generator_states,
        }

    def lower_function(self, node, enclosing_class):
        """Lower one function into its own stream."""
        if enclosing_class and node.name == "__init__":
            return self.lower_constructor(node, enclosing_class)
        outer = self.state
        owner = enclosing_class if enclosing_class else self.module_name
        # The values the function captures are its LEADING PARAMETERS. A
        # parameter is a source, so the body is analysed with the captured
        # value already tainted, and a finding inside it cannot be lost.
        captures = self.function_captures.get(node, [])
        state = self.begin_function(
            node.name, self.function_signature(node, owner, captures), enclosing_class
        )
        state.ast_node = node
        state.scope_names = self.function_scope.get(node, set())
        state.shadowed_names = self.scope_shadowed_names(node.body)
        for parameter in self.parameters_of(node):
            state.shadowed_names.add(parameter.arg)
        if self.receiver_kind(node) == "class":
            # A CLASS METHOD's first parameter IS the class: a call on it is
            # a call on the class, and the receiver slot holds the class
            # object and not an instance.
            declared = self.parameters_of(node)
            if declared:
                state.class_receiver = declared[0].arg
        for name in captures:
            declared = outer.types.get(name) if outer is not None else None
            if declared:
                state.types[name] = declared
        self.annotate(state, node, "method")
        try:
            self.lower_parameters(node, captures)
            self.report_non_constant_defaults(node)
            self.lower_body(node.body)
        finally:
            self.state = outer
        return self.finish_function(state)

    def lower_constructor(self, node, enclosing_class):
        """Lower a constructor.

        A construction names the constructor it calls as ``<Class>.<init>/n``,
        so the constructor has to be lowered under that name: a constructor
        that keeps its Python name (``__init__``) is never reached by the
        construction that calls it, and its body's findings are lost.
        """
        outer = self.state
        state = self.begin_function(
            "<init>",
            self.function_signature(node, enclosing_class, (), name="<init>"),
            enclosing_class,
        )
        state.member_kind = MemberKind.CONSTRUCTOR
        state.ast_node = node
        state.scope_names = self.function_scope.get(node, set())
        state.shadowed_names = self.scope_shadowed_names(node.body)
        for parameter in self.parameters_of(node):
            state.shadowed_names.add(parameter.arg)
        self.annotate(state, node, "method")
        try:
            self.lower_parameters(node)
            self.report_non_constant_defaults(node)
            self.lower_body(node.body)
        finally:
            self.state = outer
        return self.finish_function(state)

    def lower_parameters(self, node, captures=()):
        """Declare the parameters, in declaration order.

        The captured names come FIRST: they are the leading parameters of a
        closure, and a parameter is a source, so the body of the closure is
        analysed with the captured value already tainted.

        Each parameter also carries the facts its declaration states: whether
        it is keyword-only, whether it is variadic, and whether it has a
        default. Those facts decide how a call binds its arguments.
        """
        for name in captures:
            self.state.params.append(var_op(name, self.tac_type_of_variable(name)))
        args = node.args
        positional = list(args.posonlyargs) + list(args.args)
        keyword_only = list(args.kwonlyargs)
        defaulted = set(
            id(parameter)
            for parameter in positional[len(positional) - len(list(args.defaults)) :]
        )
        for parameter in self.parameters_of(node):
            name = parameter.arg
            operand = var_op(name, self.tac_type_of_annotation(parameter.annotation))
            self.state.params.append(operand)
            annotation = self.annotation_name(parameter.annotation)
            if annotation:
                self.state.types[name] = annotation
            variadic = parameter is args.vararg or parameter is args.kwarg
            self.state.parameters.append(
                {
                    "name": name,
                    "type": self.tac_type_of_annotation(parameter.annotation),
                    "optional": id(parameter) in defaulted
                    or parameter in keyword_only
                    or variadic,
                    "variadic": variadic,
                    "byRef": False,
                    "keywordOnly": parameter in keyword_only,
                    "defaultValue": None,
                }
            )
            self.annotate_parameter(parameter)

    def annotate_parameter(self, parameter):
        """Record the annotation ONE parameter carries."""
        annotation = parameter.annotation
        if annotation is None:
            return
        name = self.annotation_name(annotation)
        if name:
            self.state.param_annotations.append(name)

    def parameters_of(self, node):
        """A function's parameters, in declaration order.

        Every count of parameters goes through here, so that the count a
        call carries and the count the signature shows are the same count.
        """
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

        The expression is still LOWERED, so that a sink inside it is
        visible: the value is evaluated once, at the wrong time, and the
        difference is reported.
        """
        args = node.args
        positional = list(args.posonlyargs) + list(args.args)
        defaults = list(args.defaults)
        pairs = list(
            zip(positional[len(positional) - len(defaults) :], defaults)
        )
        # The keyword-only defaults are a list of their own, and they were
        # not read at all before: a default of a keyword-only parameter is
        # as much a default as any other.
        pairs.extend(zip(args.kwonlyargs, args.kw_defaults))
        for parameter, default in pairs:
            if default is None:
                continue
            if isinstance(default, ast.Constant):
                continue
            self.lower_expression(default)
            self.report(
                Problem.SOURCE_LOWERED,
                default,
                "a default value is evaluated where the function is defined, not where it is "
                "called, so it is not part of the function's own stream",
            )

    def function_signature(self, node, owner, captures=(), name=None):
        """The signature string: ``owner.name(ParamTypes):Return``."""
        types = []
        for capture in captures:
            types.append(self.state.types.get(capture) or "any")
        for parameter in self.parameters_of(node):
            types.append(self.annotation_name(parameter.annotation) or "any")
        return "%s.%s(%s):%s" % (
            owner,
            name or node.name,
            ",".join(types),
            self.annotation_name(node.returns) or "any",
        )

    def lower_class(self, node, outer_class=None):
        """The methods of a class, and the class's own body.

        The class body runs when the class is created, so it is a function
        of its own: a synthetic one, named ``<clinit>``. It is NOT the
        constructor -- the constructor is the method named ``__init__``, and
        it is lowered as ``<init>`` so that a construction can reach it.

        The class's BASES, its DECORATORS and its KEYWORDS are read here:
        they are facts about the class, and a rule that names a base class
        or a decorator has to be able to find them.

        <p>The class's IDENTITY is the module it lives in PLUS the path of
        names that leads to it. A simple name is a spelling, not an
        identity: two modules that each declare a class called ``Holder`` are
        two classes, and their members are two members. The Java front end
        states the same thing the same way -- a class's identity is its
        qualified name -- so the engine can keep two same-named classes
        apart on every language.

        <p>The CALL key still spells the class the way the source spells it
        (the class path, without the module), because the sink catalogue and
        the call graph match owners by that name.
        """
        functions = []
        class_name = node.name
        class_path = "%s.%s" % (outer_class, class_name) if outer_class else class_name
        identity = self.class_identity(class_path)
        outer = self.state
        state = self.begin_function(
            "<clinit>",
            "%s.<clinit>():any" % identity,
            identity,
            synthetic=True,
        )
        state.member_kind = MemberKind.FIELD_INITIALIZER
        state.shadowed_names = self.scope_shadowed_names(node.body)
        state.super_type = self.super_type_of(node)
        self.annotate(state, node, "class")
        try:
            # The class's own decorators run when the class is created, so
            # they belong to the class body's own stream.
            self.lower_decorators(node.decorator_list, class_name, node)
            self.guard(node, lambda: self.lower_class_body(node, functions, class_path))
            if self.is_dataclass(node) and not self.declares_init(node):
                # A dataclass gets a constructor from the decorator when it
                # does not write one itself. Without it the construction
                # reaches no body at all, and nothing the caller passes
                # reaches the instance.
                functions.append(self.lower_synthetic_constructor(node, identity))
        finally:
            self.state = outer
        functions.insert(0, self.finish_function(state))
        self.record_type_declaration(node, identity, class_name, state.super_type)
        return functions

    def class_identity(self, class_path):
        """The class's identity: the module it lives in plus its class path.

        <p>This is what the function table and the member index key on, so
        two classes of the same name in two modules stay two classes.

        @param class_path the names that lead to the class, without the module
        @return the identity, or None when there is no class
        """
        if not class_path:
            return None
        return "%s.%s" % (self.module_name, class_path)

    def class_path_of(self, identity):
        """The class path of an identity: the names without the module.

        <p>The CALL key spells the class the way the source spells it, so
        this is what a call key's owner is. The identity carries the module
        in front; the call key must not, because the call graph and the sink
        catalogue match owners by the simple name.

        @param identity the class's identity
        @return the class path, or the identity when it carries no module
        """
        if not identity:
            return identity
        prefix = "%s." % self.module_name
        if identity.startswith(prefix):
            return identity[len(prefix) :]
        return identity

    def is_dataclass(self, node):
        """Whether a class is a dataclass.

        A dataclass is a class the ``dataclass`` decorator was applied to:
        that decorator is what gives it the constructor it does not write.
        """
        for decorator in node.decorator_list or ():
            name = self.decorator_name(decorator)
            if name in ("dataclass", "dataclasses.dataclass"):
                return True
        return False

    def declares_init(self, node):
        """Whether a class body declares its own constructor."""
        for statement in node.body:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
                if statement.name == "__init__":
                    return True
        return False

    def dataclass_fields(self, node):
        """The fields a dataclass declares: their names and their defaults.

        The fields are the class body's ANNOTATED assignments, in declaration
        order: that is the order the generated constructor takes them in. An
        assignment without an annotation is not a field -- a dataclass only
        takes the annotated ones.
        """
        fields = []
        for statement in node.body:
            if not isinstance(statement, ast.AnnAssign):
                continue
            if not isinstance(statement.target, ast.Name):
                continue
            fields.append((statement.target.id, statement.annotation, statement.value))
        return fields

    def lower_synthetic_constructor(self, node, class_name):
        """The constructor a dataclass gets and does not write.

        It takes the fields as parameters and writes them into the instance,
        which is what the decorator generates. The default of a field is
        only passed on when it is a CONSTANT: a default that is computed is
        evaluated when the class is created and not when the instance is, so
        passing it on would evaluate it twice.
        """
        fields = self.dataclass_fields(node)
        arguments = [ast.arg(arg="self")]
        defaults = []
        body = []
        for name, annotation, default in fields:
            arguments.append(ast.arg(arg=name, annotation=annotation))
            if default is not None and isinstance(default, ast.Constant):
                defaults.append(default)
            elif default is not None:
                self.emit_drop(
                    "decl.dataclass.default",
                    "the default of the field %s is computed, and it is evaluated when the class "
                    "is created and not when the instance is, so the generated constructor takes "
                    "the field as a required argument" % name,
                    DropImpact.VALUE,
                    node,
                )
            body.append(
                ast.Assign(
                    targets=[
                        ast.Attribute(
                            value=ast.Name(id="self", ctx=ast.Load()),
                            attr=name,
                            ctx=ast.Store(),
                        )
                    ],
                    value=ast.Name(id=name, ctx=ast.Load()),
                    lineno=getattr(node, "lineno", 1),
                    col_offset=getattr(node, "col_offset", 0),
                )
            )
        synthetic = ast.FunctionDef(
            name="__init__",
            args=ast.arguments(
                posonlyargs=[],
                args=arguments,
                vararg=None,
                kwonlyargs=[],
                kw_defaults=[],
                kwarg=None,
                defaults=defaults,
            ),
            body=body or [ast.Pass()],
            decorator_list=[],
            returns=None,
            type_comment=None,
            lineno=getattr(node, "lineno", 1),
            col_offset=getattr(node, "col_offset", 0),
        )
        ast.fix_missing_locations(synthetic)
        return self.lower_constructor(synthetic, class_name)

    def super_type_of(self, node):
        """The class a class extends, or None."""
        for base in node.bases or ():
            name = self.annotation_name(base)
            if name:
                return name
        return None

    def record_type_declaration(self, node, identity, simple_name, super_type):
        """Record what a class declares about itself.

        The declaration is the module's TYPE surface: what the classes are,
        what they extend, what they declare. It is not the instruction
        stream, and the two are separate.
        """
        interfaces = []
        bases = list(node.bases or ())
        for base in bases[1:]:
            name = self.annotation_name(base)
            if name:
                interfaces.append(name)
        metaclass = None
        for keyword in node.keywords or ():
            if keyword.arg == "metaclass":
                metaclass = self.annotation_name(keyword.value)
        annotations = []
        for decorator in node.decorator_list or ():
            name = self.decorator_name(decorator)
            if name:
                annotations.append(name)
        type_parameters = []
        for parameter in getattr(node, "type_params", None) or ():
            name = getattr(parameter, "name", None)
            if name:
                type_parameters.append(name)
        self.type_declarations.append(
            {
                "fqn": identity,
                "simpleName": simple_name,
                "superType": super_type,
                "interfaces": interfaces,
                "annotations": annotations,
                "fields": [],
                "fieldAnnotations": [],
                "members": [],
                "traits": [],
                "modifiers": [],
                "metaclass": metaclass,
                "isInterface": False,
                "isAbstract": False,
                "isEnum": any(self.annotation_name(b) in ("Enum", "IntEnum", "StrEnum") for b in bases),
                "isRecord": any(self.annotation_name(b) == "NamedTuple" for b in bases),
                "isSynthetic": False,
                "typeParameters": type_parameters,
            }
        )

    def lower_class_body(self, node, functions, class_path):
        """Lower the class body: its attributes, and its methods.

        A method's decorators run in the class body's scope, so they are
        emitted into the class body's own stream.

        The dispatch is EXHAUSTIVE: every kind of statement a class body can
        hold is handled here, so that a statement this translator does not
        know about is a statement it says it does not know about -- and not
        one it passes on to a statement lowering that would report it as an
        unknown STATEMENT.
        """
        for statement in node.body:
            if isinstance(statement, (ast.FunctionDef, ast.AsyncFunctionDef)):
                functions.append(
                    self.lower_function_declaration(
                        statement, self.class_identity(class_path)
                    )
                )
            elif isinstance(statement, ast.ClassDef):
                functions.extend(self.lower_class(statement, class_path))
            elif isinstance(statement, ast.AnnAssign):
                # A class attribute with an annotation: ``x: int = 3``.
                self.lower_class_attribute(statement)
            elif isinstance(statement, ast.Assign):
                # A class attribute: the receiver is the CLASS object, not
                # the instance.
                self.lower_class_attribute(statement)
            elif isinstance(statement, (ast.Import, ast.ImportFrom)):
                pass
            elif isinstance(statement, (ast.Pass, ast.Expr, ast.Return)):
                self.lower_statement(statement)
            else:
                self.report(
                    Problem.UNHANDLED_NODE,
                    statement,
                    "this class member has no TAC lowering",
                )

    def lower_class_attribute(self, statement):
        """A class attribute: a member of the CLASS, not of an instance.

        The receiver is the class object, so that a read of the attribute
        finds the value the declaration wrote.
        """
        owner = self.class_path_of(self.state.enclosing_class) or self.module_name
        class_object = self.class_object_of(owner)
        targets = statement.targets if isinstance(statement, ast.Assign) else [statement.target]
        value = None
        if isinstance(statement, ast.Assign):
            value = self.lower_expression(statement.value)
        elif statement.value is not None:
            value = self.lower_expression(statement.value)
        for target in targets:
            if isinstance(target, ast.Name):
                self.emit(
                    {
                        "kind": Kind.ARRAYSTORE,
                        "lhs1": class_object,
                        "lhs2": const_op(Const.STRING, target.id),
                        "dest": value,
                        "ownerType": owner,
                        **self.position(target),
                    }
                )
                if isinstance(statement, ast.AnnAssign):
                    annotation = self.annotation_name(statement.annotation)
                    if annotation:
                        self.state.types[target.id] = annotation
            else:
                self.lower_write(target, value, statement)

    def class_object_of(self, qualified):
        """The class object of a class: the synthetic variable that holds
        it.

        A class attribute and a class constant belong to the CLASS, so
        their receiver is the class object.
        """
        name = "t_cls_%s" % (qualified or "unknown").replace(".", "_")
        variable = var_op(name, {"kind": Type.CLASS, "name": qualified or "object"})
        self.declare_local(variable)
        return variable

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
            self.lower_return(value, node)
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
            self.lower_break(node)
            return
        if isinstance(node, ast.Continue):
            self.lower_continue(node)
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
        if isinstance(node, ast.Global):
            # The names belong to the MODULE's scope, not to this one.
            for name in node.names:
                self.state.scope_bindings.append(
                    {"name": name, "kind": ScopeKind.GLOBAL, "byRef": False}
                )
            return
        if isinstance(node, ast.Nonlocal):
            # The names belong to the scope AROUND this one.
            for name in node.names:
                self.state.scope_bindings.append(
                    {"name": name, "kind": ScopeKind.CAPTURED, "byRef": False}
                )
            return
        if isinstance(node, ast.Pass):
            return
        if isinstance(node, ast.Delete):
            self.lower_delete(node)
            return
        if isinstance(node, ast.Match):
            self.lower_match(node)
            return
        type_alias = getattr(ast, "TypeAlias", None)
        if type_alias is not None and isinstance(node, type_alias):
            # A type alias has no run-time behaviour of its own: it is a
            # name for a type, and the name is what the module declares.
            name = self.alias_name(node)
            if name:
                self.state.scope_bindings.append(
                    {"name": name, "kind": ScopeKind.DECLARE, "byRef": False}
                )
            self.emit_drop(
                "type.alias",
                "a type alias has no run-time behaviour, so only its name is recorded",
                DropImpact.TYPE,
                node,
            )
            return
        try_star = getattr(ast, "TryStar", None)
        if try_star is not None and isinstance(node, try_star):
            # `except*` handles EXCEPTIONS GROUPS, and the TAC has no
            # counterpart for a group. The body is lowered all the same.
            self.lower_try(node)
            self.emit_drop(
                "exc.handler.group",
                "an except* handler catches an exception GROUP, and the TAC's catch handler "
                "catches one exception",
                DropImpact.STRUCTURE,
                node,
            )
            return
        self.report(Problem.UNHANDLED_NODE, node, "this statement has no TAC lowering")

    def lower_return(self, value, node):
        """A return.

        A return inside a ``try`` that has a ``finally`` does not leave the
        function straight away: Python runs the ``finally`` first. So the
        value is put aside, the finally is run, and the function returns
        afterwards.
        """
        pending = self.state.finally_stack[-1] if self.state.finally_stack else None
        if pending is None:
            self.emit({"kind": Kind.RETURN, "lhs1": value, **self.position(node)})
            return
        self.emit(
            {"kind": Kind.MOVE, "dest": pending["return_value"], "lhs1": value, **self.position(node)}
        )
        self.emit(
            {
                "kind": Kind.MOVE,
                "dest": pending["return_flag"],
                "lhs1": const_op(Const.INT, "1"),
                **self.position(node),
            }
        )
        self.emit_jump(pending["label"], node)

    def alias_name(self, node):
        """The name a type alias binds."""
        name = getattr(node, "name", None)
        if isinstance(name, ast.Name):
            return name.id
        return None

    def lower_break(self, node):
        """A break.

        A break leaves the innermost loop. The labels of the enclosing
        loops are kept on a stack, so that the break knows which one it
        leaves.
        """
        stack = self.state.loop_stack
        if not stack:
            self.report(Problem.UNHANDLED_NODE, node, "this break is not inside a loop")
            return
        self.emit_jump(stack[-1]["break"], node)

    def lower_continue(self, node):
        """A continue: it leaves the innermost loop too."""
        stack = self.state.loop_stack
        if not stack:
            self.report(Problem.UNHANDLED_NODE, node, "this continue is not inside a loop")
            return
        self.emit_jump(stack[-1]["continue"], node)

    def lower_delete(self, node):
        """A deletion.

        Removing a key is a STORE that says "the key is gone": the TAC has no
        other way to say it, and a store that does not say it would leave the
        key's taint in place -- which is a finding that is not there.
        """
        for target in node.targets:
            if isinstance(target, ast.Subscript):
                receiver = self.lower_expression(target.value)
                key = self.lower_key(target.slice)
                self.emit(
                    {
                        "kind": Kind.ARRAYSTORE,
                        "lhs1": receiver,
                        "lhs2": key,
                        "deleteKey": True,
                        **self.position(target),
                    }
                )
                continue
            if isinstance(target, ast.Attribute):
                receiver = self.lower_expression(target.value)
                self.emit(
                    {
                        "kind": Kind.ARRAYSTORE,
                        "lhs1": receiver,
                        "lhs2": const_op(Const.STRING, target.attr),
                        "deleteKey": True,
                        **self.position(target),
                    }
                )
                continue
            if isinstance(target, ast.Name):
                variable = var_op(target.id)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.MOVE,
                        "dest": variable,
                        "lhs1": self.emit_havoc(target),
                        **self.position(target),
                    }
                )
                self.emit_drop(
                    "expr.unset.variable",
                    "a deletion of a plain variable removes the variable's value, and the TAC has "
                    "no way to say that a variable has no value",
                    DropImpact.STRUCTURE,
                    target,
                )
                continue
            self.report(
                Problem.UNHANDLED_NODE, target, "this deletion target has no TAC lowering"
            )

    def lower_decorators(self, decorators, holder_name, node):
        """Lower a list of decorators.

        A decorator runs where the function or the class is defined, and it
        is what the name holds afterwards.

        The decorator's NAME goes into the callee key: a decorator is a
        function like any other, and a rule that names one -- a framework's
        route decorator, a task decorator -- matches on the name. A key of
        ``<python>.decorator/1`` says nothing at all.
        """
        for decorator in decorators:
            value = self.lower_expression(decorator)
            holder = var_op(holder_name)
            self.declare_local(holder)
            applied = self.state.new_temp()
            name = self.decorator_name(decorator)
            owner = self.decorator_owner(decorator)
            arity = 1
            if isinstance(decorator, ast.Call):
                arity = 1 + len(decorator.args)
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": applied,
                    "calleeSig": "%s.%s/%d" % (owner, name or "<decorator>", arity),
                    "args": [
                        const_op(Const.STRING, owner),
                        value,
                        holder,
                    ],
                    "argCount": arity,
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

    def lower_function_declaration(self, node, enclosing_class):
        """A function that is declared in the stream being lowered.

        The function itself becomes a function of its own. What stays in the
        stream is its DECORATORS: a decorator runs where the function is
        defined, and it is what the name of the function holds afterwards.
        """
        self.lower_decorators(node.decorator_list, node.name, node)
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
        approximation = APPROXIMATED_OPERATORS.get(type(node.op))
        if approximation is not None:
            # The operator is one the TAC does not have, so it is modelled
            # as the closest thing the TAC has -- and the difference is
            # reported, because it IS a difference.
            operator, note = approximation
            self.report(
                Problem.SOURCE_LOWERED,
                node,
                note,
                DropImpact.VALUE if operator is not None else DropImpact.SEMANTICS,
            )
        if operator is None:
            self.report(Problem.UNHANDLED_NODE, node, "this operator has no TAC lowering")
            return self.emit_havoc(node)
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
            variable = var_op(self.comp_local(target.id), self.tac_type_of_variable(target.id))
            self.declare_local(variable)
            # A variable that holds a closure: the call of that variable
            # has to reach the closure's body, so the closure is recorded
            # against the variable.
            if isinstance(node, ast.Assign) and isinstance(node.value, ast.Lambda):
                closure = self.lambda_by_node.get(node.value)
                if closure is not None:
                    self.state.lambda_by_var[target.id] = closure
            else:
                # The variable holds something else now, so a call of it is
                # a call of that value and not of the closure.
                self.state.lambda_by_var.pop(target.id, None)
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
            # The owner of an attribute is the class the receiver is an
            # instance of: two classes may declare an attribute of the same
            # name, and the engine's per-class field table tells them apart.
            owner = self.type_of_receiver(target.value)
            self.emit(
                {
                    "kind": Kind.ARRAYSTORE,
                    "lhs1": receiver,
                    "lhs2": key,
                    "dest": value,
                    **({"ownerType": owner} if owner else {}),
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

        A SLICE has three parts -- the lower bound, the upper bound and the
        step -- and each of them is evaluated on its own. The whole slice
        used to be an unknown value, which lost every sink in a bound.
        """
        if isinstance(node, ast.Constant):
            return self.lower_expression(node)
        if isinstance(node, ast.Slice):
            parts = []
            for part in (node.lower, node.upper, node.step):
                if part is None:
                    parts.append(const_op(Const.NULL))
                else:
                    parts.append(self.lower_expression(part))
            dest = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
            self.emit({"kind": Kind.NEWARRAY, "dest": dest, **self.position(node)})
            for index, part in enumerate(parts):
                self.emit(
                    {
                        "kind": Kind.ARRAYSTORE,
                        "lhs1": dest,
                        "lhs2": const_op(Const.INT, str(index)),
                        "dest": part,
                        **self.position(node),
                    }
                )
            return dest
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
        self.state.new_scope()
        condition = self.lower_condition(node.test)
        then_label = self.state.new_label("L")
        else_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit_branch(
            condition["operand"], then_label, else_label, condition["guard"], node
        )
        self.emit_label(then_label, LabelKind.BLOCK, node)
        self.lower_body(node.body)
        self.emit_jump(end_label, node)
        self.emit_label(else_label, LabelKind.BLOCK, node)
        if node.orelse:
            self.lower_body(node.orelse)
        self.emit_label(end_label, LabelKind.BLOCK, node)

    def lower_while(self, node):
        """A while loop.

        The loop's ``else`` runs when the loop finished WITHOUT a break, so
        the break's target has to SKIP it. That is why the loop's end and
        the loop's BODY end are two different labels: the break goes to the
        body end, and the body end jumps over the ``else`` to the end.
        """
        self.state.new_scope()
        condition_label = self.state.new_label("L")
        body_label = self.state.new_label("L")
        body_end_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit_jump(condition_label, node)
        self.emit_label(condition_label, LabelKind.LOOP_HEAD, node)
        condition = self.lower_condition(node.test)
        self.emit_branch(
            condition["operand"], body_label, end_label, condition["guard"], node
        )
        self.emit_label(body_label, LabelKind.BLOCK, node)
        outer_break, outer_continue = self.state.break_label, self.state.continue_label
        self.state.break_label = body_end_label
        self.state.continue_label = condition_label
        self.state.loop_stack.append(
            {"kind": "loop", "break": body_end_label, "continue": condition_label}
        )
        self.lower_body(node.body)
        self.state.loop_stack.pop()
        self.state.break_label, self.state.continue_label = outer_break, outer_continue
        self.emit_jump(condition_label, node)
        self.emit_label(body_end_label, LabelKind.LOOP_BODY_END, node)
        self.emit_jump(end_label, node)
        self.emit_label(end_label, LabelKind.LOOP_END, node)
        if node.orelse:
            self.lower_body(node.orelse)

    def lower_for(self, node):
        """A for loop.

        The loop reads the iterable by index. The length is a call to the
        language's own ``len``, under a synthetic owner: the TAC has no
        length, and a stand-in says so.

        Python iterates an iterable through ``__iter__`` and ``__next__``.
        When the iterable's type is not known, those calls are made, so
        that the protocol is visible to a rule that names it; when the type
        IS known, the element is read by index, which is what the protocol
        would have produced.

        The element of the protocol branch is the RESULT of ``__next__``.
        Python's ``__next__`` returns the element itself -- it is not a
        pair of a value and a done flag, the way JavaScript's iterator
        result is -- so there is nothing to take apart, and the loop
        variable IS the call's result. Reading a ``["value"]`` out of it
        would be reading a member of the element, which is a different
        thing entirely.

        The loop's ``else`` runs when the loop finished WITHOUT a break, so
        the break's target has to skip it.
        """
        self.state.new_scope()
        iterable = self.lower_expression(node.iter)
        iterable_type = self.type_of_expression(node.iter)
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
        protocol_owner = iterable_type or UNKNOWN_OWNER
        iterator = None
        if iterable_type is None:
            # The type is not known, so the protocol is modelled: it is what
            # Python actually does, and it is the only shape that is right
            # for a generator or an iterator.
            iterator = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": iterator,
                    "calleeSig": "%s.__iter__/0" % protocol_owner,
                    "args": [iterable],
                    "argCount": 0,
                    **self.position(node),
                }
            )
            self.emit_drop(
                "ctrl.iter.protocol",
                "the iterable's type is not known, so the loop is modelled through the "
                "__iter__ and __next__ protocol, and its bound is the iterable's length",
                DropImpact.STRUCTURE,
                node,
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
        body_end_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        self.emit_jump(condition_label, node)
        self.emit_label(condition_label, LabelKind.LOOP_HEAD, node)
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
        self.emit_branch(
            comparison,
            body_label,
            end_label,
            {"kind": Guard.COMPARE, "lhs": index, "op": "LT", "rhs": length},
            node,
        )
        self.emit_label(body_label, LabelKind.BLOCK, node)
        element = self.state.new_temp()
        if iterator is not None:
            # The element IS the result of __next__: Python's __next__ returns
            # the element, not a pair of a value and a done flag.
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": element,
                    "calleeSig": "%s.__next__/0" % protocol_owner,
                    "args": [iterator],
                    "argCount": 0,
                    **self.position(node),
                }
            )
        else:
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
        self.state.break_label = body_end_label
        self.state.continue_label = body_end_label
        self.state.loop_stack.append(
            {"kind": "loop", "break": body_end_label, "continue": body_end_label}
        )
        self.lower_body(node.body)
        self.state.loop_stack.pop()
        self.state.break_label, self.state.continue_label = outer_break, outer_continue
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
        self.emit_jump(condition_label, node)
        self.emit_label(body_end_label, LabelKind.LOOP_BODY_END, node)
        self.emit_jump(end_label, node)
        self.emit_label(end_label, LabelKind.LOOP_END, node)
        if node.orelse:
            self.lower_body(node.orelse)

    def lower_with(self, node):
        """A with statement.

        The target is bound to the value ``__enter__`` RETURNS, and not to
        the context manager itself: ``with open(f) as fh`` binds the file
        object, and the file object is what ``open(f).__enter__()`` gives
        back.

        ``__exit__`` is called when the block ends, whatever happened in it.
        """
        resource_vars = []
        context_values = []
        for item in node.items:
            value = self.lower_expression(item.context_expr)
            context_values.append(value)
            owner = self.type_of_expression(item.context_expr) or UNKNOWN_OWNER
            entered = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": entered,
                    "calleeSig": "%s.__enter__/0" % owner,
                    "args": [value],
                    "argCount": 0,
                    **self.position(node),
                }
            )
            if item.optional_vars is None:
                continue
            if isinstance(item.optional_vars, ast.Name):
                variable = var_op(item.optional_vars.id)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.MOVE,
                        "dest": variable,
                        "lhs1": entered,
                        **self.position(node),
                    }
                )
                resource_vars.append(item.optional_vars.id)
            else:
                self.lower_write(item.optional_vars, entered, node)
        self.lower_body(node.body)
        for value, owner in zip(
            context_values,
            [
                self.type_of_expression(item.context_expr) or UNKNOWN_OWNER
                for item in node.items
            ],
        ):
            self.emit(
                {
                    "kind": Kind.CALL,
                    "dest": self.state.new_temp(),
                    "calleeSig": "%s.__exit__/2" % owner,
                    "args": [value, const_op(Const.NULL), const_op(Const.BOOL, "false")],
                    "argCount": 2,
                    **self.position(node),
                }
            )
        if resource_vars:
            self.state.try_regions.append(
                {
                    "tryStart": -1,
                    "tryEnd": -1,
                    "catchStart": -1,
                    "catchEnd": -1,
                    "catchLabels": [],
                    "catchTypes": [],
                    "finallyLabel": None,
                    "finallyStart": -1,
                    "finallyEnd": -1,
                    # A `with` is a try/finally the COMPILER makes: there is
                    # no `finally` in the source, and there is one in the
                    # run-time.
                    "syntheticFinally": True,
                    "finallyExits": [],
                    "resourceVars": resource_vars,
                }
            )

    def lower_match(self, node):
        """A match statement.

        Python picks the FIRST case whose pattern matches, and a case with
        a guard matches only when the guard holds. So the cases become a
        chain of tests -- and every case's BODY is lowered: a body that is
        not lowered is a body whose findings are not there.
        """
        self.state.new_scope()
        subject = self.lower_expression(node.subject)
        cases = node.cases or []
        end_label = self.state.new_label("L")
        no_match_label = self.state.new_label("L")
        body_labels = [self.state.new_label("L") for _ in cases]
        region = {
            "id": "R%d" % (len(self.state.branch_regions) + 1),
            "kind": "MATCH",
            "subject": subject,
            "cases": [],
            "defaultLabel": None,
            "fallThrough": False,
        }

        for index, case in enumerate(cases):
            test_label = self.state.new_label("L")
            next_label = (
                self.state.new_label("L") if index + 1 < len(cases) else no_match_label
            )
            self.emit_label(test_label, LabelKind.SWITCH_CASE, case)
            operand, guard = self.match_pattern_test(case.pattern, subject)
            self.emit_branch(operand, body_labels[index], next_label, guard, case)
            region["cases"].append(
                {
                    "id": "C%d" % (index + 1),
                    "guards": [guard],
                    "bodyLabel": body_labels[index],
                    "nextLabel": next_label,
                }
            )
            if case.guard is not None:
                # A case with a guard matches only when the guard holds.
                guard_operand = self.lower_expression(case.guard)
                self.emit_branch(
                    guard_operand,
                    body_labels[index],
                    next_label,
                    {"kind": Guard.TRUTHINESS, "operand": guard_operand, "sense": "TRUE"},
                    case,
                )
            self.emit_label(next_label, LabelKind.SWITCH_CASE, case)

        # No case matched: Python raises. The TAC has a THROW, so the path
        # ends there rather than carrying on with an unknown value.
        self.emit_label(no_match_label, LabelKind.BLOCK, node)
        self.emit(
            {
                "kind": Kind.THROW,
                "lhs1": const_op(Const.STRING, "MatchError"),
                "ctorType": {"kind": Type.CLASS, "name": "MatchError"},
                **self.position(node),
            }
        )

        for index, case in enumerate(cases):
            self.emit_label(body_labels[index], LabelKind.BLOCK, case)
            self.match_pattern_bindings(case.pattern, subject)
            self.lower_body(case.body)
            self.emit_jump(end_label, case)
        self.emit_label(end_label, LabelKind.BLOCK, node)
        self.state.branch_regions.append(region)

    def match_pattern_test(self, pattern, subject):
        """The test a match pattern is.

        A literal pattern is an equality test; a class pattern is a type
        test; and a pattern that binds a name matches anything.
        """
        if isinstance(pattern, ast.MatchValue):
            value = self.lower_expression(pattern.value)
            dest = self.state.new_temp(Type.BOOLEAN)
            self.emit(
                {
                    "kind": Kind.BINOP,
                    "dest": dest,
                    "lhs1": subject,
                    "lhs2": value,
                    "binop": "EQ",
                    **self.position(pattern),
                }
            )
            return dest, {"kind": Guard.COMPARE, "lhs": subject, "op": "EQ", "rhs": value}
        if isinstance(pattern, ast.MatchClass):
            type_name = self.annotation_name(pattern.cls) or "object"
            dest = self.state.new_temp(Type.BOOLEAN)
            self.emit(
                {
                    "kind": Kind.INSTANCEOF,
                    "dest": dest,
                    "lhs1": subject,
                    "ctorType": {"kind": Type.CLASS, "name": type_name},
                    **self.position(pattern),
                }
            )
            return dest, {
                "kind": Guard.TYPE_TEST,
                "operand": subject,
                "type": {"kind": Type.CLASS, "name": type_name},
                "negated": False,
            }
        if isinstance(pattern, ast.MatchAs):
            if pattern.pattern is not None:
                return self.match_pattern_test(pattern.pattern, subject)
            return (
                subject,
                {"kind": Guard.TRUTHINESS, "operand": subject, "sense": "TRUE"},
            )
        if isinstance(pattern, ast.MatchStar):
            return (
                subject,
                {"kind": Guard.TRUTHINESS, "operand": subject, "sense": "TRUE"},
            )
        # A mapping or a sequence pattern: what it matches is not
        # statically known, so the test is the subject's own truth.
        return (
            subject,
            {"kind": Guard.TRUTHINESS, "operand": subject, "sense": "TRUE"},
        )

    def match_pattern_bindings(self, pattern, subject):
        """Bind the names a match pattern captures."""
        if isinstance(pattern, ast.MatchAs):
            if pattern.pattern is not None:
                self.match_pattern_bindings(pattern.pattern, subject)
            elif pattern.name:
                variable = var_op(pattern.name)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.MOVE,
                        "dest": variable,
                        "lhs1": subject,
                        **self.position(pattern),
                    }
                )
            return
        if isinstance(pattern, ast.MatchStar):
            if pattern.name:
                variable = var_op(pattern.name)
                self.declare_local(variable)
                self.emit(
                    {
                        "kind": Kind.MOVE,
                        "dest": variable,
                        "lhs1": subject,
                        **self.position(pattern),
                    }
                )
            return
        if isinstance(pattern, ast.MatchClass):
            for sub in pattern.patterns or ():
                self.match_pattern_bindings(sub, subject)
            return
        if isinstance(pattern, ast.MatchSequence):
            for sub in pattern.patterns or ():
                self.match_pattern_bindings(sub, subject)
            return
        if isinstance(pattern, ast.MatchMapping):
            for sub in pattern.patterns or ():
                self.match_pattern_bindings(sub, subject)

    def lower_try(self, node):
        """A try / except / else / finally.

        The IR has explicit instructions for this: a CATCH_HANDLER binds
        the exception to the handler's name, and a FINALLY_ENTER marks the
        block every exit of the region runs through.

        The ``finally`` block is on the path of EVERY exit: the try body's
        normal end, each handler's normal end, and a ``return`` out of the
        region. A ``return`` is the awkward one: it leaves the function, and
        Python still runs the ``finally`` first. So the value is put aside,
        a flag says that there is one, and the function returns after the
        ``finally`` has run.
        """
        self.state.new_scope()
        has_finally = bool(node.finalbody)
        else_label = self.state.new_label("L")
        after_label = self.state.new_label("L")
        end_label = self.state.new_label("L")
        finally_label = self.state.new_label("L_FINALLY") if has_finally else None

        try_start = len(self.state.instructions)
        self.lower_body(node.body)
        try_end = len(self.state.instructions) - 1
        self.emit_jump(else_label, node)

        catch_labels = []
        catch_types = []
        catch_start = -1
        catch_end = -1
        for handler in node.handlers:
            types = self.exception_type_names(handler.type)
            type_name = types[0]
            label = self.state.new_label("L_CATCH_%s" % type_name)
            if catch_start < 0:
                catch_start = len(self.state.instructions)
            self.emit_label(label, LabelKind.CATCH_ENTRY, handler)
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
                self.emit({"kind": Kind.CATCH_HANDLER, **self.position(handler)})
            self.lower_body(handler.body)
            catch_end = len(self.state.instructions) - 1
            catch_labels.append(label)
            catch_types.append(types)
            self.emit_jump(finally_label if has_finally else after_label, handler)

        self.emit_label(after_label, LabelKind.BLOCK, node)
        self.emit_jump(end_label, node)
        self.emit_label(else_label, LabelKind.BLOCK, node)
        if node.orelse:
            self.lower_body(node.orelse)
            self.emit_jump(finally_label if has_finally else end_label, node)

        finally_start = -1
        finally_end = -1
        if has_finally:
            return_flag = self.state.new_temp(Type.INT)
            return_value = self.state.new_temp()
            self.emit(
                {
                    "kind": Kind.MOVE,
                    "dest": return_flag,
                    "lhs1": const_op(Const.INT, "0"),
                    **self.position(node),
                }
            )
            self.state.finally_stack.append(
                {"label": finally_label, "return_flag": return_flag, "return_value": return_value}
            )
            finally_start = len(self.state.instructions)
            self.emit_label(finally_label, LabelKind.FINALLY_ENTRY, node)
            self.emit(
                {
                    "kind": Kind.FINALLY_ENTER,
                    "label": finally_label,
                    "labelRef": self.label_ref_of(finally_label),
                    **self.position(node),
                }
            )
            self.lower_body(node.finalbody)
            finally_end = len(self.state.instructions) - 1
            self.state.finally_stack.pop()
            finally_done = self.state.new_label("L")
            self.emit_jump(finally_done, node)
            self.emit_label(finally_done, LabelKind.BLOCK, node)
            return_label = self.state.new_label("L")
            self.emit_branch(
                return_flag,
                return_label,
                end_label,
                {
                    "kind": Guard.COMPARE,
                    "lhs": return_flag,
                    "op": "NE",
                    "rhs": const_op(Const.INT, "0"),
                },
                node,
            )
            self.emit_label(return_label, LabelKind.BLOCK, node)
            self.emit({"kind": Kind.RETURN, "lhs1": return_value, **self.position(node)})
        self.emit_label(end_label, LabelKind.BLOCK, node)

        self.state.try_regions.append(
            {
                "tryStart": try_start,
                "tryEnd": try_end,
                "catchStart": catch_start,
                "catchEnd": catch_end,
                "catchLabels": catch_labels,
                "catchTypes": catch_types,
                "finallyLabel": finally_label,
                "finallyStart": finally_start,
                "finallyEnd": finally_end,
                "syntheticFinally": False,
                "finallyExits": ([finally_label] + catch_labels) if has_finally else [],
                "resourceVars": [],
            }
        )

    def exception_type_names(self, node):
        """The exception types a handler catches.

        A handler that catches several types names them in a tuple, and the
        TAC's catch handler carries ONE type: the first one is the one the
        handler binds, and the others are recorded beside it.
        """
        if node is None:
            return ["Exception"]
        if isinstance(node, ast.Tuple) and node.elts:
            names = []
            for element in node.elts:
                names.append(self.annotation_name(element) or "Exception")
            if len(names) > 1:
                self.emit_drop(
                    "exc.catch.multiType",
                    "the handler catches %s, and the TAC's catch handler carries one type, so "
                    "only the first one is bound" % " and ".join(names),
                    DropImpact.STRUCTURE,
                    node,
                )
            return names
        return [self.annotation_name(node) or "Exception"]

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
            return var_op(self.comp_local(node.id), self.tac_type_of_variable(node.id))
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
            # The lambda's BODY becomes a function of its own, so a sink
            # inside it is analysed. The VALUE it produces is the closure
            # OBJECT -- a container that holds the captured values.
            return self.lower_lambda(node)
        if isinstance(node, ast.Await):
            # The awaited value's result is what the expression produces.
            # The TAC has no coroutine, so the awaited value stands for it.
            return self.lower_expression(node.value)
        if isinstance(node, (ast.Yield, ast.YieldFrom)):
            # The value a generator yields is evaluated in the generator's
            # own stream, so it is lowered there: a sink inside it is a sink
            # inside the generator. The value the generator produces is
            # unknown -- the TAC has no generator.
            if node.value is not None:
                self.lower_expression(node.value)
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
            return self.lower_formatted_value(node)
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

        The right operand is only evaluated when the left one says it has
        to be, so it cannot be a single BINOP: it has to be on the branch
        that reaches it. That is also what makes the taint of the right
        operand reach the result only when it is evaluated.
        """
        is_and = isinstance(node.op, ast.And)
        dest = self.state.new_temp()
        end_label = self.state.new_label("L")
        rhs_label = self.state.new_label("L")
        values = list(node.values)
        left = self.lower_expression(values[0])
        self.emit({"kind": Kind.MOVE, "dest": dest, "lhs1": left, **self.position(node)})
        self.emit_branch(
            left,
            rhs_label if is_and else end_label,
            end_label if is_and else rhs_label,
            {"kind": Guard.TRUTHINESS, "operand": left, "sense": "TRUE"},
            node,
        )
        self.emit_label(rhs_label, LabelKind.BLOCK, node)
        for value in values[1:]:
            operand = self.lower_expression(value)
            self.emit({"kind": Kind.MOVE, "dest": dest, "lhs1": operand, **self.position(node)})
        self.emit_jump(end_label, node)
        self.emit_label(end_label, LabelKind.BLOCK, node)
        return dest

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

        A comprehension is a LOOP: the elements are produced one at a time,
        and a filter throws some of them away. So it is lowered as one --
        and the FILTER is lowered too: a filter that is not lowered is a
        sink that is not there.

        The loop variable lives in the COMPREHENSION's own scope. Python
        says so, and a leak into the enclosing scope is a fact about the
        program that is not true.
        """
        result = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": result, **self.position(node)})
        self.comp_scope_stack.append({})
        try:
            self.lower_generators(
                node.generators or (),
                0,
                result,
                lambda: self.lower_comprehension_value(node, result),
            )
        finally:
            self.comp_scope_stack.pop()
        return result

    def lower_comprehension_value(self, node, result):
        """The element a comprehension produces."""
        value = self.lower_expression(node.elt)
        self.emit(
            {
                "kind": Kind.ARRAYSTORE,
                "lhs1": result,
                "lhs2": const_op(Const.INT, "0"),
                "dest": value,
                **self.position(node),
            }
        )

    def lower_dict_comprehension(self, node):
        """A dict comprehension: a loop that produces entries."""
        result = self.state.new_temp({"kind": Type.ARRAY, "component": {"kind": Type.UNKNOWN}})
        self.emit({"kind": Kind.NEWARRAY, "dest": result, **self.position(node)})
        self.comp_scope_stack.append({})
        try:
            self.lower_generators(
                node.generators or (),
                0,
                result,
                lambda: self.lower_dict_comprehension_value(node, result),
            )
        finally:
            self.comp_scope_stack.pop()
        return result

    def lower_dict_comprehension_value(self, node, result):
        """The entry a dict comprehension produces."""
        key = self.lower_expression(node.key)
        value = self.lower_expression(node.value)
        self.emit(
            {
                "kind": Kind.ARRAYSTORE,
                "lhs1": result,
                "lhs2": key,
                "dest": value,
                **self.position(node),
            }
        )

    def lower_generators(self, generators, position, result, produce):
        """Lower the generators of a comprehension as nested loops."""
        if position >= len(generators):
            produce()
            return
        generator = generators[position]
        iterable = self.lower_expression(generator.iter)
        length = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.CALL,
                "dest": length,
                "calleeSig": synthetic_signature("len", 1),
                "args": [const_op(Const.STRING, SYNTHETIC_OWNER), iterable],
                **self.position(generator),
            }
        )
        index = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.MOVE,
                "dest": index,
                "lhs1": const_op(Const.INT, "0"),
                **self.position(generator),
            }
        )
        head = self.state.new_label("L")
        body = self.state.new_label("L")
        increment = self.state.new_label("L")
        end = self.state.new_label("L")
        self.emit_jump(head, generator)
        self.emit_label(head, LabelKind.LOOP_HEAD, generator)
        comparison = self.state.new_temp(Type.BOOLEAN)
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": comparison,
                "lhs1": index,
                "lhs2": length,
                "binop": "LT",
                **self.position(generator),
            }
        )
        self.emit_branch(
            comparison,
            body,
            end,
            {"kind": Guard.COMPARE, "lhs": index, "op": "LT", "rhs": length},
            generator,
        )
        self.emit_label(body, LabelKind.BLOCK, generator)
        element = self.state.new_temp()
        self.emit(
            {
                "kind": Kind.ARRAYLOAD,
                "dest": element,
                "lhs1": iterable,
                "lhs2": index,
                **self.position(generator),
            }
        )
        self.bind_comprehension_target(generator.target)
        self.lower_write(generator.target, element, generator)
        # The FILTERS: an element that fails one is not produced, and the
        # expression that decides it has to be lowered.
        next_label = self.state.new_label("L")
        for condition in generator.ifs or ():
            test = self.lower_expression(condition)
            self.emit_branch(
                test,
                next_label,
                increment,
                {"kind": Guard.TRUTHINESS, "operand": test, "sense": "TRUE"},
                generator,
            )
        self.emit_label(next_label, LabelKind.BLOCK, generator)
        self.lower_generators(generators, position + 1, result, produce)
        self.emit_label(increment, LabelKind.LOOP_BODY_END, generator)
        incremented = self.state.new_temp(Type.INT)
        self.emit(
            {
                "kind": Kind.BINOP,
                "dest": incremented,
                "lhs1": index,
                "lhs2": const_op(Const.INT, "1"),
                "binop": "ADD",
                **self.position(generator),
            }
        )
        self.emit(
            {
                "kind": Kind.MOVE,
                "dest": index,
                "lhs1": incremented,
                **self.position(generator),
            }
        )
        self.emit_jump(head, generator)
        self.emit_label(end, LabelKind.LOOP_END, generator)

    def bind_comprehension_target(self, target):
        """Give a comprehension's loop variable its own local.

        Python gives a comprehension a scope of its own, and its loop
        variable does not leak into the scope around it. The TAC's locals
        are one name each, so the comprehension's variable gets a name of
        its own.
        """
        if not self.comp_scope_stack:
            return
        scope = self.comp_scope_stack[-1]
        if isinstance(target, ast.Name):
            self.comp_counter += 1
            scope[target.id] = "t_comp%d_%s" % (self.comp_counter, target.id)
            return
        if isinstance(target, (ast.Tuple, ast.List)):
            for element in target.elts:
                self.bind_comprehension_target(element)

    def comp_local(self, name):
        """The local a name lives in, when a comprehension owns it."""
        for scope in reversed(self.comp_scope_stack):
            if name in scope:
                return scope[name]
        return name

    def lower_joined_string(self, node):
        """An f-string.

        The string is the concatenation of its parts, and the taint of a
        hole reaches the string. A string with ONE hole is still a string:
        the value of the hole is not the string, and returning it would
        give the caller a number where the source had a text.
        """
        parts = []
        for value_node in node.values:
            if isinstance(value_node, ast.Constant):
                parts.append(const_op(Const.STRING, value_node.value))
            else:
                parts.append(self.lower_expression(value_node))
        if not parts:
            return const_op(Const.STRING, "")
        if len(parts) == 1:
            dest = self.state.new_temp(Type.STRING)
            self.emit(
                {
                    "kind": Kind.UNARYOP,
                    "dest": dest,
                    "lhs1": parts[0],
                    "unop": "CAST",
                    "castType": Type.STRING,
                    **self.position(node),
                }
            )
            return dest
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

    def lower_formatted_value(self, node):
        """One hole of an f-string.

        The FORMAT SPECIFICATION is part of the expression: ``f"{x:{w}}"``
        evaluates ``w``, and dropping it loses whatever ``w`` reached. An
        explicit conversion (``!r`` or ``!s``) is a cast.
        """
        value = self.lower_expression(node.value)
        if node.format_spec is not None:
            self.lower_expression(node.format_spec)
        conversion = getattr(node, "conversion", -1)
        if conversion in (114, 115):
            # ``!r`` (repr) and ``!s`` (str) are explicit conversions.
            dest = self.state.new_temp(Type.STRING)
            self.emit(
                {
                    "kind": Kind.UNARYOP,
                    "dest": dest,
                    "lhs1": value,
                    "unop": "CAST",
                    "castType": Type.STRING,
                    **self.position(node),
                }
            )
            return dest
        return value

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
        leading = []
        for name in callee.get("leading") or []:
            leading.append(var_op(name, self.tac_type_of_variable(name)))
        args = [callee["receiver"]] + leading + arguments
        dest = self.state.new_temp(self.tac_type_of_name(self.return_type_of_callee(callee)))
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

    def return_type_of_callee(self, callee):
        """The type the callee returns, when the source says so.

        <p>Three sources, in the order they are trusted:
        <ul>
          <li>a BUILT-IN function: the language says what it returns;
          <li>a function of this module: the ``->`` annotation it declares;
          <li>a method of one of this module's classes: the ``->`` annotation
              it declares.
        </ul>
        <p>Anything else -- an imported function, a method of a class from
        another module -- has no return type the analysis can see here, and
        the call's destination stays untyped.
        """
        owner = callee.get("owner")
        method = callee.get("method")
        if owner == "builtins":
            return BUILTIN_RETURN_TYPES.get(method)
        if owner == self.module_name:
            definition = self.defined_functions.get(method)
            if definition is not None:
                return self.annotation_name(definition.returns)
        node = self.class_methods.get((owner, method))
        if node is not None:
            return self.annotation_name(node.returns)
        return None

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
            # A CLASS METHOD's `cls` IS the class, so a call on it constructs
            # one and not a method.
            state = self.state
            if (
                state is not None
                and state.class_receiver == name
                and state.enclosing_class
            ):
                return self.class_path_of(state.enclosing_class)
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

        <p>The key is ``<Class>.<init>/<arity>``. The ``<init>`` part is
        what the JVM, TypeScript, ArkTS, C# and PHP front ends all use for
        a construction, so a catalogue entry written for one of them can
        match a Python construction too -- the catalogue has no Python
        constructor entries yet, and this is the shape they will have to
        use when it gets them.

        <p>The owner is the class itself: the constructor is lowered as
        ``<Class>.<init>``, so this is the key that reaches the
        constructor's body.

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
                # The key names the CONSTRUCTOR: the constructor is lowered
                # as `<Class>.<init>`, so this is the name that reaches it.
                "calleeSig": "%s.<init>/%d" % (class_name, self.constructor_arity(class_name, node)),
                "ctorType": self.tac_type_of_name(class_name),
                "args": arguments,
                "argCount": self.passed_arity(node),
                **self.position(node),
            }
        )
        return dest

    def constructor_arity(self, class_name, node):
        """The arity of a construction.

        The constructor's DECLARED parameters when the module declares the
        constructor, and the number of arguments passed otherwise.
        """
        definition = self.class_methods.get((class_name, "__init__"))
        if definition is not None:
            return self.declared_arity(definition)
        return self.passed_arity(node)

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
        closure = self.state.lambda_by_var.get(name) if self.state is not None else None
        if closure is not None:
            # The variable holds a closure of THIS function, so the values it
            # captured are in scope here and the call passes them. The
            # closure OBJECT is the receiver: it is the value the name holds.
            return {
                "owner": self.module_name,
                "method": closure["name"],
                "receiver": closure.get("object") or const_op(Const.STRING, self.module_name),
                "arity": closure["arity"],
                "resolved": True,
                "reason": None,
                "leading": closure["captures"],
            }
        definition = self.defined_functions.get(name)
        if definition is not None:
            captures = self.function_captures.get(definition, [])
            if captures and not self.can_pass_captures(definition):
                # The closure is called from outside the scope that made
                # it, so the values it captured are not here to pass. The
                # call becomes a black box: the engine keeps the taint of
                # the arguments, and the body of the closure is analysed
                # on its own.
                return {
                    "owner": UNKNOWN_OWNER,
                    "method": name,
                    "receiver": const_op(Const.STRING, UNKNOWN_OWNER),
                    "arity": self.passed_arity(node),
                    "resolved": False,
                    "reason": (
                        "this closure is called from outside the scope that created it, so the "
                        "values it captured cannot be passed to it"
                    ),
                }
            return {
                "owner": self.module_name,
                "method": name,
                "receiver": const_op(Const.STRING, self.module_name),
                "arity": len(captures) + self.declared_arity(definition),
                "resolved": True,
                "reason": None,
                "leading": captures,
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
                "arity": self.arity_of_name(owner, method, node),
                "resolved": True,
                "reason": None,
            }
        # A call on a CLASS of this module: the owner is that class, and the
        # receiver is the class object. A class is not an instance, so the
        # receiver slot must not hold one -- and an owner that is known must
        # not be a placeholder.
        class_name = self.class_of_receiver(function.value)
        if class_name is not None:
            return {
                "owner": class_name,
                "method": method,
                "receiver": self.class_object_of(class_name),
                "arity": self.arity_of_name(class_name, method, node),
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
            "arity": self.arity_of_name(receiver_type, method, node),
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

    def class_of_receiver(self, node):
        """The class a receiver names, when it names one.

        <p>A receiver names a class when it is a class this module declares,
        or when it is the CLASS parameter of the class method it is written
        in. A class is not an instance, so the receiver slot of a call on it
        holds the class object.
        """
        if not isinstance(node, ast.Name):
            return None
        name = node.id
        state = self.state
        if state is not None and state.class_receiver == name and state.enclosing_class:
            return self.class_path_of(state.enclosing_class)
        if name in self.defined_classes:
            if state is not None and name in state.shadowed_names:
                # The scope binds the name to a value of its own, so the name
                # does not mean the class here.
                return None
            return name
        return None

    def arity_of_name(self, owner, method, node):
        """The arity of a call.

        The arity counts the DECLARED parameters of a function the module
        declares, because that is the signature the sink catalogue matches
        on. A function from elsewhere has no declared parameters the
        analysis can see, so the arity is the number of positional
        arguments the call passes.

        The declaration is looked up by the CLASS that declares the method
        and the method's NAME: two classes may declare a method of the
        same name, and the arity of one is not the arity of the other. The
        name alone is the fallback, and the difference is recorded.
        """
        if owner is not None:
            definition = self.class_methods.get((owner, method))
            if definition is not None:
                return self.declared_arity(definition)
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

        A STATIC method has no receiver at all, so its parameters are all
        the user's: ``@staticmethod def s(a)`` is ``K.stat/1``. Counting the
        receiver there made every call of a static method unresolvable.
        """
        count = len(self.parameters_of(definition))
        if self.has_receiver(definition):
            count -= 1
        return max(count, 0)

    # ---- helpers --------------------------------------------------------------------------

    def label_ref(self, name, kind, scope_id):
        """A label reference: what a label IS, and how deep it is.

        The engine tells a loop's end from a loop's body end by their kind,
        and a jump is checked against its target by their scope.
        """
        return {"name": name, "kind": kind, "scopeId": scope_id}

    def label_ref_of(self, name):
        """The label reference of a label this function knows."""
        kind = self.state.label_kinds.get(name) or LabelKind.BLOCK
        scope_id = self.state.label_scopes.get(name)
        if scope_id is None:
            scope_id = self.state.scope_counter
        return self.label_ref(name, kind, scope_id)

    def remember_label(self, name, kind, scope_id):
        """Remember what a label IS, so that a jump to it can say so too."""
        self.state.label_kinds[name] = kind
        self.state.label_scopes[name] = scope_id

    def emit_label(self, name, kind, node):
        """Emit a label."""
        scope_id = self.state.scope_counter
        self.remember_label(name, kind, scope_id)
        self.emit(
            {
                "kind": Kind.LABEL,
                "label": name,
                "labelRef": self.label_ref(name, kind, scope_id),
                **self.position(node),
            }
        )

    def emit_jump(self, name, node):
        """Emit a jump."""
        self.emit(
            {
                "kind": Kind.JUMP,
                "label": name,
                "labelRef": self.label_ref_of(name),
                **self.position(node),
            }
        )

    def emit_branch(self, condition, true_label, false_label, guard, node):
        """Emit a branch.

        The guard is the STRUCTURED condition: it says what the branch
        tests, and the engine turns it into a path condition. A branch with
        no guard tells the solver nothing, which is the same as not having
        lowered the condition at all.
        """
        self.emit(
            {
                "kind": Kind.BRANCH,
                "lhs1": condition,
                "trueLabel": true_label,
                "falseLabel": false_label,
                "guard": guard,
                "labelRef": self.label_ref(
                    true_label, LabelKind.BLOCK, self.state.scope_counter
                ),
                **self.position(node),
            }
        )

    def lower_condition(self, node):
        """Lower a condition, and say what it tests.

        A comparison is a Compare guard and everything else is a Truthiness
        guard: the condition is true when the value is, and the engine's
        language rules say what "is" means.

        The guard is never a guess: it says what the SOURCE says.
        """
        if node is None:
            return {
                "operand": const_op(Const.BOOL, "true"),
                "guard": {"kind": Guard.ALWAYS, "value": True},
            }
        if isinstance(node, ast.Constant) and isinstance(node.value, bool):
            value = bool(node.value)
            return {
                "operand": const_op(Const.BOOL, "true" if value else "false"),
                "guard": {"kind": Guard.ALWAYS, "value": value},
            }
        if (
            isinstance(node, ast.Compare)
            and len(node.ops) == 1
            and len(node.comparators) == 1
        ):
            operator = COMPARISON_OPERATORS.get(type(node.ops[0]))
            if operator is not None:
                left = self.lower_expression(node.left)
                right = self.lower_expression(node.comparators[0])
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
                return {
                    "operand": dest,
                    "guard": {"kind": Guard.COMPARE, "lhs": left, "op": operator, "rhs": right},
                }
        if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
            operand = self.lower_expression(node.operand)
            dest = self.state.new_temp(Type.BOOLEAN)
            self.emit(
                {
                    "kind": Kind.UNARYOP,
                    "dest": dest,
                    "lhs1": operand,
                    "unop": "NOT",
                    **self.position(node),
                }
            )
            return {
                "operand": dest,
                "guard": {"kind": Guard.TRUTHINESS, "operand": operand, "sense": "FALSE"},
            }
        operand = self.lower_expression(node)
        return {
            "operand": operand,
            "guard": {"kind": Guard.TRUTHINESS, "operand": operand, "sense": "TRUE"},
        }

    def annotate(self, state, node, channel):
        """Record the decorators a declaration carries.

        A decorator is what the decorated thing BECOMES, so its name is
        what a rule matches on. The IR has four channels for it, and the
        arguments go into ``annotationValues`` so that a rule can match on
        the value and not only on the name.
        """
        for decorator in getattr(node, "decorator_list", None) or ():
            name = self.decorator_name(decorator)
            if not name:
                continue
            target = state.__dict__.get("%s_annotations" % channel)
            if target is not None:
                target.append(name)
            values = []
            if isinstance(decorator, ast.Call):
                for argument in decorator.args:
                    values.append(self.argument_value_text(argument))
                for keyword in decorator.keywords:
                    values.append(self.argument_value_text(keyword.value))
            existing = state.annotation_values.get(name) or []
            state.annotation_values[name] = existing + values

    def argument_value_text(self, node):
        """The text of a constant argument."""
        if isinstance(node, ast.Constant):
            return str(node.value)
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            return node.attr
        return ""

    def decorator_name(self, decorator):
        """The name a decorator carries.

        ``@app.route("/x")`` is a decorator named ``route``, and
        ``@dataclass`` is one named ``dataclass``.
        """
        if isinstance(decorator, ast.Name):
            return decorator.id
        if isinstance(decorator, ast.Attribute):
            return decorator.attr
        if isinstance(decorator, ast.Call):
            return self.decorator_name(decorator.func)
        return None

    def decorator_owner(self, decorator):
        """The owner a decorator's callee key carries.

        A decorator that is a plain name belongs to the module the file is
        in; one that is an attribute belongs to whatever its receiver is.
        """
        target = decorator.func if isinstance(decorator, ast.Call) else decorator
        if isinstance(target, ast.Name):
            return self.module_name
        if isinstance(target, ast.Attribute):
            owner = self.owner_of_dotted(self.dotted_name(target.value))
            if owner is not None:
                return owner
            return UNKNOWN_OWNER
        return UNKNOWN_OWNER

    def receiver_kind(self, definition):
        """What a function's implicit receiver is.

        A method has one -- the INSTANCE -- except a static method, which
        is called on the class and not on an instance, and a class method,
        whose receiver is the CLASS.

        The rule matters because the receiver is ``args[0]``: a static
        method that is given an instance as its receiver can never be
        called correctly.
        """
        if id(definition) not in self.method_nodes:
            return None
        for decorator in getattr(definition, "decorator_list", None) or ():
            name = self.decorator_name(decorator)
            if name == "staticmethod":
                return None
            if name == "classmethod":
                return "class"
        return "instance"

    def has_receiver(self, definition):
        """Whether a function has an implicit receiver."""
        return self.receiver_kind(definition) is not None

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
                DropImpact.SEMANTICS,
            )

    def emit_havoc(self, node):
        """A value the translator could not lower.

        HAVOC says "this value is unknown". It is neither tainted nor
        clean, and the checkers read it as unknown rather than as safe.
        """
        dest = self.state.new_temp({"kind": Type.UNKNOWN})
        self.emit({"kind": Kind.HAVOC, "dest": dest, **self.position(node)})
        return dest

    def report(self, kind, node, message, impact=None):
        """Report a problem on the current function.

        The same problem in the same function is reported ONCE, with the
        number of times it happened: a file with fifty calls to a name the
        analysis cannot resolve has one problem, not fifty.

        A problem is also a DROP. The problem is what the reader is told;
        the drop is what the engine derives the function's precision from,
        and what says HOW MUCH was lost. A construct that was not understood
        at all costs the function its semantics; one that was understood but
        whose shape is not expressible costs it only its shape.
        """
        key = (kind, message)
        entry = self.state.problem_counts.get(key)
        if entry is not None:
            entry["count"] += 1
        else:
            problem = {
                "kind": kind,
                "message": message,
                "nodeClass": type(node).__name__ if node is not None else None,
            }
            problem.update(self.position(node))
            self.state.problem_counts[key] = {"problem": problem, "count": 1}
            self.state.problems.append(problem)
        self.emit_drop(
            construct_of(kind, node),
            message,
            impact or impact_of_problem(kind),
            node,
        )

    def emit_drop(self, construct, detail, impact, node=None):
        """Record a drop: something this translator could not lower, and
        what it cost.

        This is the ONE exit for a degradation. Every place that gives up on
        a construct comes through here, so that "what did this translator
        not understand" has exactly one answer and the engine's precision is
        derived from the same record the reader sees.

        The drops are deduped by WHERE they happened, not only by what they
        say: two different calls of an unresolvable name are two different
        places the analysis is blind at, and the reader is told about both.
        """
        position = self.position(node)
        node_class = type(node).__name__ if node is not None else None
        key = (
            construct,
            detail,
            impact,
            node_class,
            position["line"],
            position["column"],
        )
        entry = self.state.drop_counts.get(key)
        if entry is not None:
            entry["count"] += 1
            return
        self.state.drop_counts[key] = {"count": 1}
        self.state.drops.append(
            {
                "construct": construct,
                "detail": detail,
                "impact": impact,
                "line": position["line"],
                "column": position["column"],
                "scope": "FUNCTION",
            }
        )

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

    def type_of_expression(self, node):
        """The type of an expression, when the source says so."""
        if isinstance(node, ast.Name):
            return self.state.types.get(node.id)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name):
            if node.func.id in self.defined_classes:
                return node.func.id
        return None

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
