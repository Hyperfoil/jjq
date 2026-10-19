package io.hyperfoil.tools.jjq.quarkus.deployment;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A polymorphic family base ({@code @JqType} or {@code @JqKeys})
 * discovered via Jandex. Resolved to a dispatch configuration during
 * augmentation so native images never query sealed permits at runtime.
 */
public final class JjqPolyFamilyBuildItem extends MultiBuildItem {

    private final String className;

    public JjqPolyFamilyBuildItem(String className) {
        this.className = className;
    }

    /** The family base type name (Jandex dotted form). */
    public String getClassName() {
        return className;
    }
}
