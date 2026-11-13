package io.reggraeton.scan.db;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** What database work a set of call graph nodes (e.g. everything a hook reaches) does. */
public final class DatabaseAccess {

    /** Calls that change data, or may (stored procedures, commits of earlier work, ORM writes). */
    private static final Pattern WRITE_CALL = Pattern.compile(
            "\\.(executeUpdate|executeLargeUpdate|executeBatch|execute|update|batchUpdate|persist|merge|remove|save\\w*"
                    + "|delete\\w*|insert\\w*|commit|prepareCall|call|refresh|flush|migrate|clean|executeProcedure"
                    + "|onSetup|onTearDown)$");

    /**
     * @param operations SQL operations ({@code INSERT}, {@code NEXTVAL} ...) and called APIs ({@code JdbcTemplate.update})
     * @param nodes      the nodes that access the database directly
     */
    /**
     * @param operations SQL operations, most significant first
     * @param libraries  database libraries called (JDBC, Spring JDBC, Hibernate ...)
     * @param writeCalls called APIs that change data ({@code Statement.executeUpdate}, {@code JdbcTemplate.update} ...)
     */
    public record Reach(Set<String> operations, Set<String> libraries, Set<String> writeCalls, boolean writes,
                        List<String> nodes) {
        public boolean isEmpty() {
            return nodes.isEmpty();
        }

        /** e.g. {@code DELETE, NEXTVAL, SELECT FROM DUAL; JDBC (Statement.executeUpdate)}. */
        public String summary() {
            StringBuilder sb = new StringBuilder(String.join(", ", operations));
            if (!libraries.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : "; ").append(String.join(", ", libraries));
                if (!writeCalls.isEmpty()) {
                    sb.append(" (").append(String.join(", ", writeCalls)).append(')');
                }
            }
            return sb.toString();
        }
    }

    private static final Pattern LIBRARY = Pattern.compile("database access \\(([^)]+)\\)");

    private final Map<String, CallGraph.Node> databaseNodes = new HashMap<>();

    public DatabaseAccess(CallGraph graph) {
        for (CallGraph.Node n : graph.nodes()) {
            if (n.apiUsages().stream().anyMatch(u -> ApiUsage.DATABASE.equals(u.category()))) {
                databaseNodes.put(n.id(), n);
            }
        }
    }

    public boolean accessesDatabase(String nodeId) {
        return databaseNodes.containsKey(nodeId);
    }

    public Collection<CallGraph.Node> nodes() {
        return databaseNodes.values();
    }

    public Reach reach(Collection<String> nodeIds) {
        Set<String> sql = new TreeSet<>(java.util.Comparator.comparingInt(DatabaseAccess::order).thenComparing(s -> s));
        Set<String> libraries = new TreeSet<>();
        Set<String> writeCalls = new TreeSet<>();
        List<String> nodes = new ArrayList<>();
        boolean writes = false;
        for (String id : nodeIds) {
            CallGraph.Node n = databaseNodes.get(id);
            if (n == null) {
                continue;
            }
            nodes.add(id);
            for (ApiUsage u : n.apiUsages()) {
                if (!ApiUsage.DATABASE.equals(u.category())) {
                    continue;
                }
                if (u.api().startsWith(ApiUsage.SQL_PREFIX)) {
                    List<String> ops = Sql.operations(u.code(), false);
                    sql.addAll(ops);
                    writes |= Sql.writes(ops);
                } else {
                    var library = LIBRARY.matcher(u.note());
                    libraries.add(library.find() ? library.group(1) : u.api());
                    if (isWriteCall(u.api())) {
                        writeCalls.add(u.api());
                        writes = true;
                    }
                }
            }
        }
        return new Reach(new LinkedHashSet<>(sql), libraries, writeCalls, writes, nodes);
    }

    /** {@code JdbcTemplate.update}, {@code Statement.executeUpdate}, {@code EntityManager.persist} ... */
    public static boolean isWriteCall(String api) {
        return WRITE_CALL.matcher(api).find();
    }

    private static final List<String> ORDER = List.of(Sql.PLSQL, Sql.CALL, Sql.MERGE, Sql.INSERT, Sql.UPDATE, Sql.DELETE,
            Sql.TRUNCATE, Sql.DDL, Sql.NEXTVAL, Sql.CURRVAL, Sql.FUNCTION, Sql.DUAL, Sql.SELECT, Sql.LOCK, Sql.COMMIT,
            Sql.ROLLBACK, Sql.FLASHBACK);

    private static int order(String op) {
        int i = ORDER.indexOf(op);
        return i < 0 ? ORDER.size() : i;
    }
}
