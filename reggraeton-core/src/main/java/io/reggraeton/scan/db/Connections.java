package io.reggraeton.scan.db;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds JDBC connection strings and database settings in text, with credentials masked. */
public final class Connections {

    /** A connection string or setting found on one line. {@code key} is null for a bare JDBC URL. */
    public record Found(String key, String value) {
    }

    private static final Pattern JDBC_URL = Pattern.compile("jdbc:[A-Za-z0-9]+:[^\\s\"'<>`]*");
    /** jdbc:oracle:thin:scott/tiger@host -> scott/***@host. */
    private static final Pattern USER_PASSWORD_AT = Pattern.compile("(jdbc:[A-Za-z0-9]+:(?:[A-Za-z0-9]+:)?)([^/@:;?()]+)/([^@]*)@");
    /** jdbc:x://user:pass@host -> user:***@host. */
    private static final Pattern URL_USERINFO = Pattern.compile("(//[^/:@\\s]+):([^@/\\s]*)@");
    private static final Pattern PASSWORD_PARAM = Pattern.compile("(?i)\\b(password|passwd|pwd)=([^;&\\s\"'<]*)");

    private static final Pattern PROPERTY = Pattern.compile("^\\s*([\\w.\\-\\[\\]]+)\\s*[:=]\\s*(.*?)\\s*$");
    private static final Pattern XML_ELEMENT = Pattern.compile("<([\\w.\\-]+)>([^<]*)</\\1>");
    private static final Pattern DB_KEY = Pattern.compile("(?i)(db|database|datasource|jdbc|oracle|sql|connection|hibernate)");
    private static final Pattern SETTING = Pattern.compile("(?i)(url|user|username|password|passwd|pwd|schema|driver|host|sid|service)");
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(pass|pwd|secret|credential)");

    private Connections() {
    }

    /**
     * @param configFile true for properties/YAML/XML/env files, where {@code key=value} database settings are also reported
     */
    public static List<Found> find(String line, boolean configFile) {
        List<Found> found = new ArrayList<>();
        if (configFile) {
            Matcher p = PROPERTY.matcher(line);
            if (p.matches() && !line.stripLeading().startsWith("#") && isSetting(p.group(1))) {
                found.add(new Found(p.group(1), value(p.group(1), p.group(2))));
                return found;
            }
            Matcher x = XML_ELEMENT.matcher(line);
            while (x.find()) {
                if (isSetting(x.group(1))) {
                    found.add(new Found(x.group(1), value(x.group(1), x.group(2))));
                }
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        Matcher url = JDBC_URL.matcher(line);
        while (url.find()) {
            found.add(new Found(null, mask(balance(url.group()))));
        }
        return found;
    }

    private static boolean isSetting(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1);
        return DB_KEY.matcher(key).find() && SETTING.matcher(last).find();
    }

    private static String value(String key, String value) {
        String v = value.replaceAll("^[\"']|[\"']$", "");
        if (SECRET_KEY.matcher(key.toLowerCase(Locale.ROOT)).find() && !v.isEmpty() && !v.startsWith("${")) {
            return "***";
        }
        return mask(v);
    }

    /** Drops a trailing ')' that belongs to the surrounding code, keeps those of Oracle descriptors. */
    private static String balance(String url) {
        String s = url.replaceAll("[,;.]+$", "");
        while (s.endsWith(")") && count(s, '(') < count(s, ')')) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static int count(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }

    /** Masks passwords in JDBC URLs and key=value pairs. */
    public static String mask(String text) {
        String s = USER_PASSWORD_AT.matcher(text).replaceAll("$1$2/***@");
        s = URL_USERINFO.matcher(s).replaceAll("$1:***@");
        return PASSWORD_PARAM.matcher(s).replaceAll("$1=***");
    }
}
