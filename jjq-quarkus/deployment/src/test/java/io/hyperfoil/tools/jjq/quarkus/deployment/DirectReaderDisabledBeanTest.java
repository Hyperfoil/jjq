package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.DirectEligible;
import io.hyperfoil.tools.jjq.quarkus.test.DirectEligible_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.DirectNested;
import io.hyperfoil.tools.jjq.quarkus.test.DirectNested_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.DirectReaderResource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Default behavior with direct readers disabled (issue #173): no reader
 * classes affect the application, and REST binding is exactly today's path.
 */
class DirectReaderDisabledBeanTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(DirectEligible.class,
                    DirectEligible_JqMapping.class,
                    DirectNested.class,
                    DirectNested_JqMapping.class,
                    DirectReaderResource.class));

    @Inject
    JqMapper mapper;

    @Test
    void noReadersRegisteredByDefault() {
        assertNull(mapper.directReaderFor(DirectEligible.class));
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
    }
}
