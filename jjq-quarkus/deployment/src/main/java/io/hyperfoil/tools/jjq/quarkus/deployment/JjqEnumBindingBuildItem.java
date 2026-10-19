package io.hyperfoil.tools.jjq.quarkus.deployment;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A compiled enum binding emitted during augmentation, with the enum class
 * it serves. The binding performs direct member calls; registration happens
 * through the generated mapper producer.
 */
public final class JjqEnumBindingBuildItem extends MultiBuildItem {

    private final String enumClassName;
    private final String bindingClassName;

    public JjqEnumBindingBuildItem(String enumClassName, String bindingClassName) {
        this.enumClassName = enumClassName;
        this.bindingClassName = bindingClassName;
    }

    /** The bound enum class name (Jandex dotted form). */
    public String getEnumClassName() {
        return enumClassName;
    }

    /** The generated binding class name. */
    public String getBindingClassName() {
        return bindingClassName;
    }
}
