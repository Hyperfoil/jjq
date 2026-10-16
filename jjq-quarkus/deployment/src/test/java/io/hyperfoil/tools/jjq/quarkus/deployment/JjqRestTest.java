package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.get;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting;
import io.hyperfoil.tools.jjq.quarkus.test.GreetingResource;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting_JqMapping;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Extension integration test: RESTEasy Reactive provider registration
 * and the CDI-managed {@link JqMapper} singleton.
 */
class JjqRestTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeting.class, Greeting_JqMapping.class, GreetingResource.class));

    @Inject
    JqMapper mapper;

    @Test
    void mappedRecordRoundTrip() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3}")
                .when().post("/greeting")
                .then()
                .statusCode(200)
                .body("message", equalTo("hello"))
                .body("count", equalTo(3));
    }

    @Test
    void mapperBeanIsSingletonSharedWithResources() {
        int viaRest = Integer.parseInt(get("/greeting/mapper-id").asString());
        assertEquals(System.identityHashCode(mapper), viaRest);
    }

    @Test
    void injectedMapperHandlesRecord() {
        Greeting greeting = mapper.fromJson("{\"message\":\"hi\",\"count\":1}", Greeting.class);
        assertEquals(new Greeting("hi", 1), greeting);
        assertEquals("{\"message\":\"hi\",\"count\":1}", mapper.toJson(greeting));
    }
}
