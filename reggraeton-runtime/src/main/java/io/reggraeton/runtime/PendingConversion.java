package io.reggraeton.runtime;

/** Marks code that still needs a manual conversion; see {@code TODO(reggraeton)} comments next to each call. */
public final class PendingConversion {

    private PendingConversion() {
    }

    /**
     * Always throws {@link PendingConversionException}. Declared to return any type so it can stand
     * in for an expression as well as for a statement.
     */
    public static <T> T fail(String message) {
        throw new PendingConversionException(message);
    }
}
