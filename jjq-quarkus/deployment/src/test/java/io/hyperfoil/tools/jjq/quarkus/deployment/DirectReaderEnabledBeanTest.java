package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.DirectReadFallbackException;
import io.hyperfoil.tools.jjq.mapper.DirectReader;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.DirectEligible;
import io.hyperfoil.tools.jjq.quarkus.test.DirectEligible_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.DirectNested;
import io.hyperfoil.tools.jjq.quarkus.test.DirectNested_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.DirectReaderResource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Augmentation wiring for direct readers with the opt-in flag enabled
 * (issue #173): eligible request types get a registered reader that
 * constructs through the specialized route, ineligible types keep the
 * existing path, and REST behavior is unchanged (dispatch is issue #174).
 */
class DirectReaderEnabledBeanTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(DirectEligible.class,
                    DirectEligible_JqMapping.class,
                    DirectNested.class,
                    DirectNested_JqMapping.class,
                    DirectReaderResource.class))
            .overrideConfigKey("quarkus.jjq.direct-readers", "true");

    @Inject
    JqMapper mapper;

    @Test
    void eligibleRequestTypeHasRegisteredReader() {
        DirectReader<DirectEligible> reader = mapper.directReaderFor(DirectEligible.class);
        assertNotNull(reader);
        assertEquals(DirectEligible.class, reader.type());
    }

    @Test
    void registeredReaderConstructsThroughTheSpecializedRoute() {
        DirectReader<DirectEligible> reader = mapper.directReaderFor(DirectEligible.class);
        assertEquals(new DirectEligible("ann", 3), reader.read(
                "{\"name\":\"ann\",\"age\":3}".getBytes(StandardCharsets.UTF_8), mapper));
        assertEquals(new DirectEligible("ann", 3), reader.read(
                "{\"age\":3,\"name\":\"ann\"}".getBytes(StandardCharsets.UTF_8), mapper));
    }

    @Test
    void registeredReaderFallsBackOnCoercion() {
        DirectReader<DirectEligible> reader = mapper.directReaderFor(DirectEligible.class);
        try {
            reader.read("{\"name\":42,\"age\":3}".getBytes(StandardCharsets.UTF_8), mapper);
            fail("expected fallback");
        } catch (DirectReadFallbackException e) {
            // The existing path then binds the coercion exactly as today.
            assertEquals(new DirectEligible("42", 3), mapper.fromJqValue(
                    io.hyperfoil.tools.jjq.value.JqValues.parseStrict(
                            "{\"name\":42,\"age\":3}".getBytes(StandardCharsets.UTF_8)),
                    DirectEligible.class));
        }
    }

    @Test
    void nestedRequestTypeKeepsTheExistingPath() {
        assertNull(mapper.directReaderFor(DirectNested.class));
    }

    @Test
    void restBehaviorIsUnchanged() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"ann\",\"age\":3}")
                .when().post("/direct/eligible")
                .then()
                .statusCode(200)
                .body("name", equalTo("ann"))
                .body("age", equalTo(3));
        given()
                .contentType("application/json")
                .body("{\"inner\":{\"name\":\"ann\",\"age\":3},\"note\":\"hi\"}")
                .when().post("/direct/nested")
                .then()
                .statusCode(200)
                .body("note", equalTo("hi"));
    }
}
