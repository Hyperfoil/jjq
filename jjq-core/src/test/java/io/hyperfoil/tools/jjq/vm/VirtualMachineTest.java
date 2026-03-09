package io.hyperfoil.tools.jjq.vm;

import io.hyperfoil.tools.jjq.JqProgram;
import io.hyperfoil.tools.jjq.value.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VirtualMachineTest {

    private List<JqValue> vm(String filter, String inputJson) {
        JqProgram program = JqProgram.compile(filter);
        return program.applyAll(JqValues.parse(inputJson));
    }

    private JqValue vmOne(String filter, String inputJson) {
        List<JqValue> results = vm(filter, inputJson);
        assertEquals(1, results.size(), "Expected 1 result for '%s' on '%s', got %d".formatted(filter, inputJson, results.size()));
        return results.getFirst();
    }

    @Nested
    class Identity {
        @Test void identity() { assertEquals(JqNumber.of(42), vmOne(".", "42")); }
        @Test void identityNull() { assertEquals(JqNull.NULL, vmOne(".", "null")); }
        @Test void identityString() { assertEquals(JqString.of("hello"), vmOne(".", "\"hello\"")); }
    }

    @Nested
    class FieldAccess {
        @Test void simpleField() { assertEquals(JqNumber.of(1), vmOne(".a", "{\"a\":1,\"b\":2}")); }
        @Test void chainedField() { assertEquals(JqNumber.of(3), vmOne(".a.b", "{\"a\":{\"b\":3}}")); }
        @Test void missingField() { assertEquals(JqNull.NULL, vmOne(".missing", "{\"a\":1}")); }
    }

    @Nested
    class Pipe {
        @Test void simplePipe() { assertEquals(JqNumber.of(3), vmOne(".a | . + 1", "{\"a\":2}")); }

        @Test void pipeWithIteration() {
            List<JqValue> results = vm(".[] | . * 2", "[1,2,3]");
            assertEquals(3, results.size());
            assertEquals(JqNumber.of(2), results.get(0));
            assertEquals(JqNumber.of(4), results.get(1));
            assertEquals(JqNumber.of(6), results.get(2));
        }
    }

    @Nested
    class Comma {
        @Test void commaExpr() {
            List<JqValue> results = vm(".a, .b", "{\"a\":1,\"b\":2}");
            assertEquals(2, results.size());
            assertEquals(JqNumber.of(1), results.get(0));
            assertEquals(JqNumber.of(2), results.get(1));
        }
    }

    @Nested
    class Iteration {
        @Test void arrayIteration() {
            List<JqValue> results = vm(".[]", "[1,2,3]");
            assertEquals(3, results.size());
            assertEquals(JqNumber.of(1), results.get(0));
            assertEquals(JqNumber.of(2), results.get(1));
            assertEquals(JqNumber.of(3), results.get(2));
        }

        @Test void objectIteration() {
            List<JqValue> results = vm(".[]", "{\"a\":1,\"b\":2}");
            assertEquals(2, results.size());
            assertEquals(JqNumber.of(1), results.get(0));
            assertEquals(JqNumber.of(2), results.get(1));
        }
    }

    @Nested
    class Arithmetic {
        @Test void add() { assertEquals(JqNumber.of(3), vmOne(". + 1", "2")); }
        @Test void sub() { assertEquals(JqNumber.of(4), vmOne(". - 1", "5")); }
        @Test void mul() { assertEquals(JqNumber.of(12), vmOne(". * 3", "4")); }
        @Test void div() { assertEquals(JqNumber.of(5), vmOne(". / 2", "10")); }
        @Test void mod() { assertEquals(JqNumber.of(1), vmOne(". % 3", "7")); }
        @Test void negate() { assertEquals(JqNumber.of(-5), vmOne("-.", "5")); }
    }

    @Nested
    class Comparison {
        @Test void eq() { assertEquals(JqBoolean.TRUE, vmOne(". == 1", "1")); }
        @Test void neq() { assertEquals(JqBoolean.TRUE, vmOne(". != 1", "2")); }
        @Test void lt() { assertEquals(JqBoolean.TRUE, vmOne(". < 2", "1")); }
        @Test void gt() { assertEquals(JqBoolean.FALSE, vmOne(". > 2", "1")); }
    }

    @Nested
    class ArrayConstruction {
        @Test void emptyArray() { assertEquals(JqArray.EMPTY, vmOne("[]", "null")); }

        @Test void arrayConstruct() {
            JqValue result = vmOne("[.a, .b]", "{\"a\":1,\"b\":2}");
            assertEquals(JqValues.parse("[1,2]"), result);
        }

        @Test void arrayWithIteration() {
            JqValue result = vmOne("[.[] | . * 2]", "[1,2,3]");
            assertEquals(JqValues.parse("[2,4,6]"), result);
        }

        @Test void arrayWithSelectAndMap() {
            JqValue result = vmOne("[.[] | select(. > 5) | . * 2]", "[1,3,6,8,10]");
            assertEquals(JqValues.parse("[12,16,20]"), result);
        }
    }

    @Nested
    class IfThenElse {
        @Test void ifTrue() { assertEquals(JqString.of("yes"), vmOne("if true then \"yes\" else \"no\" end", "null")); }
        @Test void ifFalse() { assertEquals(JqString.of("no"), vmOne("if false then \"yes\" else \"no\" end", "null")); }
        @Test void elif() { assertEquals(JqString.of("two"), vmOne("if . == 1 then \"one\" elif . == 2 then \"two\" else \"other\" end", "2")); }
    }

    @Nested
    class Variables {
        @Test void variableBinding() {
            assertEquals(JqNumber.of(2), vmOne(". as $x | $x + 1", "1"));
        }
    }

    @Nested
    class ReduceExpr {
        @Test void reduceSum() {
            assertEquals(JqNumber.of(6), vmOne("reduce .[] as $x (0; . + $x)", "[1,2,3]"));
        }

        @Test void reduceConcat() {
            assertEquals(JqString.of("abc"), vmOne("reduce .[] as $x (\"\"; . + $x)", "[\"a\",\"b\",\"c\"]"));
        }
    }

    @Nested
    class TryCatch {
        @Test void tryCatchError() {
            assertEquals(JqString.of("caught"), vmOne("try error catch \"caught\"", "null"));
        }
    }

    @Nested
    class Builtins {
        @Test void length() { assertEquals(JqNumber.of(3), vmOne("length", "[1,2,3]")); }
        @Test void type() { assertEquals(JqString.of("number"), vmOne("type", "1")); }
        @Test void keys() { assertEquals(JqValues.parse("[\"a\",\"b\"]"), vmOne("keys", "{\"b\":2,\"a\":1}")); }
        @Test void add() { assertEquals(JqNumber.of(6), vmOne("add", "[1,2,3]")); }
        @Test void map() { assertEquals(JqValues.parse("[2,3,4]"), vmOne("map(. + 1)", "[1,2,3]")); }
        @Test void select() { assertEquals(JqValues.parse("[3,4]"), vmOne("[.[] | select(. > 2)]", "[1,2,3,4]")); }
        @Test void sort() { assertEquals(JqValues.parse("[1,2,3]"), vmOne("sort", "[3,1,2]")); }
        @Test void reverse() { assertEquals(JqValues.parse("[3,2,1]"), vmOne("reverse", "[1,2,3]")); }
        @Test void tostring() { assertEquals(JqString.of("42"), vmOne("tostring", "42")); }
        @Test void values() { assertEquals(JqValues.parse("[1,2]"), vmOne("values", "{\"a\":1,\"b\":2}")); }
        @Test void notTrue() { assertEquals(JqBoolean.FALSE, vmOne("not", "true")); }
        @Test void notFalse() { assertEquals(JqBoolean.TRUE, vmOne("not", "false")); }
        @Test void tonumber() { assertEquals(JqNumber.of(42), vmOne("tonumber", "\"42\"")); }
        @Test void min() { assertEquals(JqNumber.of(1), vmOne("min", "[3,1,2]")); }
        @Test void max() { assertEquals(JqNumber.of(3), vmOne("max", "[3,1,2]")); }
        @Test void flatten() { assertEquals(JqValues.parse("[1,2,3,4]"), vmOne("flatten", "[[1,2],[3,[4]]]")); }
        @Test void unique() { assertEquals(JqValues.parse("[1,2,3]"), vmOne("unique", "[3,1,2,1,3]")); }
        @Test void floor() { assertEquals(JqNumber.of(3), vmOne("floor", "3.7")); }
        @Test void ceil() { assertEquals(JqNumber.of(4), vmOne("ceil", "3.2")); }
        @Test void round() { assertEquals(JqNumber.of(4), vmOne("round", "3.5")); }
        @Test void abs() { assertEquals(JqNumber.of(5), vmOne("abs", "-5")); }
        @Test void tojson() { assertEquals(JqString.of("[1,2]"), vmOne("tojson", "[1,2]")); }
        @Test void fromjson() { assertEquals(JqValues.parse("[1,2]"), vmOne("fromjson", "\"[1,2]\"")); }
    }

    @Nested
    class CompoundFieldAccess {
        @Test void dotField2() {
            assertEquals(JqNumber.of(3), vmOne(".a.b", "{\"a\":{\"b\":3}}"));
        }

        @Test void dotField2Missing() {
            assertEquals(JqNull.NULL, vmOne(".a.b", "{\"a\":{\"c\":1}}"));
        }

        @Test void dotField2NullIntermediate() {
            assertEquals(JqNull.NULL, vmOne(".a.b", "{\"x\":1}"));
        }

        @Test void dotField2UsesOptimizedOpcode() {
            JqProgram program = JqProgram.compile(".a.b");
            String asm = program.getBytecode().disassemble();
            assertTrue(asm.contains("DOT_FIELD2"), "Should use DOT_FIELD2 opcode: " + asm);
        }
    }

    @Nested
    class InlinedBuiltinOpcodes {
        @Test void lengthUsesInlinedOpcode() {
            JqProgram program = JqProgram.compile("length");
            String asm = program.getBytecode().disassemble();
            assertTrue(asm.contains("BUILTIN_LENGTH"), "Should use BUILTIN_LENGTH: " + asm);
        }

        @Test void typeUsesInlinedOpcode() {
            JqProgram program = JqProgram.compile("type");
            String asm = program.getBytecode().disassemble();
            assertTrue(asm.contains("BUILTIN_TYPE"), "Should use BUILTIN_TYPE: " + asm);
        }

        @Test void sortUsesInlinedOpcode() {
            JqProgram program = JqProgram.compile("sort");
            String asm = program.getBytecode().disassemble();
            assertTrue(asm.contains("BUILTIN_SORT"), "Should use BUILTIN_SORT: " + asm);
        }

        @Test void emptyProducesNoOutput() {
            List<JqValue> results = vm("empty", "null");
            assertTrue(results.isEmpty());
        }
    }

    @Nested
    class Disassembly {
        @Test void disassemble() {
            JqProgram program = JqProgram.compile(".a | . + 1");
            Bytecode bc = program.getBytecode();
            String asm = bc.disassemble();
            assertNotNull(asm);
            assertTrue(asm.contains("DOT_FIELD"));
            assertTrue(asm.contains("SET_INPUT"));
            assertTrue(asm.contains("ADD"));
            assertTrue(asm.contains("OUTPUT"));
        }
    }

    @Nested
    class ConstantFolding {
        @Test void foldArithmetic() {
            // 2 + 3 should be folded to 5 at compile time
            JqProgram program = JqProgram.compile("2 + 3");
            Bytecode bc = program.getBytecode();
            String asm = bc.disassemble();
            // Should have PUSH_CONST 5, not PUSH_CONST 2, PUSH_CONST 3, ADD
            assertFalse(asm.contains("ADD"), "ADD should be folded away: " + asm);
            assertEquals(JqNumber.of(5), vmOne("2 + 3", "null"));
        }

        @Test void foldComparison() {
            // 1 < 2 should be folded to true
            JqProgram program = JqProgram.compile("1 < 2");
            Bytecode bc = program.getBytecode();
            String asm = bc.disassemble();
            // Verify no LT opcode in output (careful: "HALT" contains "LT")
            assertFalse(asm.lines().anyMatch(l -> l.trim().startsWith(l.trim().split(":")[0] + ":") && l.contains(": LT ")),
                    "LT opcode should be folded away");
            // Simpler: just verify the bytecode is small (3 instructions: PUSH_TRUE, OUTPUT, HALT)
            assertEquals(3, program.getBytecode().size(), "Should have 3 instructions after folding");
            assertEquals(JqBoolean.TRUE, vmOne("1 < 2", "null"));
        }

        @Test void noFoldWithInput() {
            // . + 1 cannot be folded (depends on input)
            JqProgram program = JqProgram.compile(". + 1");
            Bytecode bc = program.getBytecode();
            String asm = bc.disassemble();
            assertTrue(asm.contains("ADD"), "ADD should NOT be folded: " + asm);
        }
    }
}
