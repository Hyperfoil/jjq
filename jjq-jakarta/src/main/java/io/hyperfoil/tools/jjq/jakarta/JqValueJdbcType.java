package io.hyperfoil.tools.jjq.jakarta;

import io.hyperfoil.tools.jjq.value.JqValue;
import io.hyperfoil.tools.jjq.value.JqValues;
import org.hibernate.type.descriptor.ValueBinder;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicBinder;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Hibernate {@link JdbcType} that stores {@link JqValue} as binary data using
 * direct {@code byte[]} JDBC I/O.
 *
 * <p>This bypasses the String-based FormatMapper path entirely:</p>
 * <ul>
 *   <li><b>Write:</b> {@link JqValues#serializeToBytes(JqValue)} → {@code PreparedStatement.setBytes()}</li>
 *   <li><b>Read:</b> {@code ResultSet.getBytes()} → {@link JqValues#parse(byte[])}</li>
 * </ul>
 *
 * <p>For documents parsed from {@code byte[]}, deferred string values are
 * copied as raw bytes during serialization — zero String construction,
 * zero UTF-8 re-encoding. This makes the parse→persist round-trip optimal
 * for pass-through workloads.</p>
 *
 * <p>Compatible with PostgreSQL BYTEA columns, SQLite BLOB, and any database
 * that supports {@code setBytes()}/{@code getBytes()} on the JDBC driver.</p>
 *
 * <p>Usage in entity classes:</p>
 * <pre>{@code
 * @Column(columnDefinition = "BYTEA")
 * @JdbcType(JqValueJdbcType.class)
 * @JavaType(JqValueJavaType.class)
 * @Mutability(Immutability.class)
 * public JqValue data;
 * }</pre>
 *
 * @see JqValueJavaType
 * @see JqValueFormatMapper
 */
public class JqValueJdbcType implements JdbcType {

    public static final JqValueJdbcType INSTANCE = new JqValueJdbcType();

    /** Creates a new JqValueJdbcType. */
    public JqValueJdbcType() {}

    @Override
    public int getJdbcTypeCode() {
        return Types.VARBINARY;
    }

    @Override
    public String getFriendlyName() {
        return "JqValueBinary";
    }

    @Override
    public String toString() {
        return "JqValueJdbcType(VARBINARY)";
    }

    @Override
    public <X> ValueBinder<X> getBinder(JavaType<X> javaType) {
        return new BasicBinder<>(javaType, this) {
            @Override
            protected void doBind(PreparedStatement st, X value, int index, WrapperOptions options)
                    throws SQLException {
                byte[] bytes = javaType.unwrap(value, byte[].class, options);
                st.setBytes(index, bytes);
            }

            @Override
            protected void doBind(CallableStatement st, X value, String name, WrapperOptions options)
                    throws SQLException {
                byte[] bytes = javaType.unwrap(value, byte[].class, options);
                st.setBytes(name, bytes);
            }
        };
    }

    @Override
    public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
        return new BasicExtractor<>(javaType, this) {
            @Override
            protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options)
                    throws SQLException {
                byte[] bytes = rs.getBytes(paramIndex);
                if (bytes == null) return null;
                return javaType.wrap(bytes, options);
            }

            @Override
            protected X doExtract(CallableStatement statement, int index, WrapperOptions options)
                    throws SQLException {
                byte[] bytes = statement.getBytes(index);
                if (bytes == null) return null;
                return javaType.wrap(bytes, options);
            }

            @Override
            protected X doExtract(CallableStatement statement, String name, WrapperOptions options)
                    throws SQLException {
                byte[] bytes = statement.getBytes(name);
                if (bytes == null) return null;
                return javaType.wrap(bytes, options);
            }
        };
    }
}
