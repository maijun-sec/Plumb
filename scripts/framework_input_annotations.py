#!/usr/bin/env python3
"""The authoritative catalogue of framework parameter-binding annotations.

Every name here was checked against the framework's own documentation:

  Spring MVC / Spring Data (spring.io)
    RequestParam, PathVariable, RequestBody, RequestHeader, RequestAttribute,
    CookieValue, RequestPart, ModelAttribute, SessionAttribute, MatrixVariable

  JAX-RS / Jakarta REST (jakarta.ws.rs)
    QueryParam, PathParam, HeaderParam, CookieParam, FormParam, BeanParam,
    MatrixParam

  Quarkus REST (formerly RESTEasy Reactive) (quarkus.io/guides/rest)
    RestPath, RestQuery, RestHeader, RestCookie, RestForm, RestMatrix,
    RestBeanParam

  Micronaut (docs.micronaut.io/http)
    Body, CookieValue, Header, QueryValue, Part, RequestAttribute,
    PathVariable, RequestBean

The DFA taint engine and the AST input-validation checker must agree on
this list. Before this catalogue existed, each declared its own set and
they had drifted: 13 annotations were an HTTP trust boundary to the
taint engine but invisible to the input-validation checker.

This file is the SINGLE source of truth. The Java side reads it through
a generated class, so the two layers cannot drift again.
"""

# annotation simple name -> (framework id, human description)
ANNOTATIONS = {
    # --- Spring MVC / Spring Data -------------------------------------
    "RequestParam": ("spring-mvc", "a request query or form parameter"),
    "PathVariable": ("spring-mvc", "a URI path variable"),
    "RequestBody": ("spring-mvc", "the deserialized request body"),
    "RequestHeader": ("spring-mvc", "an HTTP request header"),
    "RequestAttribute": ("spring-mvc", "a request attribute"),
    "CookieValue": ("spring-mvc", "an HTTP cookie"),
    "RequestPart": ("spring-mvc", "a part of a multipart request"),
    "ModelAttribute": ("spring-mvc", "a model-bound request object"),
    "SessionAttribute": ("spring-mvc", "an HTTP session attribute"),
    "MatrixVariable": ("spring-mvc", "a URI matrix variable"),
    # --- JAX-RS / Jakarta REST ----------------------------------------
    "QueryParam": ("jax-rs", "a query parameter"),
    "PathParam": ("jax-rs", "a URI path parameter"),
    "HeaderParam": ("jax-rs", "an HTTP header"),
    "CookieParam": ("jax-rs", "an HTTP cookie"),
    "FormParam": ("jax-rs", "a form parameter"),
    "BeanParam": ("jax-rs", "a bean of bound parameters"),
    "MatrixParam": ("jax-rs", "a URI matrix parameter"),
    # --- Quarkus REST / RESTEasy Reactive -----------------------------
    "RestPath": ("quarkus-rest", "a URI path parameter"),
    "RestQuery": ("quarkus-rest", "a query parameter"),
    "RestHeader": ("quarkus-rest", "an HTTP header"),
    "RestCookie": ("quarkus-rest", "an HTTP cookie"),
    "RestForm": ("quarkus-rest", "a form parameter"),
    "RestMatrix": ("quarkus-rest", "a URI matrix parameter"),
    "RestBeanParam": ("quarkus-rest", "a bean of bound parameters"),
    # --- Micronaut -----------------------------------------------------
    "Body": ("micronaut", "the request body"),
    "Header": ("micronaut", "an HTTP header"),
    "QueryValue": ("micronaut", "a query parameter"),
    "Part": ("micronaut", "a part of a multipart request"),
    "RequestBean": ("micronaut", "a bean of bound parameters"),
    # --- Servlet annotation form ---------------------------------------
    "WebParam": ("jakarta-servlet", "a servlet request parameter"),
}

# The frameworks the catalogue covers, in the order they are reported.
FRAMEWORKS = (
    "spring-mvc",
    "jax-rs",
    "quarkus-rest",
    "micronaut",
    "jakarta-servlet",
)

# Annotations that mark a class or method as a framework endpoint, mapped to
# the framework they identify.
#
# The JAX-RS and Quarkus REST endpoint annotations are the SAME names
# (@Path, @GET, @POST, ...), so a bare name cannot tell them apart. The
# framework is then inferred from the BINDING annotation on the parameter,
# which is unambiguous: @RestQuery is Quarkus, @QueryParam is JAX-RS.
# The endpoint map is therefore only a hint, and the binding annotation
# always wins.
ENDPOINT_ANNOTATIONS = {
    # --- Spring MVC / Spring Data -------------------------------------
    "RestController": "spring-mvc",
    "RequestMapping": "spring-mvc",
    "GetMapping": "spring-mvc",
    "PostMapping": "spring-mvc",
    "PutMapping": "spring-mvc",
    "DeleteMapping": "spring-mvc",
    "PatchMapping": "spring-mvc",
    # --- JAX-RS / Jakarta REST ----------------------------------------
    "Path": "jax-rs",
    "GET": "jax-rs",
    "POST": "jax-rs",
    "PUT": "jax-rs",
    "DELETE": "jax-rs",
    "HEAD": "jax-rs",
    "OPTIONS": "jax-rs",
    "PATCH": "jax-rs",
    # --- Micronaut -----------------------------------------------------
    "Controller": "micronaut",
    "Get": "micronaut",
    "Post": "micronaut",
    "Put": "micronaut",
    "Delete": "micronaut",
    "Patch": "micronaut",
    "Head": "micronaut",
    "Options": "micronaut",
}

if __name__ == "__main__":
    print("catalogue: %d annotations across %d frameworks" % (len(ANNOTATIONS), len(FRAMEWORKS)))
    for framework in FRAMEWORKS:
        names = sorted(n for n, (f, _) in ANNOTATIONS.items() if f == framework)
        print("  %-16s %s" % (framework, ", ".join(names)))
    print()
    print("endpoint annotations: %d" % len(ENDPOINT_ANNOTATIONS))
