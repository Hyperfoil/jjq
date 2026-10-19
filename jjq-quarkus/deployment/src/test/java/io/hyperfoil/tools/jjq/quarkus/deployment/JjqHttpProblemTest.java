package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.quarkus.test.Greeting;
import io.hyperfoil.tools.jjq.quarkus.test.GreetingResource;
import io.hyperfoil.tools.jjq.quarkus.test.Greeting_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.JqValueResource;
import io.quarkus.builder.Version;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;

/** http-problem requires a JSON provider; exercise JSON-B coexistence without Jackson. */
class JjqHttpProblemTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .setForcedDependencies(List.of(
                    Dependency.of("io.quarkiverse.httpproblem", "quarkus-http-problem", "3.38.2"),
                    Dependency.of("io.quarkiverse.httpproblem", "quarkus-http-problem-deployment", "3.38.2"),
                    Dependency.of("io.quarkus", "quarkus-rest-jsonb", Version.getVersion()),
                    Dependency.of("io.quarkus", "quarkus-rest-jsonb-deployment", Version.getVersion())))
            .withApplicationRoot(jar -> jar.addClasses(Greeting.class, Greeting_JqMapping.class,
                    GreetingResource.class, JqValueResource.class));

    @Test
    void malformedBodyUsesProblemDetailsWithoutExplicitOptIn() {
        given()
                .contentType("application/json")
                .accept("application/json")
                .body("{bad")
                .when().post("/greeting?token=not-for-instance")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("type", equalTo("about:blank"))
                .body("title", equalTo("Bad Request"))
                .body("status", equalTo(400))
                .body("detail", containsString("Invalid JSON request body"))
                .body("instance", equalTo("/greeting"));
    }

    @Test
    void bindingFailureUsesProblemDetailsWithItsPath() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":{}}")
                .when().post("/greeting")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("detail", containsString("$.count"));
    }

    @Test
    void jqValueParseFailureUsesProblemDetails() {
        given()
                .contentType("application/json")
                .body("[1,")
                .when().post("/jvalue")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("status", equalTo(400));
    }

    @Test
    void successfulResponseRemainsApplicationJson() {
        given()
                .contentType("application/json")
                .body("{\"message\":\"hello\",\"count\":3}")
                .when().post("/greeting")
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("message", equalTo("hello"));
    }
}
