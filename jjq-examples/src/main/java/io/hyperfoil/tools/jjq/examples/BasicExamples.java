package io.hyperfoil.tools.jjq.examples;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;

import java.util.List;

/**
 * Basic jjq examples using the core API (zero external dependencies).
 *
 * <p>Run with: {@code mvn -pl jjq-examples exec:exec -Dexec.mainClass=io.hyperfoil.tools.jjq.examples.BasicExamples}
 */
public class BasicExamples {

    public static void main(String[] args) {
        section("1. Identity and field access");
        identity();
        fieldAccess();

        section("2. Array operations");
        arrayIteration();
        arraySlicing();

        section("3. Filtering and transformation");
        selectFilter();
        mapTransform();

        section("4. Object construction");
        reshapeObjects();

        section("5. Aggregation");
        reduceSum();
        groupAndCount();

        section("6. String operations");
        stringManipulation();

        section("7. Compile once, apply many");
        compileAndReuse();

        section("8. Building JqValues programmatically");
        buildValues();

        section("9. Bytecode VM execution");
        vmExecution();
    }

    // ---- 1. Identity and field access ----

    static void identity() {
        example(". (identity)",
                ".",
                "{\"name\":\"Alice\",\"age\":30}");
    }

    static void fieldAccess() {
        example(".name (field access)",
                ".name",
                "{\"name\":\"Alice\",\"age\":30}");

        example(".a.b (nested field access)",
                ".a.b",
                "{\"a\":{\"b\":42}}");
    }

    // ---- 2. Array operations ----

    static void arrayIteration() {
        example(".[] (iterate array elements)",
                ".[]",
                "[10,20,30]");

        example(".users[].name (iterate and access field)",
                ".users[].name",
                "{\"users\":[{\"name\":\"Alice\"},{\"name\":\"Bob\"}]}");
    }

    static void arraySlicing() {
        example(".[1:3] (array slice)",
                ".[1:3]",
                "[10,20,30,40,50]");

        example(".[-2:] (last two elements)",
                ".[-2:]",
                "[10,20,30,40,50]");
    }

    // ---- 3. Filtering and transformation ----

    static void selectFilter() {
        example("[.[] | select(. > 3)] (filter values > 3)",
                "[.[] | select(. > 3)]",
                "[1,2,3,4,5,6]");

        example("select objects by field",
                "[.[] | select(.age >= 30)]",
                "[{\"name\":\"Alice\",\"age\":30},{\"name\":\"Bob\",\"age\":25},{\"name\":\"Carol\",\"age\":35}]");
    }

    static void mapTransform() {
        example("map(. * 2) (double each element)",
                "map(. * 2)",
                "[1,2,3,4,5]");

        example("[.[] | {name, upper: (.name | ascii_upcase)}]",
                "[.[] | {name, upper: (.name | ascii_upcase)}]",
                "[{\"name\":\"alice\"},{\"name\":\"bob\"}]");
    }

    // ---- 4. Object construction ----

    static void reshapeObjects() {
        example("{name, email} (pick fields)",
                "{name, email}",
                "{\"name\":\"Alice\",\"email\":\"alice@example.com\",\"age\":30,\"role\":\"admin\"}");

        example("computed keys with expressions",
                "{(.name): .age}",
                "{\"name\":\"Alice\",\"age\":30}");

        example("build summary object",
                "{total: (. | length), items: [.[] | .name]}",
                "[{\"name\":\"A\"},{\"name\":\"B\"},{\"name\":\"C\"}]");
    }

    // ---- 5. Aggregation ----

    static void reduceSum() {
        example("reduce .[] as $x (0; . + $x) (sum array)",
                "reduce .[] as $x (0; . + $x)",
                "[1,2,3,4,5]");

        example("add (builtin sum)",
                "add",
                "[10,20,30]");

        example("min, max",
                "[min, max]",
                "[3,1,4,1,5,9,2,6]");
    }

    static void groupAndCount() {
        example("group_by(.role) | map({role: .[0].role, count: length})",
                "group_by(.role) | map({role: .[0].role, count: length})",
                "[{\"name\":\"A\",\"role\":\"admin\"},{\"name\":\"B\",\"role\":\"user\"},{\"name\":\"C\",\"role\":\"admin\"}]");
    }

    // ---- 6. String operations ----

    static void stringManipulation() {
        example("split and join",
                "split(\",\") | map(. | ltrimstr(\" \")) | join(\" | \")",
                "\"apple, banana, cherry\"");

        example("string interpolation",
                "\"Hello \\(.name), you are \\(.age) years old\"",
                "{\"name\":\"Alice\",\"age\":30}");

        example("test with regex",
                "[.[] | select(test(\"^a\"))]",
                "[\"apple\",\"banana\",\"avocado\",\"cherry\"]");
    }

    // ---- 7. Compile once, apply many ----

    static void compileAndReuse() {
        // Compile the program once - this is thread-safe and reusable
        JqProgram program = JqProgram.compile(".name | ascii_upcase");

        String[] inputs = {
                "{\"name\":\"alice\"}",
                "{\"name\":\"bob\"}",
                "{\"name\":\"carol\"}"
        };

        System.out.println("  Compiled: " + program.expression());
        for (String json : inputs) {
            JqValue input = JqValues.parse(json);
            List<JqValue> results = program.applyAll(input);
            System.out.printf("  %-25s => %s%n", json, results.getFirst().toJsonString());
        }
    }

    // ---- 8. Building JqValues programmatically ----

    static void buildValues() {
        // Instead of parsing JSON strings, build values directly from Java objects
        var users = JqArray.of(
                JqObject.of(java.util.Map.of(
                        "name", JqString.of("Alice"),
                        "score", JqNumber.of(95)
                )),
                JqObject.of(java.util.Map.of(
                        "name", JqString.of("Bob"),
                        "score", JqNumber.of(82)
                )),
                JqObject.of(java.util.Map.of(
                        "name", JqString.of("Carol"),
                        "score", JqNumber.of(91)
                ))
        );

        System.out.println("  Input (built programmatically): " + users.toJsonString());

        JqProgram program = JqProgram.compile("[.[] | select(.score >= 90) | .name]");
        List<JqValue> results = program.applyAll(users);
        System.out.println("  Filter: " + program.expression());
        System.out.println("  Result: " + results.getFirst().toJsonString());
    }

    // ---- 9. Bytecode VM execution ----

    static void vmExecution() {
        JqProgram program = JqProgram.compile("[.[] | . * 2 + 1]");
        JqValue input = JqValues.parse("[1,2,3,4,5]");

        List<JqValue> results = program.applyAll(input);
        System.out.println("  Result: " + results.getFirst().toJsonString());

        // Inspect the compiled bytecode
        System.out.println("  Bytecode disassembly:");
        for (String line : program.getBytecode().disassemble().lines().toList()) {
            System.out.println("    " + line);
        }
    }

    // ---- Helpers ----

    static void example(String description, String filter, String inputJson) {
        JqProgram program = JqProgram.compile(filter);
        JqValue input = JqValues.parse(inputJson);
        List<JqValue> results = program.applyAll(input);

        System.out.println("  " + description);
        System.out.println("    Filter: " + filter);
        System.out.println("    Input:  " + inputJson);
        if (results.size() == 1) {
            System.out.println("    Output: " + results.getFirst().toJsonString());
        } else {
            for (int i = 0; i < results.size(); i++) {
                System.out.println("    Output[" + i + "]: " + results.get(i).toJsonString());
            }
        }
        System.out.println();
    }

    static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
        System.out.println();
    }
}
