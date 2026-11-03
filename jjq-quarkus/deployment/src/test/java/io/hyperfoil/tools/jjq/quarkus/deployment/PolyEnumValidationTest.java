package io.hyperfoil.tools.jjq.quarkus.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.jandex.Index;
import org.junit.jupiter.api.Test;

import io.hyperfoil.tools.jjq.mapper.JqName;
import io.hyperfoil.tools.jjq.mapper.JqSubtype;
import io.hyperfoil.tools.jjq.mapper.JqType;

/**
 * Build-time enum validation of discriminator mappings (issue #201):
 * wire values outside the discriminator enum fail here instead of as
 * runtime unknown-value failures. Pure index checks, no reflection.
 */
class PolyEnumValidationTest {

    enum Method {
        EMAIL,
        SMS
    }

    @JqType(discriminator = "method", existingProperty = true, mappings = {
            @JqSubtype(value = "EMIAL", type = TypoEmail.class),
            @JqSubtype(value = "SMS", type = TypoSms.class)
    })
    sealed interface TypoChannel permits TypoEmail, TypoSms {
    }

    record TypoEmail(Method method, String a) implements TypoChannel {
    }

    record TypoSms(Method method, String b) implements TypoChannel {
    }

    @JqType(discriminator = "method", existingProperty = true)
    sealed interface DeadChannel permits DeadLetter, DeadSmsOk {
    }

    record DeadLetter(Method method, String reason) implements DeadChannel {
    }

    @JqName("SMS")
    record DeadSmsOk(Method method, String number) implements DeadChannel {
    }

    enum Other {
        PUSH
    }

    @JqType(discriminator = "method", existingProperty = true)
    sealed interface AmbiguousChannel permits AmbEmail, AmbPush {
    }

    record AmbEmail(Method method, String address) implements AmbiguousChannel {
    }

    record AmbPush(Other method, String text) implements AmbiguousChannel {
    }

    @JqType(discriminator = "kind")
    sealed interface InjectedChannel permits InjectedA, InjectedB {
    }

    record InjectedA(String kind, String a) implements InjectedChannel {
    }

    record InjectedB(String kind, String b) implements InjectedChannel {
    }

    @Test
    void renamedVariantsMatchingEnumPass() throws Exception {
        // @JqName values are the wire values; both name real constants.
        // (Nested @JqName form used here to keep the fixture small.)
        var violations = JjqProcessor.polyEnumViolations(
                Index.of(RenamedChannel.class, RenamedEmail.class, RenamedSms.class, Method.class));
        assertTrue(violations.isEmpty(), violations.toString());
    }

    @JqType(discriminator = "method", existingProperty = true)
    sealed interface RenamedChannel permits RenamedEmail, RenamedSms {
    }

    @JqName("EMAIL")
    record RenamedEmail(Method method, String address) implements RenamedChannel {
    }

    @JqName("SMS")
    record RenamedSms(Method method, String number) implements RenamedChannel {
    }

    @Test
    void explicitTypoFailsFast() throws Exception {
        var violations = JjqProcessor.polyEnumViolations(
                Index.of(TypoChannel.class, TypoEmail.class, TypoSms.class, Method.class));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("EMIAL"), violations.toString());
        assertTrue(violations.get(0).contains("Method"), violations.toString());
    }

    @Test
    void derivedValueOutsideEnumFailsFast() throws Exception {
        // DeadLetter derives "DeadLetter", which is no Method constant.
        var violations = JjqProcessor.polyEnumViolations(
                Index.of(DeadChannel.class, DeadLetter.class, DeadSmsOk.class, Method.class));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("DeadLetter"), violations.toString());
    }

    @Test
    void mismatchedEnumTypesFailFast() throws Exception {
        var violations = JjqProcessor.polyEnumViolations(
                Index.of(AmbiguousChannel.class, AmbEmail.class, AmbPush.class,
                        Method.class, Other.class));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("mismatched"), violations.toString());
    }

    @Test
    void injectedDiscriminatorUntouched() throws Exception {
        // No existingProperty: nothing to verify against.
        var violations = JjqProcessor.polyEnumViolations(
                Index.of(InjectedChannel.class, InjectedA.class, InjectedB.class));
        assertTrue(violations.isEmpty(), violations.toString());
    }
}
