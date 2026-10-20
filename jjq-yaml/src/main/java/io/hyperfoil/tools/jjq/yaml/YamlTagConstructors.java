package io.hyperfoil.tools.jjq.yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable registry of {@link YamlConstructor} handlers keyed by
 * normalized tag name (issue #165).
 *
 * <p>Registration keys normalize the same way node tags do, so
 * {@code with("!foo", ...)} and {@code with("foo", ...)} register the
 * same handler. {@link #EMPTY} (no handlers) reproduces historical
 * parsing exactly; every {@code JqYaml.parse} overload without
 * constructors delegates with {@link #EMPTY}.</p>
 *
 * @see JqYaml#parse(String, YamlTagConstructors)
 */
public final class YamlTagConstructors {

    /** No custom constructors: historical tag behavior. */
    public static final YamlTagConstructors EMPTY = new YamlTagConstructors(Map.of());

    private final Map<String, YamlConstructor> handlers;

    private YamlTagConstructors(Map<String, YamlConstructor> handlers) {
        this.handlers = handlers;
    }

    /**
     * Return a registry with {@code constructor} handling {@code tag}.
     * Re-registering a tag replaces its handler.
     *
     * @param tag         the tag name ({@code !} prefix optional)
     * @param constructor the handler (must not be null)
     * @return a new registry holding all previous handlers plus this one
     */
    public YamlTagConstructors with(String tag, YamlConstructor constructor) {
        if (constructor == null) throw new NullPointerException("constructor must not be null");
        Map<String, YamlConstructor> next = new LinkedHashMap<>(handlers);
        next.put(normalize(tag), constructor);
        return new YamlTagConstructors(Collections.unmodifiableMap(next));
    }

    /** True when no handlers are registered (the parsing fast path). */
    public boolean isEmpty() {
        return handlers.isEmpty();
    }

    /** The handler for a normalized tag, or null. */
    YamlConstructor find(String normalizedTag) {
        return handlers.get(normalizedTag);
    }

    /** Normalization shared with the parser's node-tag handling. */
    static String normalize(String token) {
        return YamlParser.normalizeTag(token);
    }
}
