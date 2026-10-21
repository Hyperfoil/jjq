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
    public void directReaderIsRegisteredForEligibleRequests() {
        // Guards against silent no-ops: the suite below must exercise the
        // specialized route, not just agree with it through fallback.
        given()
                .when().get("/jjq/mapper/direct")
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

    @Test
    public void channelEmailRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"EMAIL\",\"to\":\"a@x.y\"}")
                .when().post("/jjq/channel")
                .then()
                .statusCode(200)
                .body("method", equalTo("EMAIL"))
                .body("to", equalTo("a@x.y"));
    }

    @Test
    public void channelSlackRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"SLACK\",\"channel\":\"#ops\"}")
                .when().post("/jjq/channel")
                .then()
                .statusCode(200)
                .body("method", equalTo("SLACK"))
                .body("channel", equalTo("#ops"));
    }

    @Test
    public void secretSlackAndGithubShareTokenBinding() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"SLACK\",\"token\":\"t1\"}")
                .when().post("/jjq/secret")
                .then()
                .statusCode(200)
                .body("method", equalTo("SLACK"))
                .body("token", equalTo("t1"));
        given()
                .contentType("application/json")
                .body("{\"method\":\"GITHUB_ISSUE\",\"token\":\"t2\"}")
                .when().post("/jjq/secret")
                .then()
                .statusCode(200)
                .body("method", equalTo("GITHUB_ISSUE"))
                .body("token", equalTo("t2"));
    }

    @Test
    public void secretUnmappedEmailIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"EMAIL\",\"token\":\"t\"}")
                .when().post("/jjq/secret")
                .then()
                .statusCode(400)
                .body(containsString("EMAIL"));
    }

    @Test
    public void secretMissingMethodIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"token\":\"t\"}")
                .when().post("/jjq/secret")
                .then()
                .statusCode(400);
    }

    @Test
    public void nodeSelectsByKeysWithDefault() {
        given()
                .contentType("application/json")
                .body("{\"windowLen\":50,\"maxPvalue\":0.001}")
                .when().post("/jjq/node")
                .then()
                .statusCode(200)
                .body("windowLen", equalTo(50));
        given()
                .contentType("application/json")
                .body("{\"windowSize\":20,\"deviations\":2.0}")
                .when().post("/jjq/node")
                .then()
                .statusCode(200)
                .body("windowSize", equalTo(20));
        given()
                .contentType("application/json")
                .body("{\"threshold\":0.5,\"window\":10}")
                .when().post("/jjq/node")
                .then()
                .statusCode(200)
                .body("threshold", equalTo(0.5F));
        given()
                .contentType("application/json")
                .body("{\"min\":1.5,\"max\":2.5}")
                .when().post("/jjq/node")
                .then()
                .statusCode(200)
                .body("min", equalTo(1.5F));
    }

    @Test
    public void nodeOverlappingKeysAreRejectedWhenStrict() {

        // Priority (first rule wins) is covered by the mapper unit tests on a
        // lenient mapper. This application enables failOnUnknownProperties, so
        // an input carrying two families' keys fails on the non-selected
        // family's keys instead of silently picking the first.
        given()
                .contentType("application/json")
                .body("{\"windowLen\":50,\"maxPvalue\":0.001,\"windowSize\":20,\"deviations\":2.0}")
                .when().post("/jjq/node")
                .then()
                .statusCode(400);
    }

    @Test
    public void recordListRoundTrips() {
        given()
                .contentType("application/json")
                .body("[{\"message\":\"hello\",\"count\":3},{\"message\":\"hi\",\"count\":1}]")
                .when().post("/jjq/records")
                .then()
                .statusCode(200)
                .body("[0].message", equalTo("hello"))
                .body("[1].count", equalTo(1));
    }

    @Test
    public void recordListElementFailureIsBadRequest() {
        given()
                .contentType("application/json")
                .body("[{\"message\":\"hello\",\"count\":3},{\"message\":\"hi\",\"count\":{}}]")
                .when().post("/jjq/records")
                .then()
                .statusCode(400)
                .body(containsString("[1]"));
    }

    @Test
    public void recordListNullIsNoContent() {
        given()
                .contentType("application/json")
                .body("null")
                .when().post("/jjq/records")
                .then()
                .statusCode(204);
    }

    @Test
    public void recordListEmptyBindsEmpty() {
        given()
                .contentType("application/json")
                .body("[]")
                .when().post("/jjq/records")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    public void channelListRoundTrips() {
        given()
                .contentType("application/json")
                .body("[{\"method\":\"EMAIL\",\"to\":\"a@x.y\"},{\"method\":\"SLACK\",\"channel\":\"#ops\"}]")
                .when().post("/jjq/channels")
                .then()
                .statusCode(200)
                .body("[0].method", equalTo("EMAIL"))
                .body("[1].channel", equalTo("#ops"));
    }

    @Test
    public void malformedJsonProblemAcceptRendersProblem() {
        given()
                .contentType("application/json")
                .accept("application/json, application/problem+json")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("about:blank"))
                .body("title", equalTo("Bad Request"))
                .body("status", equalTo(400))
                .body("detail", containsString("Invalid JSON request body"))
                .body("instance", equalTo("/jjq/record"));
    }

    @Test
    public void higherTextPreferenceKeepsPlainTextError() {
        given()
                .contentType("application/json")
                .accept("application/json, text/plain;q=0.9, application/problem+json;q=0.2")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .contentType("text/plain")
                .body(containsString("Invalid JSON request body"));
    }

    @Test
    public void problemDetailsPreserveCollectionElementPath() {
        given()
                .contentType("application/json")
                .accept("application/json, application/problem+json")
                .body("[{\"message\":\"ok\",\"count\":1},{\"message\":\"bad\",\"count\":{}}]")
                .when().post("/jjq/records")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400))
                .body("detail", containsString("$[1].count"))
                .body("instance", equalTo("/jjq/records"));
    }

    @Test
    public void jqValueParseFailureUsesProblemDetails() {
        given()
                .contentType("application/json")
                .accept("application/json, application/problem+json")
                .body("[1,")
                .when().post("/jjq/value")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("detail", containsString("Invalid JSON request body"));
    }

    @Test
    public void zeroQualityProblemAcceptKeepsPlainTextError() {
        given()
                .contentType("application/json")
                .accept("application/json, application/problem+json;q=0")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .contentType("text/plain");
    }

    @Test
    public void jsonOnlyAcceptKeepsPlainTextError() {
        given()
                .contentType("application/json")
                .accept("application/json")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(400)
                .contentType("text/plain");
    }

    @Test
    public void problemOnlyAcceptKeepsFrameworkNotAcceptableResponse() {
        // The endpoint produces application/json. JAX-RS rejects this Accept
        // before reading the body, so our exception mapper must not run.
        given()
                .contentType("application/json")
                .accept("application/problem+json")
                .body("{bad")
                .when().post("/jjq/record")
                .then()
                .statusCode(406);
    }

    @Test
    public void problemDetailsStayBoundedAndOmitQueryParameters() {
        String detail = given()
                .contentType("application/json")
                .accept("application/json, application/problem+json")
                .body("{} " + "x".repeat(5000))
                .when().post("/jjq/value?token=not-for-instance")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("instance", equalTo("/jjq/value"))
                .extract().jsonPath().getString("detail");
        assertTrue(detail.length() < 600, "Problem detail must retain the request-error size bound");
        assertFalse(detail.contains("x".repeat(1000)));
    }
}
