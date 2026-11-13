package io.reggraeton.scan.db;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.DatabaseInventory;
import io.reggraeton.util.RepoFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds the {@link DatabaseInventory}: SQL found in Java code comes from the call graph (so it is
 * linked to the method containing it); SQL scripts, mapper XML, configuration and feature files are
 * scanned as text, and every text file is searched for connection strings.
 */
public final class DatabaseScanner {

    /** Files searched for SQL. */
    private static final Set<String> SQL_FILES = Set.of(".sql", ".pls", ".pks", ".pkb", ".prc", ".fnc", ".trg",
            ".xml", ".properties", ".yml", ".yaml", ".json", ".feature", ".groovy", ".kt", ".conf", ".csv");
    /** Files where key=value database settings are reported. */
    private static final Set<String> CONFIG_FILES = Set.of(".properties", ".yml", ".yaml", ".xml", ".conf", ".cfg",
            ".ini", ".env", ".toml");
    private static final Set<String> TEXT_FILES = Set.of(".java", ".txt", ".md", ".gradle", ".kts", ".cfg", ".ini",
            ".env", ".toml", ".bat", ".cmd", ".sh", ".ps1", ".jenkinsfile", ".groovy");
    private static final long MAX_SIZE = 2_000_000;
    private static final int MAX_LINES_PER_STATEMENT = 30;
    private static final Pattern STATEMENT_START = Pattern.compile(
            "\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|CALL|BEGIN|DECLARE|EXEC|EXECUTE|COMMIT"
                    + "|ROLLBACK|LOCK|FLASHBACK)\\b|\\{\\s*(\\?\\s*=\\s*)?call\\b", Pattern.CASE_INSENSITIVE);
    /** Lines that end a statement in a text file. */
    private static final Pattern STATEMENT_END = Pattern.compile(";\\s*(--.*)?$|^\\s*/\\s*$|</|\"\"\"|\"\\s*[,)]?\\s*$|^\\s*$");

    private DatabaseScanner() {
    }

    public static DatabaseInventory scan(Path root, CallGraph graph) {
        List<DatabaseInventory.Connection> connections = new ArrayList<>();
        List<DatabaseInventory.SqlStatement> statements = new ArrayList<>();

        for (CallGraph.Node node : graph.nodes()) {
            for (ApiUsage u : node.apiUsages()) {
                if (ApiUsage.DATABASE.equals(u.category()) && u.api().startsWith(ApiUsage.SQL_PREFIX)) {
                    List<String> ops = Sql.operations(u.code(), false);
                    statements.add(new DatabaseInventory.SqlStatement(node.file(), u.line(), ops, Sql.writes(ops), u.code(), node.id()));
                }
            }
        }

        for (Path file : RepoFiles.find(root, DatabaseScanner::isText)) {
            String ext = extension(file);
            List<String> lines = lines(file);
            String rel = RepoFiles.relative(root, file);
            boolean config = CONFIG_FILES.contains(ext) || file.getFileName().toString().startsWith(".env");
            for (int i = 0; i < lines.size(); i++) {
                for (Connections.Found f : Connections.find(lines.get(i), config)) {
                    connections.add(new DatabaseInventory.Connection(rel, i + 1, f.key(), f.value()));
                }
            }
            if (SQL_FILES.contains(ext)) {
                sqlInText(rel, lines, statements);
            }
        }
        statements.sort(Comparator.comparing(DatabaseInventory.SqlStatement::file).thenComparingInt(DatabaseInventory.SqlStatement::line));
        return new DatabaseInventory(connections, statements);
    }

    /** A statement starts on a line with an SQL keyword and runs to ';', '/', a closing tag/quote or a blank line. */
    private static void sqlInText(String file, List<String> lines, List<DatabaseInventory.SqlStatement> out) {
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            var start = STATEMENT_START.matcher(line);
            if (line.stripLeading().startsWith("--") || !start.find()) {
                i++;
                continue;
            }
            StringBuilder text = new StringBuilder(line.substring(start.start()));
            int end = i;
            boolean plsql = line.substring(start.start()).matches("(?is)\\s*(BEGIN|DECLARE|CREATE\\s+(OR\\s+REPLACE\\s+)?(PROCEDURE|FUNCTION|PACKAGE|TRIGGER)).*");
            while (end + 1 < lines.size() && end - i < MAX_LINES_PER_STATEMENT
                    && (plsql ? !lines.get(end).matches("^\\s*/\\s*$") && !lines.get(end).matches("(?i)^\\s*END\\s*\\w*\\s*;\\s*$")
                    : !STATEMENT_END.matcher(lines.get(end)).find())) {
                end++;
                text.append('\n').append(lines.get(end));
            }
            String sql = text.toString().replaceAll("<[^>]+>", " ");
            List<String> ops = Sql.operations(sql, false);
            if (!ops.isEmpty()) {
                out.add(new DatabaseInventory.SqlStatement(file, i + 1, ops, Sql.writes(ops), Sql.display(sql, 300), null));
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    private static boolean isText(Path file) {
        String ext = extension(file);
        String name = file.getFileName().toString();
        try {
            return (SQL_FILES.contains(ext) || CONFIG_FILES.contains(ext) || TEXT_FILES.contains(ext)
                    || name.equals("Jenkinsfile") || name.startsWith(".env"))
                    && Files.size(file) <= MAX_SIZE;
        } catch (IOException e) {
            return false;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot);
    }

    private static List<String> lines(Path file) {
        return RepoFiles.read(file).lines().toList();
    }
}
