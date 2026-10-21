package io.hyperfoil.tools.jjq.quarkus.deployment;

import io.quarkus.builder.item.MultiBuildItem;

/**
 * A direct byte-to-record reader generated for an eligible
 * {@code @JqMapped} request type, with its generated reader class name.
 */
public final class JjqDirectReaderBuildItem extends MultiBuildItem {

    private final String className;
    private final String readerClassName;

    public JjqDirectReaderBuildItem(String className, String readerClassName) {
        this.className = className;
        this.readerClassName = readerClassName;
    }

    /** The bound type name (Jandex dotted form). */
    public String getClassName() {
        return className;
    }

    /** The generated {@code _DirectReader} class name. */
    public String getReaderClassName() {
        return readerClassName;
    }
}
