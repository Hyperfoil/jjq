package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.DirectPojo;
import io.hyperfoil.tools.jjq.quarkus.test.DirectPojoResource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Augmentation wiring for flat-POJO direct readers (issue #4): JavaBean
 * models (openapi-generator shape — private fields, public setters,
 * public no-arg constructor) get a registered reader that constructs
 * through the no-arg constructor and per-field writes after full
 * validation, and REST behavior is unchanged.
 */
class DirectReaderPojoBeanTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(DirectPojo.class,
                    DirectPojoResource.class))
            .overrideConfigKey("quarkus.jjq.direct-readers", "true");

    @Inject
    JqMapper mapper;

    @Test
    void pojoRequestTypeHasRegisteredReader() {
        DirectReader<DirectPojo> reader = mapper.directReaderFor(DirectPojo.class);
        assertNotNull(reader);
        assertEquals(DirectPojo.class, reader.type());
    }

    @Test
    void registeredReaderConstructsThroughSetters() {
        DirectReader<DirectPojo> reader = mapper.directReaderFor(DirectPojo.class);
        DirectPojo expected = new DirectPojo();
        expected.setName("ann");
        expected.setAge(3);
        expected.setScore(99L);
        assertEquals(expected, reader.read(
                "{\"name\":\"ann\",\"age\":3,\"score\":99}".getBytes(StandardCharsets.UTF_8), mapper));
        // Absent boxed field binds null exactly like the tree path.
        DirectPojo partial = new DirectPojo();
        partial.setName("ann");
        partial.setAge(3);
        assertEquals(partial, reader.read(
                "{\"name\":\"ann\",\"age\":3}".getBytes(StandardCharsets.UTF_8), mapper));
    }

    @Test
    void restRoundTripIsUnchanged() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"bob\",\"age\":7,\"score\":null}")
                .when().post("/direct-pojo/echo")
                .then()
                .statusCode(200)
                .body("name", equalTo("bob"))
                .body("age", equalTo(7))
                .body("score", equalTo(null));
    }
}
