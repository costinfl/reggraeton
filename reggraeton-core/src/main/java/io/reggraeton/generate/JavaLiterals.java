package io.reggraeton.generate;

/** Java source literals. */
final class JavaLiterals {

    private JavaLiterals() {
    }

    /** {@code "text"} with Java escapes. */
    static String string(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c < 0xa0)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Text for a {@code /** ... *}{@code /} comment. */
    static String comment(String value) {
        return value.replace("*/", "* /").replace("\n", " ");
    }
}
