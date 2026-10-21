package io.hyperfoil.tools.jjq.mapper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registry mechanics for build-time generated direct readers (issue #173):
 * registration, lookup, duplicate rejection and the untouched default path.
 * Read semantics of generated readers are pinned in the deployment module,
 * where the bytecode is emitted.
 */
class DirectReaderRegistryTest {

    record Flat(String name, int age) {}

    static final class StubReader implements DirectReader<Flat> {
        @Override
        public Class<Flat> type() {
            return Flat.class;
        }

        @Override
        public Flat read(byte[] json, JqMapper mapper) {
            throw new DirectReadFallbackException();
        }
    }

    @Test
    void unregisteredTypeHasNoReader() {
        assertNull(JqMapper.create().directReaderFor(Flat.class));
    }

    @Test
    void registeredReaderIsShared() {
        StubReader reader = new StubReader();
        JqMapper mapper = JqMapper.builder().registerDirectReader(reader).build();
        assertSame(reader, mapper.directReaderFor(Flat.class));
    }

    @Test
    void duplicateRegistrationFailsFast() {
        assertThrows(JqMapperException.class, () -> JqMapper.builder()
                .registerDirectReader(new StubReader())
                .registerDirectReader(new StubReader())
                .build());
    }

    @Test
    void fallbackSignalCarriesNoStackTrace() {
        DirectReadFallbackException signal = assertThrows(DirectReadFallbackException.class,
                () -> new StubReader().read(new byte[0], JqMapper.create()));
        assertEquals(0, signal.getStackTrace().length);
    }
}
