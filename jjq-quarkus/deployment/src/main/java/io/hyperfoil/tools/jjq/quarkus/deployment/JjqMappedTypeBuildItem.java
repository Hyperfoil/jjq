package io.hyperfoil.tools.jjq.quarkus.deployment;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A {@code @JqMapped} type discovered via Jandex, with its generated
 * {@code _JqMapping} class name resolved by naming convention.
 */
public final class JjqMappedTypeBuildItem extends MultiBuildItem {

    private final String className;
    private final String mappingClassName;

    public JjqMappedTypeBuildItem(String className, String mappingClassName) {
        this.className = className;
        this.mappingClassName = mappingClassName;
    }

    /** The {@code @JqMapped} record/class name (Jandex dotted form). */
    public String getClassName() {
        return className;
    }

    /** The generated {@code _JqMapping} class name. */
    public String getMappingClassName() {
        return mappingClassName;
    }
}
