package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JqValueTest {

    @Test
    void testNull() {
        assertEquals(JqValue.Type.NULL, JqNull.NULL.type());
        assertFalse(JqNull.NULL.isTruthy());
        assertEquals("null", JqNull.NULL.toJsonString());
        assertEquals(0, JqNull.NULL.length());
    }

    @Test
    void testBoolean() {
        assertTrue(JqBoolean.TRUE.isTruthy());
        assertFalse(JqBoolean.FALSE.isTruthy());
        assertEquals("true", JqBoolean.TRUE.toJsonString());
        assertEquals("false", JqBoolean.FALSE.toJsonString());
        assertSame(JqBoolean.TRUE, JqBoolean.of(true));
        assertSame(JqBoolean.FALSE, JqBoolean.of(false));
    }

    @Test
    void testNumber() {
        assertEquals(JqNumber.of(42), JqNumber.of(42));
        assertEquals(JqNumber.of(42), JqNumber.of(42L));
        assertEquals("42", JqNumber.of(42).toJsonString());
        assertEquals("3.14", JqNumber.of(3.14).toJsonString());
        assertTrue(JqNumber.of(0).isTruthy()); // numbers are truthy
    }

    @Test
    void testNumberCache() {
        assertSame(JqNumber.of(0), JqNumber.of(0));
        assertSame(JqNumber.of(1), JqNumber.of(1));
        assertSame(JqNumber.of(-1), JqNumber.of(-1));
        assertSame(JqNumber.of(127), JqNumber.of(127));
    }

    @Test
    void testString() {
        var s = JqString.of("hello");
        assertEquals("hello", s.stringValue());
        assertEquals("\"hello\"", s.toJsonString());
        assertEquals(5, s.length());

        // Escape sequences
        assertEquals("\"he\\nllo\"", JqString.of("he\nllo").toJsonString());
        assertEquals("\"he\\\"llo\"", JqString.of("he\"llo").toJsonString());
    }

    @Test
    void testArray() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3));
        assertEquals(3, arr.length());
        assertEquals("[1,2,3]", arr.toJsonString());
        assertEquals(JqNumber.of(1), arr.get(0));
        assertEquals(JqNumber.of(3), arr.get(-1));
        assertEquals(JqNull.NULL, arr.get(10));
    }

    @Test
    void testArraySlice() {
        var arr = JqArray.of(JqNumber.of(1), JqNumber.of(2), JqNumber.of(3), JqNumber.of(4));
        assertEquals("[2,3]", arr.slice(1, 3).toJsonString());
        assertEquals("[3,4]", arr.slice(2, null).toJsonString());
        assertEquals("[1,2]", arr.slice(null, 2).toJsonString());
    }

    @Test
    void testObject() {
        var map = new LinkedHashMap<String, JqValue>();
        map.put("name", JqString.of("Alice"));
        map.put("age", JqNumber.of(30));
        var obj = JqObject.of(map);
        assertEquals(2, obj.length());
        assertEquals(JqString.of("Alice"), obj.get("name"));
        assertEquals(JqNull.NULL, obj.get("missing"));
        assertTrue(obj.has("name"));
        assertFalse(obj.has("missing"));
    }

    @Test
    void testAdd() {
        assertEquals(JqNumber.of(5), JqNumber.of(2).add(JqNumber.of(3)));
        assertEquals(JqString.of("ab"), JqString.of("a").add(JqString.of("b")));
        assertEquals("[1,2,3,4]", JqArray.of(JqNumber.of(1), JqNumber.of(2))
                .add(JqArray.of(JqNumber.of(3), JqNumber.of(4))).toJsonString());
    }

    @Test
    void testSubtract() {
        assertEquals(JqNumber.of(1), JqNumber.of(3).subtract(JqNumber.of(2)));
    }

    @Test
    void testMultiply() {
        assertEquals(JqNumber.of(6), JqNumber.of(2).multiply(JqNumber.of(3)));
    }

    @Test
    void testDivide() {
        assertEquals(JqNumber.of(2), JqNumber.of(6).divide(JqNumber.of(3)));
        // String split
        var result = JqString.of("a,b,c").divide(JqString.of(","));
        assertEquals("[\"a\",\"b\",\"c\"]", result.toJsonString());
    }

    @Test
    void testNegate() {
        assertEquals(JqNumber.of(-5), JqNumber.of(5).negate());
        assertEquals(JqNumber.of(5), JqNumber.of(-5).negate());
    }

    @Test
    void testCompare() {
        assertTrue(JqNumber.of(1).compareTo(JqNumber.of(2)) < 0);
        assertTrue(JqString.of("a").compareTo(JqString.of("b")) < 0);
        assertEquals(0, JqNull.NULL.compareTo(JqNull.NULL));
        // Type ordering: null < boolean < number < string < array < object
        assertTrue(JqNull.NULL.compareTo(JqBoolean.FALSE) < 0);
        assertTrue(JqBoolean.FALSE.compareTo(JqNumber.of(0)) < 0);
        assertTrue(JqNumber.of(0).compareTo(JqString.of("")) < 0);
    }

    @Test
    void testNullAdd() {
        // null + x = x (jq semantics)
        assertEquals(JqNumber.of(5), JqNull.NULL.add(JqNumber.of(5)));
        assertEquals(JqString.of("hello"), JqNull.NULL.add(JqString.of("hello")));
    }

    // ========================================================================
    //  byte[] parser tests -- verify identical output to String parser
    // ========================================================================

    @Test
    void testByteParserScalars() {
        assertByteParseSame("null");
        assertByteParseSame("true");
        assertByteParseSame("false");
        assertByteParseSame("42");
        assertByteParseSame("-17");
        assertByteParseSame("3.14");
        assertByteParseSame("1e10");
        assertByteParseSame("3.14e-2");
        assertByteParseSame("-0.5");
        assertByteParseSame("0");
        assertByteParseSame("\"\"");
        assertByteParseSame("\"hello\"");
        assertByteParseSame("\"hello world\"");
    }

    @Test
    void testByteParserEscapedStrings() {
        assertByteParseSame("\"hello\\nworld\"");
        assertByteParseSame("\"tab\\there\"");
        assertByteParseSame("\"quote\\\"inside\"");
        assertByteParseSame("\"back\\\\slash\"");
        assertByteParseSame("\"unicode\\u0041\"");  // \u0041 = A
    }

    @Test
    void testByteParserArrays() {
        assertByteParseSame("[]");
        assertByteParseSame("[1,2,3]");
        assertByteParseSame("[\"a\",\"b\"]");
        assertByteParseSame("[[1],[2,3]]");
        assertByteParseSame("[true,false,null,42,\"str\"]");
    }

    @Test
    void testByteParserObjects() {
        assertByteParseSame("{}");
        assertByteParseSame("{\"name\":\"Alice\",\"age\":30}");
        assertByteParseSame("{\"a\":{\"b\":{\"c\":42}}}");
    }

    @Test
    void testByteParserComplex() {
        assertByteParseSame("[{\"x\":1},{\"x\":2}]");
        assertByteParseSame("{\"arr\":[1,2,3],\"obj\":{\"a\":\"b\"}}");
        assertByteParseSame("{\"results\":[{\"load\":{\"avThroughput\":1000.5}}]}");
    }

    @Test
    void testByteParserUtf8() {
        // Multi-byte UTF-8 characters
        assertByteParseSame("{\"city\":\"T\\u00f6ky\\u00f6\"}");
        // Direct UTF-8 in string
        String utf8Json = "{\"emoji\":\"hello\"}"; // simple ASCII for now
        assertByteParseSame(utf8Json);
    }

    @Test
    void testByteParserWhitespace() {
        String json = "  {  \"a\"  :  1  ,  \"b\"  :  [  2  ,  3  ]  }  ";
        assertByteParseSame(json);
    }

    @Test
    void testByteParserBOM() {
        // UTF-8 BOM: EF BB BF
        byte[] withBom = new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF, '4', '2'};
        assertEquals(JqNumber.of(42), JqValues.parse(withBom));
    }

    @Test
    void testByteParserParseAll() {
        String jsonl = "{\"a\":1}\n{\"b\":2}\n{\"c\":3}";
        byte[] bytes = jsonl.getBytes(StandardCharsets.UTF_8);
        var fromString = JqValues.parseAll(jsonl);
        var fromBytes = JqValues.parseAll(bytes);
        assertEquals(fromString.size(), fromBytes.size());
        for (int i = 0; i < fromString.size(); i++) {
            assertEquals(fromString.get(i).toJsonString(), fromBytes.get(i).toJsonString());
        }
    }

    // ========================================================================
    //  SWAR string scanning edge cases
    //  Tests boundary conditions around the 8-byte SWAR scanning window
    // ========================================================================

    @Test
    void testByteParserStringLengths1to16() {
        // Test strings of every length from 1 to 16 characters
        // to exercise both SWAR path (>=8 bytes) and scalar tail (<8 bytes)
        for (int len = 1; len <= 16; len++) {
            String value = "a".repeat(len);
            String json = "\"" + value + "\"";
            assertByteParseSame(json);
        }
    }

    @Test
    void testByteParserQuoteAtEachPosition() {
        // String where the closing quote falls at each position 0-15
        // within SWAR scanning windows. Tests that getIndex() correctly
        // identifies the first match position.
        for (int len = 0; len <= 15; len++) {
            String value = "x".repeat(len);
            String json = "\"" + value + "\"";
            JqValue result = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
            assertEquals(JqString.of(value), result,
                    "Failed for string length " + len);
        }
    }

    @Test
    void testByteParserBackslashAtEachPosition() {
        // String with backslash at each position 0-15 to test
        // the SWAR -> scalar fallback at every boundary
        for (int pos = 0; pos <= 15; pos++) {
            String prefix = "x".repeat(pos);
            String json = "\"" + prefix + "\\n\"";
            assertByteParseSame(json);
        }
    }

    @Test
    void testByteParserExactly8ByteString() {
        // String content exactly 8 bytes -- fills one SWAR word
        assertByteParseSame("\"abcdefgh\"");
    }

    @Test
    void testByteParserExactly7ByteString() {
        // String content exactly 7 bytes -- scalar tail only, no SWAR
        assertByteParseSame("\"abcdefg\"");
    }

    @Test
    void testByteParserExactly9ByteString() {
        // 9 bytes -- one SWAR iteration + 1 scalar byte
        assertByteParseSame("\"abcdefghi\"");
    }

    @Test
    void testByteParserExactly16ByteString() {
        // 16 bytes -- two full SWAR iterations
        assertByteParseSame("\"abcdefghijklmnop\"");
    }

    @Test
    void testByteParserLongString() {
        // 100+ bytes -- many SWAR iterations + scalar tail
        String value = "abcdefghij".repeat(10);
        assertByteParseSame("\"" + value + "\"");
    }

    @Test
    void testByteParserMultipleStringsInObject() {
        // Object with many string fields -- exercises key scanning (SWAR)
        // and value scanning (SWAR) across multiple fields
        var sb = new StringBuilder("{");
        for (int i = 0; i < 20; i++) {
            if (i > 0) sb.append(",");
            String key = "field_" + i + "_name";
            String val = "value_" + i + "_data_" + "x".repeat(i);
            sb.append("\"").append(key).append("\":\"").append(val).append("\"");
        }
        sb.append("}");
        assertByteParseSame(sb.toString());
    }

    @Test
    void testByteParserEscapeAt8ByteBoundary() {
        // Escape sequence spanning the 8-byte SWAR boundary
        // "1234567\n" -- backslash at position 7, \n at position 8
        assertByteParseSame("\"1234567\\n\"");
        // "12345678\n" -- backslash at position 8 (start of second SWAR word)
        assertByteParseSame("\"12345678\\n\"");
    }

    @Test
    void testByteParserEscapedQuoteInSwarWindow() {
        // Escaped quote within SWAR scanning range
        // The SWAR should detect the backslash, fall to scalar path,
        // which then correctly skips the escaped quote
        assertByteParseSame("\"hello\\\"world\"");
        assertByteParseSame("\"12345678\\\"end\"");  // escaped quote after 8 chars
        assertByteParseSame("\"1234\\\"5678end\"");   // escaped quote at position 4
    }

    @Test
    void testByteParserUnicodeEscapeInSwarWindow() {
        // Unicode escape sequences within SWAR scanning range
        assertByteParseSame("\"prefix\\u0041suffix\"");  // \u0041 = A
        assertByteParseSame("\"12345678\\u0042end\"");   // unicode after 8 chars
    }

    @Test
    void testByteParserManyShortStrings() {
        // Array of very short strings -- each string is < 8 bytes,
        // exercises the scalar tail path exclusively
        assertByteParseSame("[\"a\",\"bb\",\"ccc\",\"dddd\",\"eeeee\",\"ffffff\",\"ggggggg\"]");
    }

    @Test
    void testByteParserEmptyAndSingleCharStrings() {
        assertByteParseSame("\"\"");
        assertByteParseSame("\"a\"");
        assertByteParseSame("\"\\n\"");
        assertByteParseSame("\"\\\"\"");
    }

    @Test
    void testByteParserObjectKeysVariedLengths() {
        // Object keys of varied lengths to exercise SWAR key scanning
        assertByteParseSame("{\"a\":1}");
        assertByteParseSame("{\"abcdefg\":1}");          // 7 bytes (scalar only)
        assertByteParseSame("{\"abcdefgh\":1}");         // 8 bytes (one SWAR word)
        assertByteParseSame("{\"abcdefghijklmnop\":1}"); // 16 bytes (two SWAR words)
        assertByteParseSame("{\"avThroughput\":1}");     // typical h5m key
        assertByteParseSame("{\"config.QUARKUS_VERSION\":\"3.8.1\"}"); // longer key
    }

    @Test
    void testByteParserRoundTripConsistency() {
        // Parse from bytes, serialize to JSON, re-parse from String,
        // verify the values are equal
        String[] inputs = {
            "{\"name\":\"Alice\",\"scores\":[1,2,3],\"active\":true}",
            "[{\"id\":1,\"label\":\"first\"},{\"id\":2,\"label\":\"second_item_with_longer_name\"}]",
            "{\"nested\":{\"deep\":{\"value\":\"found_it_here\"}}}",
        };
        for (String json : inputs) {
            JqValue fromBytes = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
            String serialized = fromBytes.toJsonString();
            JqValue reParsed = JqValues.parse(serialized);
            assertEquals(reParsed, fromBytes,
                    "Round-trip failed for: " + json);
        }
    }

    private void assertByteParseSame(String json) {
        JqValue fromString = JqValues.parse(json);
        JqValue fromBytes = JqValues.parse(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(fromString.toJsonString(), fromBytes.toJsonString(),
                "Byte parser produced different output for: " + json);
        // Also verify equals works (tests materialization for deferred strings)
        assertEquals(fromString, fromBytes,
                "Byte parser value not equal for: " + json);
    }
}
