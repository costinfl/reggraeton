# Finds the Java installation reggraeton's scripts use. Source it from sh/bash:
#   . "$ROOT/bin/resolve-java.sh"
# Order: REGGRAETON_JAVA_HOME, .reggraeton/java-home (saved by bin/setup-java.sh), JAVA_HOME,
# java on the PATH. Sets REGGRAETON_JAVA (the java executable) and REGGRAETON_JDK_HOME (the JDK
# folder; empty when only a java on the PATH was found). Returns 1 with a message when nothing is found.

reggraeton_resolve_java() {
    _rg_root="$(cd "$(dirname "$1")/.." && pwd)"
    REGGRAETON_JAVA=""
    REGGRAETON_JDK_HOME=""
    _rg_saved=""
    if [ -f "$_rg_root/.reggraeton/java-home" ]; then
        _rg_saved="$(tr -d '\r\n' < "$_rg_root/.reggraeton/java-home")"
    fi
    for _rg_pair in "REGGRAETON_JAVA_HOME|${REGGRAETON_JAVA_HOME:-}" ".reggraeton/java-home|$_rg_saved" "JAVA_HOME|${JAVA_HOME:-}"; do
        _rg_source="${_rg_pair%%|*}"
        _rg_home="${_rg_pair#*|}"
        [ -n "$_rg_home" ] || continue
        if [ -x "$_rg_home/bin/java" ]; then
            REGGRAETON_JAVA="$_rg_home/bin/java"
            REGGRAETON_JDK_HOME="$_rg_home"
            return 0
        fi
        echo "warning: $_rg_source points to '$_rg_home', which has no bin/java - ignoring it" >&2
    done
    if command -v java > /dev/null 2>&1; then
        REGGRAETON_JAVA="$(command -v java)"
        return 0
    fi
    echo "No Java found. Run bin/setup-java.sh to choose one of your installed JDKs (17 or newer)," >&2
    echo "or set REGGRAETON_JAVA_HOME to a JDK folder." >&2
    return 1
}
