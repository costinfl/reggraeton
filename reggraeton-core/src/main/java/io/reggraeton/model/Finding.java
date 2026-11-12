package io.reggraeton.model;

/**
 * Something the assessment wants a human to know about.
 *
 * @param severity {@code error} (blocks conversion of a test), {@code warning} (needs manual work) or {@code info}
 * @param code     stable machine-readable code, e.g. {@code UNDEFINED_STEP}
 */
public record Finding(String severity, String code, String message, String file, int line) {

    public static Finding error(String code, String message, String file, int line) {
        return new Finding("error", code, message, file, line);
    }

    public static Finding warning(String code, String message, String file, int line) {
        return new Finding("warning", code, message, file, line);
    }

    public static Finding info(String code, String message, String file, int line) {
        return new Finding("info", code, message, file, line);
    }
}
