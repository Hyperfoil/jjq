package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.AbstractClassJavaType;
import org.hibernate.type.descriptor.java.ImmutableMutabilityPlan;

import java.nio.charset.StandardCharsets;

/**
 * Hibernate {@link org.hibernate.type.descriptor.java.JavaType} descriptor for {@link JqValue}.
 *
 * <p>Tells Hibernate how to handle JqValue instances: equality, hashing, String conversion,
 * and conversion to/from JDBC types ({@code byte[]}, {@code String}).</p>
 *
 * <p>Uses {@link ImmutableMutabilityPlan} — JqValue instances are immutable, so Hibernate
 * skips deep-copy for second-level cache and dirty checking.</p>
 *
 * <p>Usage in entity classes:</p>
 * <pre>{@code
 * @Column(columnDefinition = "BYTEA")
 * @JdbcType(JqValueJdbcType.class)
 * @JavaType(JqValueJavaType.class)
 * @Mutability(Immutability.class)
 * public JqValue data;
 * }</pre>
 */
public class JqValueJavaType extends AbstractClassJavaType<JqValue> {

    public static final JqValueJavaType INSTANCE = new JqValueJavaType();

    /** Creates a new JqValueJavaType. */
    @SuppressWarnings("unchecked")
    public JqValueJavaType() {
        super(JqValue.class, ImmutableMutabilityPlan.instance());
    }

    @Override
    public JqValue fromString(CharSequence string) {
        if (string == null) return null;
        return JqValues.parse(string.toString());
    }

    @Override
    public String toString(JqValue value) {
        if (value == null) return null;
        return value.toJsonString();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <X> X unwrap(JqValue value, Class<X> type, WrapperOptions options) {
        if (value == null) return null;
        if (JqValue.class.isAssignableFrom(type)) return (X) value;
        if (byte[].class.isAssignableFrom(type)) return (X) JqValues.serializeToBytes(value);
        if (String.class.isAssignableFrom(type)) return (X) value.toJsonString();
        throw unknownUnwrap(type);
    }

    @Override
    public <X> JqValue wrap(X value, WrapperOptions options) {
        if (value == null) return null;
        if (value instanceof JqValue jv) return jv;
        if (value instanceof byte[] bytes) return JqValues.parse(bytes);
        if (value instanceof String s) return JqValues.parse(s);
        throw unknownWrap(value.getClass());
    }
}
