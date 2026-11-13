package io.reggraeton.model;

import java.util.List;

/**
 * Where the scanned test suite talks to a database: connection strings and settings, and SQL
 * statements (in Java string literals, SQL scripts, mapper XML, feature files ...).
 * Database calls in Java code are in the call graph as {@code database} API usages.
 */
public record DatabaseInventory(List<Connection> connections, List<SqlStatement> statements) {

    public static DatabaseInventory empty() {
        return new DatabaseInventory(List.of(), List.of());
    }

    /**
     * @param key   setting name ({@code db.url}), null for a JDBC URL found in code or text
     * @param value with passwords masked
     */
    public record Connection(String file, int line, String key, String value) {
    }

    /**
     * @param operations what it does, most significant first ({@code INSERT}, {@code NEXTVAL}, {@code SELECT FROM DUAL} ...)
     * @param writes     true when it changes data (or may: sequences, procedure calls, functions called from DUAL)
     * @param text       one line, credentials masked, shortened
     * @param nodeId     call graph node containing the SQL (Java only), null otherwise
     */
    public record SqlStatement(String file, int line, List<String> operations, boolean writes, String text, String nodeId) {
    }
}
