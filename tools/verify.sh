#!/usr/bin/env bash
#
# Compile the app + unit tests to real bytecode and run the JVM test suite,
# with no Gradle and no device.
#
# WHY THIS EXISTS
# ---------------
# `which javac` reports nothing on the agent sandbox, and believing it costs a
# full round of changes written blind — that happened on 2026-08-14. A JDK, an
# android.jar and kotlin-compiler-embeddable (plus both compiler plugins) are all
# reachable; this wires them together.
#
# It does NOT replace CI: no AGP, KSP, lint, resource processing, manifest merge
# or R8, and Robolectric tests do not run here. What it buys is turning "written
# by eye" into "compiles and passes" — the difference between one CI round and
# three.
#
# IT DISCOVERS TESTS RATHER THAN TAKING A LIST, and that is not a detail. The
# hand-typed list this replaces ran 85 tests; discovery runs 98. Thirteen classes
# were silently absent from every local verification that night — a check that
# looked like it was checking and quietly was not.
#
# USAGE
#   bash tools/verify.sh          # compile main + tests, run the JVM suite
#   bash tools/verify.sh main     # compile main only (fast inner loop)
#
# REQUIRES (override by env if they live elsewhere):
#   JAVA         a JDK 21 `java` (a JRE is not enough — this drives the Kotlin compiler)
#   ANDROID_JAR  platforms/android-<n>/android.jar matching compileSdk
#   GC           the Gradle cache holding the app's resolved dependencies
set -uo pipefail

REPO="${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
GC="${GC:-$HOME/.gradle/caches}"
[ -d "$GC" ] || GC="/var/www/vhosts/kalfa.me/.gradle/caches"
G="$GC/modules-2/files-2.1"
WORK="${WORK:-${TMPDIR:-/tmp}/kalfa-verify}"
OUT="$WORK/out"

die() { echo "FATAL: $*" >&2; exit 2; }
pick() { find "$1" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | head -1; }

JAVA="${JAVA:-$(command -v java || true)}"
[ -n "$JAVA" ] && [ -x "$JAVA" ] || die "no java; set JAVA=/path/to/jdk21/bin/java"
"$JAVA" -version 2>&1 | grep -q 'version "21' || echo "WARN: expected JDK 21"

ANDROID_JAR="${ANDROID_JAR:-}"
if [ -z "$ANDROID_JAR" ]; then
  SDK=$(sed -n 's/^sdk.dir=//p' "$REPO/local.properties" 2>/dev/null | head -1)
  ANDROID_JAR=$(find "${SDK:-/nonexistent}/platforms" -name android.jar 2>/dev/null | sort -r | head -1)
fi
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || die "no android.jar; set ANDROID_JAR=..."

KC=$(pick "$G/org.jetbrains.kotlin/kotlin-compiler-embeddable")
[ -n "$KC" ] || die "kotlin-compiler-embeddable not in $G — run a Gradle build once to populate the cache"
for m in kotlin-stdlib kotlin-reflect kotlin-script-runtime kotlin-daemon-embeddable; do
  KC="$KC:$(pick "$G/org.jetbrains.kotlin/$m")"
done
KC="$KC:$(pick "$G/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm"):$(pick "$G/org.jetbrains/annotations")"

SER=$(pick "$G/org.jetbrains.kotlin/kotlin-serialization-compiler-plugin-embeddable")
CMP=$(pick "$G/org.jetbrains.kotlin/kotlin-compose-compiler-plugin-embeddable")
[ -n "$SER" ] && [ -n "$CMP" ] || die "serialization/compose compiler plugins not in the Gradle cache"

# ── TRAP 1 · VERSION SHADOWING ────────────────────────────────────────────────
# The cache holds FIVE kotlinx-coroutines-core versions and TWO
# kotlinx-serialization-core. Letting an old one win makes kotlinx-coroutines-test
# blow up inside TestScopeKt.withDelaySkipping — 21 PASSING tests reported as
# failures — and makes every @Serializable emit a bogus "your current
# kotlinx.serialization core version is ..." error. Pin the real ones FIRST so
# they shadow the rest. Versions come from gradle/libs.versions.toml.
# Resolve to the catalog's version when it is cached, otherwise the NEWEST cached
# one — never "whatever find happens to return first". The catalog can legitimately
# pin a version this machine has never downloaded (the catalog pinned coroutines
# 1.11.0 while only 1.10.2 was cached), and a pin that silently matches nothing is
# indistinguishable from no pin at all: an older jar shadows, and you get 25
# passing tests reported as failures. Ask for it, fall back loudly, never guess.
pin() { # <group/artifact> <preferred-version>
  local ga="$1" want="$2" j
  j=$(find "$G/$ga/$want" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | head -1)
  if [ -z "$j" ]; then
    local got
    got=$(ls -1 "$G/$ga" 2>/dev/null | sort -V | tail -1)
    [ -n "$got" ] || return 0
    [ "$got" != "$want" ] && echo "WARN: $ga $want not cached; using $got" >&2
    j=$(find "$G/$ga/$got" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | head -1)
  fi
  [ -n "$j" ] && printf '%s' "$j"
}
co_v=$(sed -n 's/^kotlinxCoroutinesCore *= *"\(.*\)"/\1/p' "$REPO/gradle/libs.versions.toml")
sj_v=$(sed -n 's/^kotlinxSerializationJson *= *"\(.*\)"/\1/p' "$REPO/gradle/libs.versions.toml")
HEAD_CP=""
for spec in "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm|$co_v" \
            "org.jetbrains.kotlinx/kotlinx-serialization-core-jvm|$sj_v" \
            "org.jetbrains.kotlinx/kotlinx-serialization-json-jvm|$sj_v"; do
  j=$(pin "${spec%|*}" "${spec#*|}"); [ -n "$j" ] && HEAD_CP="$HEAD_CP$j:"
done
# The test runtime must match the coroutines-core actually pinned above, or
# kotlinx-coroutines-test fails inside TestScopeKt.withDelaySkipping.
CO_JAR=$(pin "org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" "$co_v")
CO_ACTUAL=$(basename "$(dirname "$(dirname "$CO_JAR")")" 2>/dev/null)

# ── TRAP 2 · core-telecom IS NOT IN THE GRADLE CACHE AT ALL ───────────────────
# androidx.core:core-telecom ships only as an AAR, and unlike every other
# dependency its classes are absent from modules-2 AND from the transforms dir —
# there is nothing on disk to point a classpath at. Without unpacking the AAR,
# TelecomRegistration.kt fails with a bare "unresolved reference 'telecom'" that
# looks like a code error and is not. Fetched once and cached in $WORK.
CT="$WORK/core-telecom/classes.jar"
if [ ! -f "$CT" ]; then
  AAR="${CORE_TELECOM_AAR:-$(find "$GC" -name 'core-telecom-*.aar' 2>/dev/null | head -1)}"
  if [ -z "$AAR" ]; then
    ct_v=$(sed -n 's/^coreTelecom *= *"\(.*\)"/\1/p' "$REPO/gradle/libs.versions.toml")
    AAR="$WORK/core-telecom-$ct_v.aar"
    if [ ! -f "$AAR" ]; then
      mkdir -p "$WORK"
      echo "==> fetching androidx.core:core-telecom:$ct_v (absent from the Gradle cache)"
      curl -fsS -o "$AAR" \
        "https://dl.google.com/dl/android/maven2/androidx/core/core-telecom/$ct_v/core-telecom-$ct_v.aar" \
        || die "could not fetch core-telecom; set CORE_TELECOM_AAR=/path/to/core-telecom.aar"
    fi
  fi
  mkdir -p "$(dirname "$CT")" && (cd "$(dirname "$CT")" && unzip -oq "$AAR")
fi
[ -f "$CT" ] || die "core-telecom classes.jar missing after unpack"
HEAD_CP="$HEAD_CP$CT:"

REST=$(find "$GC" -path '*transforms*' -name '*.jar' 2>/dev/null | grep -v 'lint.jar' | tr '\n' ':')
REST="$REST$(find "$G" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | grep -v -- '-javadoc' | tr '\n' ':')"
CP="$HEAD_CP$REST$ANDROID_JAR"

# BuildConfig is the ONLY AGP-generated symbol main needs (nothing references R).
mkdir -p "$WORK/stub" "$OUT"
cat > "$WORK/stub/BuildConfig.kt" <<'EOF'
package me.kalfa.agentconsole
object BuildConfig {
    const val DEBUG: Boolean = true
    const val APPLICATION_ID: String = "me.kalfa.agentconsole"
    const val SUPABASE_URL: String = "https://placeholder.supabase.co"
    const val SUPABASE_ANON_KEY: String = "placeholder"
}
EOF

# ── TRAP 3 · ARGUMENT LIST TOO LONG ───────────────────────────────────────────
# ~990 jars blows the exec limit. Everything goes through @argfiles — for the
# compiler AND the JUnit run.
compile() { # <outdir> <srclist> <extra-cp> <argfile> [friend-path]
  local out="$1" srcs="$2" extra="$3" af="$4" friend="${5:-}"
  rm -rf "$out"; mkdir -p "$out"
  { echo "-classpath \"$extra$CP\""; echo "-d \"$out\""; echo "-nowarn"; echo "-jvm-target 11"
    [ -n "$friend" ] && echo "-Xfriend-paths=\"$friend\""
    echo "-Xplugin=\"$SER\""; echo "-Xplugin=\"$CMP\""
    sed 's/^/"/;s/$/"/' "$srcs"; } > "$af"
  "$JAVA" -Xmx3g -cp "$KC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$af" 2>&1 \
    | grep -v 'unable to find' | grep 'error:' | head -25
  return "${PIPESTATUS[0]}"
}

find "$REPO/app/src/main/java" -name '*.kt' > "$WORK/main.srcs"
echo "$WORK/stub/BuildConfig.kt" >> "$WORK/main.srcs"
echo "==> compiling main ($(wc -l < "$WORK/main.srcs") files)"
compile "$OUT/main" "$WORK/main.srcs" "" "$WORK/main.args" || die "main did not compile"
echo "    OK — $(find "$OUT/main" -name '*.class' | wc -l) classes"
[ "${1:-}" = "main" ] && exit 0

# AppIdentityTest needs the AGP-generated R class; excluded here, CI covers it.
find "$REPO/app/src/test/java" -name '*.kt' | grep -v 'AppIdentityTest.kt' > "$WORK/test.srcs"
TL="$(pick "$G/junit/junit"):$(pick "$G/org.hamcrest/hamcrest-core"):$(pin "org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm" "${CO_ACTUAL:-$co_v}")"
echo "==> compiling tests ($(wc -l < "$WORK/test.srcs") files)"
# ── TRAP 4 · -Xfriend-paths ───────────────────────────────────────────────────
# Without it every `internal` member looks inaccessible from test code — a wall of
# "it is internal in file" errors that are NOT real. AGP passes this for unit
# tests, which is why e.g. PresenceShiftWatcherTest reaching an internal function
# is legal in a normal build.
compile "$OUT/test" "$WORK/test.srcs" "$OUT/main:$TL:" "$WORK/test.args" "$OUT/main" \
  || die "tests did not compile"
echo "    OK"

echo "==> running the non-Robolectric suite"
RUNNABLE=""
while read -r c; do
  f="$REPO/app/src/test/java/$(echo "$c" | tr '.' '/').kt"
  grep -qs 'Robolectric\|@Config' "$f" || RUNNABLE="$RUNNABLE $c"
done < <(cd "$OUT/test" && find . -name '*Test.class' ! -name '*$*' \
         | sed 's|^\./||;s|\.class$||;s|/|.|g' | sort)
printf -- "-cp\n%s\n" "$OUT/main:$OUT/test:$TL:$CP" > "$WORK/run.args"
# shellcheck disable=SC2086
"$JAVA" "@$WORK/run.args" org.junit.runner.JUnitCore $RUNNABLE 2>&1 | tail -6
