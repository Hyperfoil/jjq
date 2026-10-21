package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqValues;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict bytes-to-target dispatch (issue #174): registered readers serve
 * canonical inputs without a tree parse, everything else reproduces the
 * generic path exactly, and the lenient overloads keep their contract.
 */
class JqMapperStrictTest {

    record Flat(String name, int age) {}

    /** Stub reader with scripted outcomes and invocation tracking. */
    static final class StubReader implements DirectReader<Flat> {
        int invocations;
        boolean fallBack;

        @Override
        public Class<Flat> type() {
            return Flat.class;
        }

        @Override
        public Flat read(byte[] json, JqMapper mapper) {
            invocations++;
            if (fallBack) throw new DirectReadFallbackException();
            return new Flat("direct", 1);
        }
    }

    static byte[] bytes(String doc) {
        return doc.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void registeredReaderServesCanonicalInput() {
        StubReader stub = new StubReader();
        JqMapper mapper = JqMapper.builder().registerDirectReader(stub).build();
        assertEquals(new Flat("direct", 1),
                mapper.fromJsonStrict(bytes("{\"name\":\"x\",\"age\":2}"), Flat.class));
        assertEquals(1, stub.invocations);
    }

    @Test
    void fallbackReproducesTheGenericPath() {
        StubReader stub = new StubReader();
        stub.fallBack = true;
        JqMapper mapper = JqMapper.builder().registerDirectReader(stub).build();
        assertEquals(new Flat("a", 2), mapper.fromJsonStrict(bytes("{\"name\":\"a\",\"age\":2}"), Flat.class));
        assertEquals(1, stub.invocations);
        // Coercions bind through the fallback exactly as without a reader.
        assertEquals(new Flat("42", 2),
                mapper.fromJsonStrict(bytes("{\"name\":42,\"age\":2}"), Flat.class));
        assertEquals(new Flat("a", 0),
                mapper.fromJsonStrict(bytes("{\"name\":\"a\"}"), Flat.class));
    }

    @Test
    void unregisteredTypesTakeTheGenericPath() {
        JqMapper mapper = JqMapper.create();
        assertEquals(new Flat("a", 2), mapper.fromJsonStrict(bytes("{\"name\":\"a\",\"age\":2}"), Flat.class));
    }

    @Test
    void emptyAndNullBindNull() {
        JqMapper mapper = JqMapper.create();
        assertNull(mapper.fromJsonStrict(new byte[0], Flat.class));
        assertNull(mapper.fromJsonStrict(bytes("null"), Flat.class));
        assertNull(mapper.fromJsonStrict(bytes("  null  "), Flat.class));
    }

    @Test
    void strictRejectsWhatLenientTolerates() {
        JqMapper mapper = JqMapper.create();
        String doc = "{\"name\":\"a\",\"age\":1} trailing";
        // Lenient fromJson keeps its accepted-input contract (unchanged).
        assertEquals(new Flat("a", 1), mapper.fromJson(doc, Flat.class));
        // Strict rejects trailing content, malformed JSON and duplicates.
        assertThrows(IllegalArgumentException.class, () -> mapper.fromJsonStrict(bytes(doc), Flat.class));
        assertThrows(IllegalArgumentException.class,
                () -> mapper.fromJsonStrict(bytes("{bad"), Flat.class));
        assertThrows(IllegalArgumentException.class, () -> mapper.fromJsonStrict(
                bytes("{\"name\":\"a\",\"name\":\"b\",\"age\":1}"), Flat.class));
        // Binding failures keep their error class and paths.
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJsonStrict(bytes("{\"name\":{},\"age\":1}"), Flat.class));
        assertEquals("$.name", e.path());
    }

    @Test
    void unknownKeysFollowMapperPolicy() {
        String doc = "{\"name\":\"a\",\"age\":1,\"bogus\":2}";
        assertEquals(new Flat("a", 1), JqMapper.create().fromJsonStrict(bytes(doc), Flat.class));
        JqMapper strict = JqMapper.builder().failOnUnknownProperties(true).build();
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> strict.fromJsonStrict(bytes(doc), Flat.class));
        assertTrue(e.getMessage().contains("\"bogus\""), e.getMessage());
    }

    @Test
    void readerErrorsKeepTheirClassification() {
        // A reader-thrown binding error stays a binding error (never a parse error).
        DirectReader<Flat> failing = new DirectReader<>() {
            @Override
            public Class<Flat> type() {
                return Flat.class;
            }

            @Override
            public Flat read(byte[] json, JqMapper mapper) {
                throw new JqMapperException("Missing required field \"name\" for Flat").prependPath("name");
            }
        };
        JqMapper mapper = JqMapper.builder().registerDirectReader(failing).build();
        JqMapperException e = assertThrows(JqMapperException.class,
                () -> mapper.fromJsonStrict(bytes("{\"age\":1}"), Flat.class));
        assertEquals("$.name", e.path());
        // A reader-detected syntax error stays a parse error.
        DirectReader<Flat> syntax = new DirectReader<>() {
            @Override
            public Class<Flat> type() {
                return Flat.class;
            }

            @Override
            public Flat read(byte[] json, JqMapper mapper) {
                JqValues.parseStrict(json);
                throw new DirectReadFallbackException();
            }
        };
        JqMapper mapper2 = JqMapper.builder().registerDirectReader(syntax).build();
        assertThrows(IllegalArgumentException.class,
                () -> mapper2.fromJsonStrict(bytes("{bad"), Flat.class));
    }
}
