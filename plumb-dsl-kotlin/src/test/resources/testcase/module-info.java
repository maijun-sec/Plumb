/**
 * The module fixture the DSL example corpus runs against.
 *
 * <p>A `module-info.java` is a compilation unit of its own, so the module
 * directives cannot live in the class fixture.  Every directive JavaParser
 * can represent is declared here, so the corpus can demonstrate a rule about
 * a module.
 */
module com.example.dsl.fixture {

    requires java.base;
    requires transitive java.logging;

    exports com.example.dsl;

    opens com.example.dsl.internal;

    uses com.example.dsl.spi.Service;

    provides com.example.dsl.spi.Service with com.example.dsl.ServiceImpl;
}
