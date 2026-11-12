# Makes the chosen JDK the Java of the current shell (for mvn and java). Source it:
#   . bin/java-env.sh
# Exports JAVA_HOME and puts its bin first on PATH, for this shell only.
if [ -n "${BASH_SOURCE:-}" ]; then _rg_self="${BASH_SOURCE}"; else _rg_self="$0"; fi
. "$(dirname "$_rg_self")/resolve-java.sh"
if reggraeton_resolve_java "$_rg_self"; then
    if [ -n "$REGGRAETON_JDK_HOME" ]; then
        export JAVA_HOME="$REGGRAETON_JDK_HOME"
        export PATH="$JAVA_HOME/bin:$PATH"
        echo "JAVA_HOME=$JAVA_HOME (this shell only)"
    else
        echo "Using $REGGRAETON_JAVA from the PATH (JAVA_HOME unchanged)."
    fi
fi
unset _rg_self
