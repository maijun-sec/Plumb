# Support status - 06-framework-orms

**Two framework shapes are covered, and they need two different engines.**

The Spring JDBC shape is a *taint-flow* problem: the request value is bound to a method parameter by the framework, flows through a field, and reaches a `JdbcTemplate` call whose SQL text is built by concatenation. Nothing about the framework is unusual except that the receiver's type cannot be resolved. The third-party jar is not on the analysis classpath -- the normal situation for a source-only scan -- so the TAC writes the callee signature with a SIMPLE receiver name (`JdbcTemplate.query/2`) instead of a fully qualified one. Every one of the 416 sink patterns in the registry is anchored to a fully qualified name, so before the sink matcher learned to accept the prefix-optional form, none of these sinks was reachable, and nothing was reported: no error, no warning, just a scanner that could not see Spring at all. The relaxed form is consulted only for a bare simple-name receiver; a fully qualified callee still matches the strict pattern first, the class name and the method name both stay mandatory, and a word boundary after the method name keeps `JdbcTemplate.query` from matching `queryForList`.

The MyBatis shape is *not* a taint-flow problem, and treating it as one is the mistake that hides it. The TAC does not carry annotations, and a mapper method lowers to a bare `RETURN`, so by the time the taint engine runs, the string `"SELECT * FROM users WHERE name = '${name}'"` is gone -- there is nothing left to propagate. The detection therefore belongs in the AST, where the annotation is still attached to the method. The rule reports a raw `${...}` substitution and not a bound `#{...}` placeholder, because the two have opposite safety properties: `#{}` becomes a PreparedStatement placeholder and the value is bound as data, while `${}` is a raw string substitution and the value becomes part of the SQL text. A `${}` that names something the method does not receive is not reported either -- a dynamic table name or column is a legitimate use of raw substitution, because a prepared-statement placeholder cannot stand in for SQL structure.

The third shape is the FRAMEWORK ENTRY POINT, and it is the one a servlet-aware scanner cannot see at all. A Spring controller has no `getParameter()` call anywhere: the framework binds the request value to the method parameter BY ANNOTATION, and the controller body never touches a request object. So the source is not a call, it is a parameter annotation. The taint engine therefore treats a parameter carrying `@RequestParam` / `@PathVariable` / `@QueryParam` as a source whose origin is the HTTP request, and the finding names that origin (`param:customer@spring-mvc:RequestParam`) rather than an opaque parameter name. The annotation list is the same one the AST checkers use for input validation, so the two layers agree on what "user input" means in this codebase. A framework entry point outranks the caller's origin: the value was bound by the framework from the request, so no caller can supply a more specific origin for it, and the caller's label would name an unrelated parameter in an unrelated class.

## Measured

- True positives: **TP 9/9**
- True negatives: **TN 6/6**
- Scan mode: per-file, callgraph strategy: `global`.

## Outstanding cases

none

## Known limitations

none

Full per-case detail: `RESULT.json`. Expectations: `EXPECTATIONS.json`.
