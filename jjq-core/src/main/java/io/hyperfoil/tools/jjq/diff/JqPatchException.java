package io.hyperfoil.tools.jjq.diff;

/**
 * A failed RFC 6902 patch application: unknown operation, missing path
 * or value, out-of-bounds index, failed {@code test}, or a move into
 * its own child. The original document is never modified — values are
 * immutable, so a throwing apply leaves no partial state behind.
 */
public class JqPatchException extends RuntimeException {

    private final int opIndex;

    /**
     * @param opIndex index of the failing operation in the patch array,
     *                or -1 for document-level errors (patch not an array)
     * @param message what went wrong
     */
    public JqPatchException(int opIndex, String message) {
        super(message);
        this.opIndex = opIndex;
    }

    /** Index of the failing operation, or -1 for document-level errors. */
    public int opIndex() {
        return opIndex;
    }

    @Override
    public String getMessage() {
        String base = super.getMessage();
        return opIndex < 0 ? base : "operation[" + opIndex + "]: " + base;
    }
}
