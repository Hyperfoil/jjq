package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqSubtype;
import io.hyperfoil.tools.jjq.mapper.JqType;

/** Discriminator-as-field family for augmentation testing (issue #151). */
@JqType(discriminator = "method", existingProperty = true, mappings = {
        @JqSubtype(value = "EMAIL", type = PolyEmail.class),
        @JqSubtype(value = "SLACK", type = PolySlack.class)
})
public sealed interface PolyChannel permits PolyEmail, PolySlack {
}
