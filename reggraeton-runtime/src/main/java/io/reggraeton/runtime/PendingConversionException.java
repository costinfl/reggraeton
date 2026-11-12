package io.reggraeton.runtime;

/**
 * Thrown by code reggraeton could not convert automatically. The message names the original
 * source location and what to do; search the sources for {@code TODO(reggraeton)} to find them.
 */
public class PendingConversionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PendingConversionException(String message) {
        super("Not converted yet (reggraeton): " + message);
    }
}
