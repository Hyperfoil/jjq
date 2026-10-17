package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting;
import io.hyperfoil.tools.jjq.quarkus.test.GreetingResource;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting_JqMapping;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Extension integration test: {@code quarkus.jjq.fail-on-unknown-properties}
 * is applied once when the CDI mapper is created.
 */
class JjqStrictUnknownPropertiesTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeting.class, Greeting_JqMapping.class,
                    GreetingResource.class))
            .overrideConfigKey("quarkus.jjq.fail-on-unknown-properties", "true");

    @Inject
    JqMapper mapper;

    @Test
    void mapperCarriesStrictFlag() {
        org.junit.jupiter.api.Assertions.assertTrue(mapper.isFailOnUnknownProperties());
    }

    @Test
    void unknownField_isBadRequestWhenStrict() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3,\"nickname\":\"Al\"}")
                .when().post("/greeting")
                .then()
                .statusCode(400);
    }

    @Test
    void knownFields_stillBindWhenStrict() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3}")
                .when().post("/greeting")
                .then()
                .statusCode(200)
                .body("message", equalTo("hello"))
                .body("count", equalTo(3));
    }
}
