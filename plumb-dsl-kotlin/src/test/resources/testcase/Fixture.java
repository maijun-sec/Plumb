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
    // ==================================================================
    // The section below produces the node types the corpus demonstrates.
    //
    // Every shape is here for ONE reason: it makes JavaParser produce a
    // node type that no other shape produces.  The comment above each
    // shape names the node type.
    // ==================================================================

    // ---- `ArrayCreationLevel`, `ArrayCreationExpr`, `ArrayInitializerExpr` -
    private int[] arrayField = new int[] {1, 2, 3};

    private int[][] gridField = new int[2][3];

    // ---- `BooleanLiteralExpr`, `CharLiteralExpr`, `DoubleLiteralExpr`,
    //      `LongLiteralExpr`, `NullLiteralExpr`, `TextBlockLiteralExpr` -----
    Object literals() {
        boolean flag = true;
        char letter = 'x';
        long big = 42L;
        double fraction = 4.2d;
        String nothing = null;
        String block = """
                a text block""";
        return flag + "" + letter + big + fraction + nothing + block;
    }

    // ---- `AssignExpr` ---------------------------------------------------
    void assigns(int value) {
        int total = 0;
        total += value;
        total -= value;
        total *= value;
        total /= value;
        total %= value;
        total <<= 1;
        total >>= 1;
        total >>>= 1;
        total &= value;
        total |= value;
        total ^= value;
    }

    // ---- `ArrayAccessExpr`, `EmptyStmt`, `ContinueStmt`, `UnaryExpr` ------
    void readsArrays(int[] values) {
        int first = values[0];
        values[0] = first;
        ;
        for (int i = 0; i < values.length; i++) {
            if (i == 0) {
                continue;
            }
            System.out.println(-values[i]);
        }
    }

    // ---- `CastExpr`, `EnclosedExpr`, `ConditionalExpr` --------------------
    Object castsAndTests(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        return (value != null) ? value.toString() : "";
    }

    // ---- `ClassExpr`, `TypeExpr`, `ThisExpr`, `SuperExpr` ----------------
    Object createsObjects() {
        Class<?> type = String.class;
        int[] copy = new int[] {1, 2, 3};
        synchronized (this) {
            System.out.println(copy.length + type.getName());
        }
        return this;
    }

    // ---- `AssertStmt`, `LabeledStmt`, `BreakStmt` -------------------------
    void assertsAndLabels(int[] values) {
        assert values != null : "values must not be null";
        outer:
        for (int i = 0; i < values.length; i++) {
            if (i == 5) {
                break outer;
            }
        }
    }

    // ---- `ForStmt`, `ForEachStmt`, `WhileStmt`, `DoStmt`, `IfStmt` -------
    void controlFlow(int input) {
        if (input > 0) {
            System.out.println("positive");
        } else if (input < 0) {
            System.out.println("negative");
        } else {
            System.out.println("zero");
        }

        for (int i = 0; i < 10; i++) {
            System.out.println(i);
        }

        for (int each : new int[] {1, 2}) {
            System.out.println(each);
        }

        while (input > 0) {
            input--;
        }

        do {
            input++;
        } while (input < 0);
    }

    // ---- `SwitchStmt`, `SwitchEntry` -------------------------------------
    void switches(int input) {
        switch (input) {
            case 0:
                break;
            case 1:
                System.out.println("one");
                break;
            default:
                break;
        }
    }

    // ---- `SynchronizedStmt` ----------------------------------------------
    void synchronizes() {
        synchronized (this) {
            System.out.println("locked");
        }
    }

    // ---- `LambdaExpr`, `MethodReferenceExpr` -----------------------------
    void usesLambdas() {
        java.util.function.Function<String, Integer> length = value -> value.length();
        java.util.function.Function<String, Integer> compare = String::length;
        Runnable runnable = () -> System.out.println("run");
        System.out.println(length.apply("a") + compare.apply("b"));
        runnable.run();
    }

    // ---- `ConstructorDeclaration`, `ExplicitConstructorInvocationStmt`,
    //      `InitializerDeclaration` ---------------------------------------
    Fixture() {
        this(1);
    }

    Fixture(int seed) {
        super();
        this.mutableField = String.valueOf(seed);
    }

    {
        // An instance initializer.
        mutableField = "initialized";
    }

    static {
        // A static initializer.
    }

    // ---- `ReceiverParameter` ---------------------------------------------
    void withReceiver(Fixture this) {
        System.out.println(this);
    }

    // ---- `EnumDeclaration`, `EnumConstantDeclaration` --------------------
    enum Color {
        RED,
        GREEN;

        boolean isWarm() {
            return this == RED;
        }
    }

    // ---- `RecordDeclaration`, `CompactConstructorDeclaration` ------------
    record Point(int x, int y) {
        Point {
            if (x < 0) {
                throw new IllegalArgumentException("negative");
            }
        }

        int sum() {
            return x + y;
        }
    }

    // ---- `LocalClassDeclarationStmt`, `LocalRecordDeclarationStmt` -------
    void declaresLocalTypes() {
        class Local {
            int value;
        }

        record LocalRecord(int value) {
        }

        Local local = new Local();
        LocalRecord record = new LocalRecord(1);
        System.out.println(local.value + record.value());
    }

    // ---- `AnnotationDeclaration`, `AnnotationMemberDeclaration` ----------
    @interface Marker {
        String name() default "";

        int value() default 0;
    }

    // ---- `MarkerAnnotationExpr`, `NormalAnnotationExpr`,
    //      `SingleMemberAnnotationExpr`, `MemberValuePair` ---------------
    @Marker
    @Marker(name = "marked", value = 1)
    @Marker("positional")
    void annotated() {
    }

    // ---- `TypeParameter`, `IntersectionType`, `WildcardType`, `UnknownType`
    <T extends Number & Comparable<T>> T pick(T first, T second) {
        java.util.Map<String, ? extends Number> map = null;
        java.util.List<?> anything = null;
        return first.compareTo(second) >= 0 ? first : second;
    }

    // ---- `VarType` -------------------------------------------------------
    void infersTypes() {
        var text = "inferred";
        var number = 1;
        System.out.println(text + number);
    }

    // ---- `SwitchExpr`, `YieldStmt` ---------------------------------------
    int modernSwitch(int value) {
        return switch (value) {
            case 1, 2 -> 10;
            case 3 -> {
                yield 30;
            }
            default -> 0;
        };
    }

    // ---- `TypePatternExpr`, `RecordPatternExpr` --------------------------
    Object patterns(Object value) {
        if (value instanceof String text && !text.isEmpty()) {
            return text;
        }
        if (value instanceof Integer number) {
            return number + 1;
        }
        return value;
    }

    // ---- `UnionType`: a multi-catch parameter ----------------------------
    void catchesSeveralTypes() {
        try {
            throwsTwo();
        } catch (RuntimeException | Error e) {
            System.out.println(e);
        }
    }

    private void throwsTwo() {
        throw new IllegalStateException("two");
    }

    // ---- `SuperExpr`: an explicit super call -----------------------------
    @Override
    public String toString() {
        return super.toString() + "!";
    }

    // ---- `BlockComment`: a comment between the asterisks -----------------
    /* A block comment.  `block-comment` matches it. */

    // ---- `IntersectionType`: an intersection cast ------------------------
    void intersects(Object value) {
        Comparable<?> comparable = (Comparable<?> & java.io.Serializable) value;
        System.out.println(comparable);
    }

}
