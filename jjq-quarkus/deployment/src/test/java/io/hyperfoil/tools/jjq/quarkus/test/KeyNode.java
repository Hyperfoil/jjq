package io.hyperfoil.tools.jjq.quarkus.test;

import io.hyperfoil.tools.jjq.mapper.JqKey;
import io.hyperfoil.tools.jjq.mapper.JqKeys;

/** Key-presence family for augmentation testing (issue #151). */
@JqKeys(value = {
        @JqKey(value = "a", type = KeyNodeA.class)
}, defaultSubtype = KeyNodeB.class)
public sealed interface KeyNode permits KeyNodeA, KeyNodeB {
}
