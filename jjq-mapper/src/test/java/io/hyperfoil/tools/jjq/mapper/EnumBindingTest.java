package io.hyperfoil.tools.jjq.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;

/**
 * Compiled enum bindings take precedence over reflective handler
 * resolution; unregistered enums keep reflective behavior untouched.
 * The test binding below replicates plain name binding while counting
 * invocations — the count (not invented semantics) proves routing.
 */
class EnumBindingTest {

    enum Color {
        RED, GREEN
    }

    enum Renamed {
        @JqName("set-if-unset")
        SET_IF_UNSET,
        PLAIN
    }

    static final class CountingEnumBinding implements EnumBinding<Color> {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public Class<Color> type() {
            return Color.class;
        }

        @Override
        public Color read(JqValue value, JqMapper mapper) {
            reads.incrementAndGet();
            if (value == null || value instanceof io.hyperfoil.tools.jjq.value.JqNull) return null;
            return Enum.valueOf(Color.class, ((JqString) value).stringValue());
        }

        @Override
        public JqValue write(Color value, JqMapper mapper) {
            writes.incrementAndGet();
            return JqString.of(value.name());
        }
    }

    @Test
    void compiledBindingServesReadsAndWrites() {
        CountingEnumBinding binding = new CountingEnumBinding();
        JqMapper mapper = JqMapper.builder().registerEnum(binding).build();

        assertSame(Color.RED, mapper.fromJqValue(JqValues.parse("\"RED\""), Color.class));
        assertEquals(1, binding.reads.get());
        assertEquals(JqString.of("GREEN"), mapper.toJqValue(Color.GREEN));
        assertEquals(1, binding.writes.get());
    }

    @Test
    void compiledBindingHonorsNullContract() {
        CountingEnumBinding binding = new CountingEnumBinding();
        JqMapper mapper = JqMapper.builder().registerEnum(binding).build();

        // Null short-circuits before bindings by design (lenient-null
        // invariant holds regardless of binding quality).
        assertNull(mapper.fromJqValue(JqValues.parse("null"), Color.class));
        assertEquals(0, binding.reads.get());
        // Direct binding use still honors the null contract.
        assertNull(binding.read(null, mapper));
    }

    @Test
    void unregisteredEnumsKeepReflectiveBehavior() {
        JqMapper mapper = JqMapper.builder().build();

        assertSame(Renamed.SET_IF_UNSET, mapper.fromJqValue(JqValues.parse("\"set-if-unset\""), Renamed.class));
        assertEquals(JqString.of("set-if-unset"), mapper.toJqValue(Renamed.SET_IF_UNSET));
        assertTrue(mapper.fromJqValue(JqValues.parse("\"PLAIN\""), Renamed.class) == Renamed.PLAIN);
    }
}
