package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JjqMappingNameTest {

    @Test
    void topLevelRecord() {
        assertEquals("com.foo.User_JqMapping",
                JjqProcessor.mappingClassNameFor("com.foo.User"));
    }

    @Test
    void nestedRecordUsesUnderscore() {
        assertEquals("com.foo.Outer_Inner_JqMapping",
                JjqProcessor.mappingClassNameFor("com.foo.Outer$Inner"));
    }

    @Test
    void defaultPackage() {
        assertEquals("User_JqMapping",
                JjqProcessor.mappingClassNameFor("User"));
    }
}
