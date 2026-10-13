package io.hyperfoil.tools.jjq.mapper;

import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

/**
 * Serialization half of polymorphic family membership: the discriminator
 * property plus this type's wire value. Carried by subtype mappings so
 * serialization injects the discriminator with no per-call lookup.
 *
 * @param discriminator the JSON property carrying the discriminator value
 * @param wireValue     this type's discriminator value
 */
record PolySerInfo(String discriminator, String wireValue) {
    /**
     * Inject the discriminator into a serialized tree. The tree is rebuilt
     * with the discriminator appended last via checked {@code put}, so an
     * any-getter extra on the same key is deterministically replaced rather
     * than duplicated.
     *
     * @param tree the subtype mapping output (must be a {@link JqObject})
     * @return the tree plus discriminator
     */
    JqValue inject(JqValue tree) {
        if (!(tree instanceof JqObject obj)) {
            throw new JqMapperException("Polymorphic serialization of " + wireValue
                    + " produced " + (tree == null ? "null" : tree.type().jqName())
                    + ", not an object — cannot inject '" + discriminator + "'");
        }
        var builder = JqObject.builder(obj.size() + 1);
        obj.forEach((k, v) -> builder.putUnchecked(k, v));
        builder.put(discriminator, JqString.of(wireValue));
        return builder.build();
    }
}
