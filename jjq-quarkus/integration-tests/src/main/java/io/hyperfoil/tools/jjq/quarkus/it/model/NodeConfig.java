package io.hyperfoil.tools.jjq.quarkus.it.model;

import io.hyperfoil.tools.jjq.mapper.JqKey;
import io.hyperfoil.tools.jjq.mapper.JqKeys;

/** Key-presence family with no discriminator on the wire. */
@JqKeys(value = {
        @JqKey(value = "windowLen", type = EDivisiveNode.class),
        @JqKey(value = "windowSize", type = StdDevNode.class),
        @JqKey(value = "threshold", type = RelativeNode.class)
}, defaultSubtype = FixedNode.class)
public sealed interface NodeConfig permits FixedNode, RelativeNode, StdDevNode, EDivisiveNode {
}
