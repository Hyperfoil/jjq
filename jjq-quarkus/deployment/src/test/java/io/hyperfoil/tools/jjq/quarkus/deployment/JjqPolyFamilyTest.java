package io.hyperfoil.tools.jjq.quarkus.deployment;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.hyperfoil.tools.jjq.quarkus.test.KeyNode;
import io.hyperfoil.tools.jjq.quarkus.test.KeyNodeA;
import io.hyperfoil.tools.jjq.quarkus.test.KeyNodeA_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.KeyNodeB;
import io.hyperfoil.tools.jjq.quarkus.test.KeyNodeB_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.PolyChannel;
import io.hyperfoil.tools.jjq.quarkus.test.PolyEmail;
import io.hyperfoil.tools.jjq.quarkus.test.PolyEmail_JqMapping;
import io.hyperfoil.tools.jjq.quarkus.test.PolyMethod;
import io.hyperfoil.tools.jjq.quarkus.test.PolyResource;
import io.hyperfoil.tools.jjq.quarkus.test.PolySlack;
import io.hyperfoil.tools.jjq.quarkus.test.PolySlack_JqMapping;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Family-typed endpoints dispatch through the augmentation-resolved
 * dispatch tables (issue #151): discriminator-as-field and key-presence
 * round-trips over HTTP, plus unknown-discriminator rejection.
 */
class JjqPolyFamilyTest {

    @RegisterExtension
    static final QuarkusExtensionTest UNIT_TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    PolyMethod.class,
                    PolyChannel.class,
                    PolyEmail.class, PolyEmail_JqMapping.class,
                    PolySlack.class, PolySlack_JqMapping.class,
                    KeyNode.class,
                    KeyNodeA.class, KeyNodeA_JqMapping.class,
                    KeyNodeB.class, KeyNodeB_JqMapping.class,
                    PolyResource.class));

    @Test
    void channelEmailRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"EMAIL\",\"to\":\"a@x.y\"}")
                .when().post("/poly/channel")
                .then()
                .statusCode(200)
                .body("method", equalTo("EMAIL"))
                .body("to", equalTo("a@x.y"));
    }

    @Test
    void channelSlackRoundTrips() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"SLACK\",\"channel\":\"#ops\"}")
                .when().post("/poly/channel")
                .then()
                .statusCode(200)
                .body("method", equalTo("SLACK"))
                .body("channel", equalTo("#ops"));
    }

    @Test
    void channelUnknownMethodIsBadRequest() {
        given()
                .contentType("application/json")
                .body("{\"method\":\"WEBHOOK\",\"to\":\"a@x.y\"}")
                .when().post("/poly/channel")
                .then()
                .statusCode(400);
    }

    @Test
    void nodeSelectsByKeyWithDefault() {
        given()
                .contentType("application/json")
                .body("{\"a\":3}")
                .when().post("/poly/node")
                .then()
                .statusCode(200)
                .body("a", equalTo(3));
        given()
                .contentType("application/json")
                .body("{\"b\":4}")
                .when().post("/poly/node")
                .then()
                .statusCode(200)
                .body("b", equalTo(4));
    }
}
