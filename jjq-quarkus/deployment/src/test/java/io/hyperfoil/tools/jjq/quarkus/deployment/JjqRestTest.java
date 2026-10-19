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
import io.hyperfoil.tools.jjq.quarkus.test.JqValueResource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Extension integration test: RESTEasy Reactive provider registration
 * and the CDI-managed {@link JqMapper} singleton.
 */
class JjqRestTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Greeting.class, Greeting_JqMapping.class,
                    GreetingResource.class, JqValueResource.class));

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

    @Test
    void malformedBody_isBadRequest() {
        given()
                .contentType("application/json")
                .body("{bad")
                .when().post("/greeting")
                .then()
                .statusCode(400);
    }

    @Test
    void trailingContent_isBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3} trailing")
                .when().post("/greeting")
                .then()
                .statusCode(400);
    }

    @Test
    void duplicateKeys_isBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"a\",\"message\":\"b\",\"count\":3}")
                .when().post("/greeting")
                .then()
                .statusCode(400);
    }

    @Test
    void shapeMismatch_isBadRequest() {
        given()
                .contentType("application/json")
                .body("[1,2,3]")
                .when().post("/greeting")
                .then()
                .statusCode(400);
    }

    @Test
    void unknownFields_ignoredByDefault() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3,\"nickname\":\"Al\"}")
                .when().post("/greeting")
                .then()
                .statusCode(200)
                .body("message", equalTo("hello"))
                .body("count", equalTo(3));
    }

    @Test
    void nullBody_bindsNull() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/greeting")
                .then()
                .statusCode(204);
    }

    @Test
    void jvalueRoundTrip() {
        given()
                .contentType("application/json")
                .body("{\"a\":[1,2]}")
                .when().post("/jvalue")
                .then()
                .statusCode(200)
                .body("a", equalTo(java.util.List.of(1, 2)));
    }

    @Test
    void jvalueMalformed_isBadRequest() {
        given()
                .contentType("application/json")
                .body("{bad")
                .when().post("/jvalue")
                .then()
                .statusCode(400);
    }

    @Test
    void mappedListRoundTrip() {
        given()
                .contentType("application/json")
                .body("[{\"message\":\"hello\",\"count\":3},{\"message\":\"hi\",\"count\":1}]")
                .when().post("/greeting/batch")
                .then()
                .statusCode(200)
                .body("[0].message", equalTo("hello"))
                .body("[1].count", equalTo(1));
    }

    @Test
    void mappedListElementFailure_isBadRequest() {
        given()
                .contentType("application/json")
                .body("[{\"message\":\"hello\",\"count\":3},{\"message\":\"hi\",\"count\":{}}]")
                .when().post("/greeting/batch")
                .then()
                .statusCode(400);
    }

    @Test
    void mappedListNullBody_bindsNull() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/greeting/batch")
                .then()
                .statusCode(204);
    }
}
