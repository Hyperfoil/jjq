package io.hyperfoil.tools.jjq.quarkus.it;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/** HTTP-only assertions shared with the packaged JVM/native test. */
@QuarkusTest
public class JjqResourceTest {

    @Test
    public void mappedRecordRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3}")
                .when().post("/jjq/record")
                .then()
                .statusCode(200)
                .body("message", equalTo("hello"))
                .body("count", equalTo(3));
    }

    @Test
    public void enumBridgeRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"display-name\":\"Alice\",\"auth\":\"api-key\"}")
                .when().post("/jjq/jackson")
                .then()
                .statusCode(200)
                .body("display-name", equalTo("Alice"))
                .body("auth", equalTo("api-key"));
    }

    @Test
    public void jsonbPropertyRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"repo-path\":\"/work/jjq\"}")
                .when().post("/jjq/jsonb")
                .then()
                .statusCode(200)
                .body("repo-path", equalTo("/work/jjq"));
    }

    @Test
    public void jqValueRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"nested\":{\"active\":true},\"values\":[1,2,3]}")
                .when().post("/jjq/value")
                .then()
                .statusCode(200)
                .body("nested.active", equalTo(true))
                .body("values", equalTo(java.util.List.of(1, 2, 3)));
    }

    @Test
    public void injectedMapperBindsRequests() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"injected mapper\",\"count\":2}")
                .when().post("/jjq/mapper")
                .then()
                .statusCode(200)
                .body("message", equalTo("injected mapper"))
                .body("count", equalTo(2));
    }

    @Test
    public void mapperConfigurationIsApplied() {
        given()
                .when().get("/jjq/mapper/strict")
                .then()
                .statusCode(200)
                .body(equalTo("true"));
    }

    @Test
    public void invalidCreatorInputIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"display-name\":\"Alice\",\"auth\":\"unsupported\"}")
                .when().post("/jjq/jackson")
                .then()
                .statusCode(400);
    }

    @Test
    public void malformedJsonIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .contentType("text/plain")
                .body(containsString("Invalid JSON request body"));
    }

    @Test
    public void trailingContentIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3} {}")
                .when().post("/jjq/record")
                .then()
                .statusCode(400);
    }

    @Test
    public void duplicateKeysAreBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"message\":\"again\",\"count\":3}")
                .when().post("/jjq/record")
                .then()
                .statusCode(400);
    }

    @Test
    public void malformedJqValueIsBadRequest() {
        given()
                .contentType("application/json")
                .body("[1,2")
                .when().post("/jjq/value")
                .then()
                .statusCode(400);
    }

    @Test
    public void invalidUtf8IsBadRequest() {
        given()
                .contentType("application/json")
                .body(new byte[]{'"', (byte) 0xC3, '(', '"'})
                .when().post("/jjq/value")
                .then()
                .statusCode(400);
    }

    @Test
    public void bindingFailureIncludesPropertyPath() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":{}}")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .body(containsString("$.count"));
    }

    @Test
    public void unknownPropertiesAreRejectedWhenConfigured() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3,\"extra\":true}")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .body(containsString("extra"));
    }

    @Test
    public void nullRecordIsNoContent() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/jjq/record")
                .then()
                .statusCode(204);
    }

    @Test
    public void emptyRecordIsNoContent() {
        given()
                .contentType("application/json")
                .body(new byte[0])
                .when().post("/jjq/record")
                .then()
                .statusCode(204);
    }

    @Test
    public void jsonNullIsPreservedForJqValue() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/jjq/value")
                .then()
                .statusCode(200)
                .body(equalTo("null"));
    }

    @Test
    public void errorDetailsAreBounded() {
        String response = given()
                .contentType("application/json")
                .body("{} " + "x".repeat(5000))
                .when().post("/jjq/value")
                .then()
                .statusCode(400)
                .extract().asString();
        assertTrue(response.length() < 700, "Error response must not grow with the request body");
        assertFalse(response.contains("x".repeat(1000)));
    }

    @Test
    public void unsupportedMediaTypeRemainsUnsupported() {
        given()
                .contentType("text/plain")
                .body("hello")
                .when().post("/jjq/record")
                .then()
                .statusCode(415);
    }

    @Test
    public void oversizedRequestRemainsPayloadTooLarge() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"" + "x".repeat(40 * 1024) + "\",\"count\":1}")
                .when().post("/jjq/record")
                .then()
                .statusCode(413);
    }

    @Test
    public void applicationFailureRemainsServerError() {
        given()
                .when().get("/jjq/failure")
                .then()
                .statusCode(500);
    }
}
