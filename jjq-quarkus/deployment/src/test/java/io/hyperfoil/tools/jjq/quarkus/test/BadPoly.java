package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqSubtype;
import io.hyperfoil.tools.jjq.mapper.JqType;

/**
 * Malformed family: two wire values collide. Augmentation must fail the
 * build with the mapper's message instead of failing the first request.
 */
@JqType(discriminator = "method", existingProperty = true, mappings = {
        @JqSubtype(value = "A", type = BadPolyA.class),
        @JqSubtype(value = "A", type = BadPolyB.class)
})
public sealed interface BadPoly permits BadPolyA, BadPolyB {
}
