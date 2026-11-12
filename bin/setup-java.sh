#!/usr/bin/env bash
# Chooses the JDK that reggraeton's scripts use, without changing PATH or system settings.
#
#   bin/setup-java.sh                       # list the installed JDKs and pick one
#   bin/setup-java.sh --java-home /path/to/jdk-21
#   bin/setup-java.sh --list
#
# The choice is saved to .reggraeton/java-home in this checkout and used by bin/reggraeton,
# bin/java-env.sh and scripts/e2e.sh. REGGRAETON_JAVA_HOME overrides it. Java 17+ is required.
set -euo pipefail

MINIMUM=17
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONFIG="$ROOT/.reggraeton/java-home"

version_of() {   # version text of a JDK folder, e.g. 21.0.5 or 1.8.0_392
    local home="$1" line
    if [ -f "$home/release" ]; then
        line="$(grep -E '^JAVA_VERSION=' "$home/release" | head -1 || true)"
        if [ -n "$line" ]; then line="${line#JAVA_VERSION=}"; echo "${line//\"/}"; return; fi
    fi
    "$home/bin/java" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1
}

major_of() {
    local v="$1"
    if [[ "$v" =~ ^1\.([0-9]+) ]]; then echo "${BASH_REMATCH[1]}"
    elif [[ "$v" =~ ^([0-9]+) ]]; then echo "${BASH_REMATCH[1]}"
    else echo 0; fi
}

save() {
    mkdir -p "$(dirname "$CONFIG")"
    printf '%s' "$1" > "$CONFIG"
    echo
    echo "Saved: reggraeton scripts in this checkout will use $1"
    echo "  (stored in $CONFIG; REGGRAETON_JAVA_HOME overrides it)"
    echo
    echo "For Maven in this shell (sets JAVA_HOME for the shell only):"
    echo "  . bin/java-env.sh"
}

check_and_save() {
    local home="$1" version major
    if [ ! -x "$home/bin/java" ]; then echo "'$home' is not a JDK folder: it has no bin/java" >&2; exit 1; fi
    version="$(version_of "$home")"
    major="$(major_of "$version")"
    if [ "$major" -lt "$MINIMUM" ]; then
        echo "'$home' is Java ${version:-?}; reggraeton needs Java $MINIMUM or newer." >&2
        exit 1
    fi
    echo "Java $version at $home"
    save "$home"
}

LIST=false
EXPLICIT=""
while [ $# -gt 0 ]; do
    case "$1" in
        --java-home) EXPLICIT="${2:?--java-home needs a folder}"; shift 2 ;;
        --java-home=*) EXPLICIT="${1#--java-home=}"; shift ;;
        --list) LIST=true; shift ;;
        -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

if [ -n "$EXPLICIT" ]; then
    check_and_save "$(cd "$EXPLICIT" 2>/dev/null && pwd || echo "$EXPLICIT")"
    exit 0
fi

# ---- detection ------------------------------------------------------------------------------
declare -a HOMES=()
add() {
    local candidate="$1" full existing
    [ -n "$candidate" ] && [ -x "$candidate/bin/java" ] || return 0
    full="$(cd "$candidate" && pwd -P)"
    for existing in "${HOMES[@]+"${HOMES[@]}"}"; do [ "$existing" = "$full" ] && return 0; done
    HOMES+=("$full")
}
for parent in /usr/lib/jvm /usr/java /opt /Library/Java/JavaVirtualMachines "$HOME/Library/Java/JavaVirtualMachines" \
              "$HOME/.jdks" "$HOME/.sdkman/candidates/java" "$HOME/.asdf/installs/java"; do
    [ -d "$parent" ] || continue
    for dir in "$parent"/*/; do
        dir="${dir%/}"
        add "$dir"
        add "$dir/Contents/Home"
    done
done
add "${JAVA_HOME:-}"
if command -v java > /dev/null 2>&1; then
    javabin="$(command -v java)"
    javabin="$(readlink -f "$javabin" 2>/dev/null || echo "$javabin")"
    add "$(dirname "$(dirname "$javabin")")"
fi

if [ ${#HOMES[@]} -eq 0 ]; then
    echo "No JDK found in the usual places. Run again with the folder of a JDK $MINIMUM+:"
    echo "  bin/setup-java.sh --java-home /path/to/jdk"
    exit 1
fi

# sort by major version, newest first
declare -a ROWS=()
for home in "${HOMES[@]}"; do
    version="$(version_of "$home")"
    ROWS+=("$(major_of "$version")|${version:-?}|$home")
done
mapfile -t ROWS < <(printf '%s\n' "${ROWS[@]}" | sort -t'|' -k1,1nr -k3,3)

current=""
[ -f "$CONFIG" ] && current="$(tr -d '\r\n' < "$CONFIG")"
echo "Installed JDKs:"
default=""
for i in "${!ROWS[@]}"; do
    IFS='|' read -r major version home <<< "${ROWS[$i]}"
    notes=()
    [ "$major" -lt "$MINIMUM" ] && notes+=("too old, needs $MINIMUM+")
    [ "$home" = "$current" ] && notes+=("current choice")
    note=""
    if [ ${#notes[@]} -gt 0 ]; then
        note="   (${notes[0]}"
        for extra in "${notes[@]:1}"; do note="$note; $extra"; done
        note="$note)"
    fi
    printf '  [%d] Java %-12s %s%s\n' $((i + 1)) "$version" "$home" "$note"
    if [ -z "$default" ] && [ "$major" -ge "$MINIMUM" ]; then default=$((i + 1)); fi
done
$LIST && exit 0
if [ -z "$default" ]; then
    echo; echo "None of them is Java $MINIMUM or newer. Install a JDK $MINIMUM+ and run this again."; exit 1
fi

while true; do
    read -r -p "Use which JDK? [1-${#ROWS[@]}, Enter = $default, q = quit] " answer || exit 1
    answer="${answer:-$default}"
    [ "$answer" = "q" ] && exit 1
    if [[ "$answer" =~ ^[0-9]+$ ]] && [ "$answer" -ge 1 ] && [ "$answer" -le ${#ROWS[@]} ]; then
        IFS='|' read -r major version home <<< "${ROWS[$((answer - 1))]}"
        if [ "$major" -lt "$MINIMUM" ]; then echo "Java $version is too old; choose a JDK $MINIMUM or newer."; continue; fi
        save "$home"
        exit 0
    fi
    echo "Please enter a number between 1 and ${#ROWS[@]}."
done
