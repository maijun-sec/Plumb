package com.example.dsl;

import java.util.List;
import java.util.logging.Logger;

/**
 * The fixture the DSL example corpus runs against.
 *
 * <p>Every shape in here is deliberate: the corpus counts the nodes each
 * matcher matches, so the file and the expectations have to agree. The
 * comments say which example each shape is there for.
 */
public class Fixture {

    // ---- a static final field: `static-final-field` matches ---------------
    private static final String CONSTANT = "value";

    // ---- a mutable field: `non-static-field` matches ----------------------
    private String mutableField;

    // ---- a mutable field of a logging type: `field-of-a-logger-type` ------
    private Logger logger;

    // ---- a method whose name starts with "debug": `method-named-debug` ----
    void debugSomething() {
        System.out.println("debug");
    }

    // ---- a method whose name ends with "Helper": `method-named-debug-ends-with`
    void someHelper() {
        System.out.println("helper");
    }

    // ---- a method with no parameters: `method-with-no-parameters` --------
    void noParameters() {
    }

    // ---- a method with one List parameter: `method-with-one-parameter`,
    //      `method-with-a-list-parameter` ----------------------------------
    void oneParameter(List<String> args) {
        System.out.println(args);
    }

    // ---- a method with a call inside it: `method-containing-a-print` ------
    void makesACall() {
        System.out.println("call");
    }

    // ---- a method that calls printStackTrace: `call-to-print-stack-trace` -
    void callsPrintStackTrace() {
        new IllegalStateException("boom").printStackTrace();
    }

    // ---- three catch blocks ------------------------------------------------
    void catches() {
        // `catch-of-exception` matches all three.
        try {
            int x = 5 / 0;
        } catch (Exception e) {
            // An empty catch: `empty-catch-block` and `catch-with-a-statement`
            // are counted against the catch below.
        }

        try {
            int x = 5 / 0;
        } catch (Exception e) {
            // A catch with a return: `catch-containing-a-return` matches.
            return;
        }

        try {
            int x = 5 / 0;
        } catch (Exception e) {
            // A catch with a throw: `catch-containing-a-throw` matches.
            throw new IllegalStateException(e);
        }
    }

    // ---- a method called main, so `broad-catch-outside-main` skips it -----
    public static void main(String[] args) {
        try {
            int x = 5 / 0;
        } catch (Exception e) {
            // A catch inside main: excluded by the method-name check.
        }
    }

    /**
     * A javadoc that mentions a password.
     *
     * `comment-with-a-password` matches this comment.
     */
    void documented() {
    }
}
