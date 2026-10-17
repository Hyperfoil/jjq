package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.hyperfoil.tools.jjq.quarkus.test.AuthHolder;
import io.hyperfoil.tools.jjq.quarkus.test.AuthHolder_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.BothRenamed;
import io.hyperfoil.tools.jjq.quarkus.test.BridgedResource;
import io.hyperfoil.tools.jjq.quarkus.test.JacksonRenamed;
import io.hyperfoil.tools.jjq.quarkus.test.JacksonRenamed_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.JsonbRenamed;
import io.hyperfoil.tools.jjq.quarkus.test.JsonbRenamed_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.NativeWins;
import io.hyperfoil.tools.jjq.quarkus.test.NativeWins_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.PlainJackson;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;

/**
 * Bridge-backed bindings behave identically through the injected CDI mapper
 * whether the mapping is generated or reflective: the extension registers
 * the {@code jjq-mapper-jackson} and {@code jjq-mapper-jsonb} bridges
 * whenever those modules are application dependencies.
 */
class JjqBridgeTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    JacksonRenamed.class, JacksonRenamed_JqMapping.class,
                    AuthHolder.class, AuthHolder_JqMapping.class,
                    JsonbRenamed.class, JsonbRenamed_JqMapping.class,
                    NativeWins.class, NativeWins_JqMapping.class,
                    PlainJackson.class, BothRenamed.class,
                    BridgedResource.class));

    @Inject
    JqMapper mapper;

    @Test
    void jacksonRenameBindsThroughHttp() {
        given()
                .contentType("application/json")
                .body("{\"display-name\":\"x\"}")
                .when().post("/bridged/jackson")
                .then()
                .statusCode(200)
                .body("display-name", equalTo("x"));
    }

    @Test
    void jacksonRenameBindsThroughReflection() {
        PlainJackson value = mapper.fromJson("{\"display-name\":\"x\"}", PlainJackson.class);
        assertEquals("x", value.displayName());
    }

    @Test
    void enumWireFormRoundTripsThroughHttp() {
        given()
                .contentType("application/json")
                .body("{\"name\":\"t\",\"auth\":\"api-key\"}")
                .when().post("/bridged/auth")
                .then()
                .statusCode(200)
                .body("auth", equalTo("api-key"));
    }

    @Test
    void jsonbRenameBindsThroughHttp() {
        given()
                .contentType("application/json")
                .body("{\"repo-paths\":\"r\"}")
                .when().post("/bridged/jsonb")
                .then()
                .statusCode(200)
                .body("repo-paths", equalTo("r"));
    }

    @Test
    void jacksonWinsConflictingRenames() {
        BothRenamed value = mapper.fromJson("{\"jackson-name\":\"x\"}", BothRenamed.class);
        assertEquals("x", value.value());
        assertNull(mapper.fromJson("{\"jsonb-name\":\"y\"}", BothRenamed.class).value());
    }

    @Test
    void nativeRenameWinsOverBridge() {
        NativeWins value = mapper.fromJson("{\"native-name\":\"x\"}", NativeWins.class);
        assertEquals("x", value.value());
    }
}
