package io.reggraeton.scan.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises SQL in string literals and text files, says what it does (INSERT, sequence NEXTVAL,
 * SELECT ... FROM DUAL, stored procedure call, PL/SQL block, ...) and masks credentials.
 * Heuristic and dialect-tolerant, tuned for Oracle.
 */
public final class Sql {

    public static final String INSERT = "INSERT";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String MERGE = "MERGE";
    public static final String TRUNCATE = "TRUNCATE";
    public static final String DDL = "DDL";
    public static final String CALL = "CALL";
    public static final String PLSQL = "PL/SQL";
    public static final String NEXTVAL = "NEXTVAL";
    public static final String CURRVAL = "CURRVAL";
    public static final String DUAL = "SELECT FROM DUAL";
    /** A function called from a SELECT ... FROM DUAL that is not a well-known built-in: it may change data. */
    public static final String FUNCTION = "FUNCTION";
    public static final String SELECT = "SELECT";
    public static final String LOCK = "LOCK TABLE";
    public static final String COMMIT = "COMMIT";
    public static final String ROLLBACK = "ROLLBACK";
    public static final String FLASHBACK = "FLASHBACK";

    /** Operations that change data (or may: a sequence increment is not rolled back). */
    public static final Set<String> WRITES = Set.of(INSERT, UPDATE, DELETE, MERGE, TRUNCATE, DDL, CALL, PLSQL, NEXTVAL,
            FUNCTION, ROLLBACK, FLASHBACK);

    private static final int CI = Pattern.CASE_INSENSITIVE;
    private static final String NAME = "[\\w$#\".]+";

    private static final Pattern START = Pattern.compile(
            "^\\s*[({]?\\s*(\\?\\s*=\\s*)?(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|CALL|BEGIN|DECLARE"
                    + "|EXEC|EXECUTE|WITH|COMMIT|ROLLBACK|LOCK|FLASHBACK)\\b", CI);
    private static final Pattern UPPER_KEYWORD = Pattern.compile(
            "\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|CALL|BEGIN|DECLARE|EXEC|EXECUTE|COMMIT|ROLLBACK|FLASHBACK)\\b");
    /** Something only SQL would contain; required when no keyword is written in upper case. */
    private static final Pattern MARKERS = Pattern.compile(
            "\\bWHERE\\b|\\bJOIN\\b|\\bORDER\\s+BY\\b|\\bGROUP\\s+BY\\b|\\bVALUES\\s*\\(|\\bSET\\s+[\\w\"]+\\s*=|\\bDUAL\\b"
                    + "|\\bNEXTVAL\\b|\\bCURRVAL\\b|\\bINTO\\s+" + NAME + "\\s*\\(|[*=?]|;\\s*$|\\bEND\\s*;|\\{\\s*(\\?\\s*=\\s*)?CALL\\b"
                    + "|\\b(FROM|INTO|UPDATE|TABLE)\\s+[\\w$#]*[_.$#][\\w$#.]*", CI);

    private static final List<Rule> RULES = List.of(
            new Rule(PLSQL, Pattern.compile("\\b(DECLARE|BEGIN)\\b[\\s\\S]*\\bEND\\s*(" + NAME + ")?\\s*;", CI)),
            new Rule(CALL, Pattern.compile("\\{\\s*(\\?\\s*=\\s*)?CALL\\b|^\\s*CALL\\s+" + NAME + "\\s*\\(|^\\s*EXEC(UTE)?\\s+(?!IMMEDIATE\\b)" + NAME, CI)),
            new Rule(MERGE, Pattern.compile("\\bMERGE\\s+INTO\\b", CI)),
            new Rule(INSERT, Pattern.compile("\\bINSERT\\s+(ALL\\s+)?INTO\\s+" + NAME, CI)),
            new Rule(UPDATE, Pattern.compile("\\bUPDATE\\s+" + NAME + "(\\s+\\w+)?\\s+SET\\b", CI)),
            new Rule(DELETE, Pattern.compile("\\bDELETE\\s+FROM\\s+" + NAME + "|\\bDELETE\\s+" + NAME + "(\\s+\\w+)?\\s+WHERE\\b", CI)),
            new Rule(TRUNCATE, Pattern.compile("\\bTRUNCATE\\s+TABLE\\b", CI)),
            new Rule(DDL, Pattern.compile("\\b(CREATE(\\s+OR\\s+REPLACE)?|ALTER|DROP)\\s+(GLOBAL\\s+TEMPORARY\\s+)?(TABLE|SEQUENCE|INDEX"
                    + "|VIEW|PROCEDURE|FUNCTION|TRIGGER|PACKAGE(\\s+BODY)?|SYNONYM|TYPE|USER|MATERIALIZED\\s+VIEW)\\b", CI)),
            new Rule(NEXTVAL, Pattern.compile("\\.\\s*NEXTVAL\\b|\\bNEXT\\s+VALUE\\s+FOR\\b", CI)),
            new Rule(CURRVAL, Pattern.compile("\\.\\s*CURRVAL\\b", CI)),
            new Rule(DUAL, Pattern.compile("\\bSELECT\\b[\\s\\S]*?\\bFROM\\s+(SYS\\.)?DUAL\\b", CI)),
            new Rule(SELECT, Pattern.compile("\\bSELECT\\b[\\s\\S]+?\\bFROM\\s+(?!(SYS\\.)?DUAL\\b)[\\w$#\"(]", CI)),
            new Rule(LOCK, Pattern.compile("\\bLOCK\\s+TABLE\\b", CI)),
            new Rule(FLASHBACK, Pattern.compile("\\bFLASHBACK\\s+(TABLE|DATABASE)\\b", CI)),
            new Rule(COMMIT, Pattern.compile("^\\s*COMMIT\\b|\\bCOMMIT\\s*;", CI)),
            new Rule(ROLLBACK, Pattern.compile("^\\s*ROLLBACK\\b|\\bROLLBACK(\\s+TO\\s+\\w+)?\\s*;", CI)));

    private static final Pattern DUAL_SELECT_LIST = Pattern.compile("\\bSELECT\\b([\\s\\S]*?)\\bFROM\\s+(SYS\\.)?DUAL\\b", CI);
    private static final Pattern FUNCTION_CALL = Pattern.compile("([A-Za-z_][\\w$#]*(\\s*\\.\\s*[A-Za-z_][\\w$#]*)*)\\s*\\(");
    private static final Set<String> BUILT_INS = Set.of(
            "to_char", "to_date", "to_number", "to_timestamp", "to_clob", "nvl", "nvl2", "coalesce", "decode", "count",
            "max", "min", "sum", "avg", "upper", "lower", "initcap", "trim", "ltrim", "rtrim", "lpad", "rpad", "substr",
            "instr", "length", "replace", "concat", "trunc", "round", "abs", "mod", "ceil", "floor", "power", "sqrt",
            "add_months", "months_between", "last_day", "next_day", "extract", "greatest", "least", "cast",
            "sys_guid", "sys_context", "userenv", "current_timestamp", "systimestamp", "localtimestamp", "numtodsinterval",
            "numtoyminterval", "regexp_substr", "regexp_replace", "regexp_like", "regexp_instr", "regexp_count",
            "standard_hash", "ora_hash", "dbms_random.value", "dbms_random.string", "dbms_utility.get_time", "chr",
            "ascii", "from_tz", "new_time", "tz_offset", "rawtohex", "hextoraw", "utl_raw.cast_to_raw", "listagg", "case",
            "exists", "in", "dbms_lob.substr", "dbms_lob.getlength", "json_value", "json_object", "xmltype", "sign");

    private static final Pattern IDENTIFIED_BY = Pattern.compile("(\\bIDENTIFIED\\s+BY\\s+)(\"[^\"]*\"|\\S+)", CI);

    private record Rule(String operation, Pattern pattern) {
    }

    private Sql() {
    }

    /**
     * Operations found in {@code text}, most significant first; empty when it does not look like SQL.
     *
     * @param mustStart true for string literals: the SQL has to start at the beginning (after "(", "{" or "?=")
     */
    public static List<String> operations(String text, boolean mustStart) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (mustStart && !START.matcher(text).find()) {
            return List.of();
        }
        if (!UPPER_KEYWORD.matcher(text).find() && !MARKERS.matcher(text).find()) {
            return List.of(); // prose such as "select a product from the list"
        }
        List<String> ops = new ArrayList<>();
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                ops.add(rule.operation());
            }
        }
        if (ops.contains(PLSQL)) {
            ops.remove(CALL);
        }
        if (ops.contains(DUAL) && callsNonBuiltin(text)) {
            ops.add(ops.indexOf(DUAL), FUNCTION);
        }
        return ops;
    }

    public static boolean writes(List<String> operations) {
        return operations.stream().anyMatch(WRITES::contains);
    }

    private static boolean callsNonBuiltin(String text) {
        Matcher list = DUAL_SELECT_LIST.matcher(text);
        while (list.find()) {
            Matcher call = FUNCTION_CALL.matcher(list.group(1));
            while (call.find()) {
                String name = call.group(1).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
                if (!BUILT_INS.contains(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** One line, whitespace collapsed, credentials masked, at most {@code max} characters. */
    public static String display(String text, int max) {
        String s = IDENTIFIED_BY.matcher(text.replaceAll("\\s+", " ").trim()).replaceAll("$1***");
        s = Connections.mask(s);
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }
}
