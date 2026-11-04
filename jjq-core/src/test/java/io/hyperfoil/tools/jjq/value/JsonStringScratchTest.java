package io.hyperfoil.tools.jjq.value;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Workspace admission is an intentional performance contract for #207. */
class JsonStringScratchTest {
    @SuppressWarnings("unchecked")
    private static ThreadLocal<char[]> workspace() throws Exception {
        // Inspect our own workspace, not String internals. Reading a default
        // ThreadLocal can create its map entry, so allocation is measured by
        // the separate fresh-thread diagnostic without this inspection.
        Field field = JqString.class.getDeclaredField("SCAN_SCRATCH");
        field.setAccessible(true);
        return (ThreadLocal<char[]>) field.get(null);
    }

    @Test
    void tinyAndFirstEligibleDecodesDoNotAllocateACharWorkspace() throws Exception {
        ThreadLocal<char[]> pool = workspace();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                assertEquals("tiny\n", decode("tiny\\n"));
                assertNull(pool.get(), "tiny decoding must not activate char storage");
                String raw = "a".repeat(200) + "\\t";
                assertEquals("a".repeat(200) + "\t", decode(raw));
                assertEquals(0, pool.get().length, "first eligible decode records admission only");
                assertEquals("a".repeat(200) + "\t", decode(raw));
                assertTrue(pool.get().length >= raw.length());
                assertTrue(pool.get().length <= 8192);
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError("Fresh-thread admission", failure.get());
    }

    @Test
    void scanningAndDecodingShareSizeBoundedStorage() throws Exception {
        ThreadLocal<char[]> pool = workspace();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                assertFalse(JqString.needsEscaping("x".repeat(600)));
                assertEquals(256, pool.get().length, "scan needs one chunk, not 8K chars");
                String raw = "\u4E2D".repeat(600) + "\\n";
                assertEquals("\u4E2D".repeat(600) + "\n", decode(raw));
                char[] borrowed = pool.get();
                assertTrue(borrowed.length >= raw.length());
                assertTrue(borrowed.length <= 8192);
                assertTrue(JqString.needsEscaping("x".repeat(600) + "\""));
                assertSame(borrowed, pool.get());
                String huge = "x".repeat(9000) + "\\t";
                assertEquals("x".repeat(9000) + "\t", decode(huge));
                assertSame(borrowed, pool.get(), "oversized decoding must not grow retained storage");
                String longer = raw + "z";
                assertEquals("\u4E2D".repeat(600) + "\nz", decode(longer));
                char[] grown = pool.get();
                assertNotSame(borrowed, grown);
                assertTrue(grown.length <= 8192);
                for (int i = 1; i <= 16; i++) {
                    assertEquals("\u4E2D".repeat(600) + "\nz" + "x".repeat(i), decode(longer + "x".repeat(i)));
                    assertSame(grown, pool.get(), "incremental sizes should reuse growth headroom");
                }
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        thread.start();
        thread.join();
        if (failure.get() != null) throw new AssertionError("Shared workspace bounds", failure.get());
    }

    private static String decode(String raw) {
        return JqString.deferred(raw, 0, raw.length(), true).stringValue();
    }
}
