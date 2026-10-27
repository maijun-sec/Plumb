package com.example.dsl;

/**
 * A file that does not parse.
 *
 * <p>JavaParser does not throw the broken statement away: it keeps it as an
 * `UnparsableStmt`, so a rule about unparsable code has something to match.
 */
public class Unparsable {

    void broken() {
        doSomething(
    }
}
