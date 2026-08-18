package io.hyperfoil.tools.jjq.jakarta;

import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JavaType;
import org.hibernate.annotations.Mutability;
import org.hibernate.type.descriptor.java.Immutability;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;

import static org.junit.jupiter.api.Assertions.*;

class JqValueColumnTest {

    @Test
    void hasJdbcTypeAnnotation() {
        JdbcType jdbcType = JqValueColumn.class.getAnnotation(JdbcType.class);
        assertNotNull(jdbcType);
        assertEquals(JqValueJdbcType.class, jdbcType.value());
    }

    @Test
    void hasJavaTypeAnnotation() {
        JavaType javaType = JqValueColumn.class.getAnnotation(JavaType.class);
        assertNotNull(javaType);
        assertEquals(JqValueJavaType.class, javaType.value());
    }

    @Test
    void hasMutabilityAnnotation() {
        Mutability mutability = JqValueColumn.class.getAnnotation(Mutability.class);
        assertNotNull(mutability);
        assertEquals(Immutability.class, mutability.value());
    }

    @Test
    void allThreeMetaAnnotationsPresent() {
        Annotation[] annotations = JqValueColumn.class.getAnnotations();
        // Should have at least JdbcType, JavaType, Mutability, plus the meta-annotations
        boolean hasJdbc = false, hasJava = false, hasMut = false;
        for (Annotation a : annotations) {
            if (a instanceof JdbcType) hasJdbc = true;
            if (a instanceof JavaType) hasJava = true;
            if (a instanceof Mutability) hasMut = true;
        }
        assertTrue(hasJdbc, "Missing @JdbcType");
        assertTrue(hasJava, "Missing @JavaType");
        assertTrue(hasMut, "Missing @Mutability");
    }
}
