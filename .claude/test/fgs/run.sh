#!/bin/bash
# Verificación sin Gradle ni dispositivo del arreglo fgs-grace (doc: KUACKMEDIA.md).
#  1) Compila src/android contra android.jar + androidx (caché de Gradle) + CordovaLib de un app con plataforma
#     android (CORDOVA_JAR=… para otra ruta). Si falta algo falla (exit 2): un exit 0 tiene que probar la
#     compilación. ALLOW_SKIP=1 la saltea a sabiendas y sigue con el paso 2.
#  2) Corre el harness de la máquina de estados: MusicControlsServiceConnection.java y KillBinder.java reales contra
#     stubs mínimos de Android/Cordova con reloj virtual y un doble del servicio (harness/…/Killer).
set -eo pipefail
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
HERE=$(cd "$(dirname "$0")" && pwd)
TMP=${TMPDIR:-/tmp}/mc-fgs-check
rm -rf "$TMP" && mkdir -p "$TMP/libs" "$TMP/out" "$TMP/hout"

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
# `|| true`: con pipefail, un ls o un grep sin resultados cortaría el script antes de avisar qué falta.
ANDROID_JAR=$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)
G=$HOME/.gradle/caches/modules-2/files-2.1
CORDOVA_JAR=${CORDOVA_JAR:-$(ls "$ROOT"/../apps/app_*/project/platforms/android/CordovaLib/build/intermediates/compile_library_classes_jar/*/*/classes.jar 2>/dev/null | head -1 || true)}

CP="$ANDROID_JAR:$CORDOVA_JAR"
MISSING=""
for dep in androidx.media/media/1.7.0 androidx.core/core/1.13.1 androidx.appcompat/appcompat/1.7.0 \
    androidx.fragment/fragment/1.8.6 androidx.activity/activity/1.8.0 androidx.lifecycle/lifecycle-common/2.6.2 \
    androidx.lifecycle/lifecycle-runtime/2.6.2 androidx.lifecycle/lifecycle-viewmodel/2.6.2 \
    androidx.savedstate/savedstate/1.2.1 androidx.annotation/annotation-jvm/1.8.1; do
    f=$(find "$G/$dep" \( -name "*.aar" -o -name "*.jar" \) 2>/dev/null | grep -v sources | head -1 || true)
    if [ -z "$f" ]; then MISSING="$MISSING $dep"; continue; fi
    d="$TMP/libs/$(echo "$dep" | tr '/' '_')"; mkdir -p "$d"
    case "$f" in *.aar) (cd "$d" && unzip -o -q "$f" classes.jar) ;; *) cp "$f" "$d/classes.jar" ;; esac
    CP="$CP:$d/classes.jar"
done

if [ -z "$ANDROID_JAR" ] || [ -z "$CORDOVA_JAR" ] || [ -n "$MISSING" ]; then
    echo "1) compilación: SALTEADA (android.jar='$ANDROID_JAR' CordovaLib='$CORDOVA_JAR' faltan:$MISSING)"
    if [ "${ALLOW_SKIP:-0}" != "1" ]; then
        echo "   sin compilar no hay verificación: falla. ALLOW_SKIP=1 para seguir sólo con el harness." >&2
        exit 2
    fi
else
    javac -source 11 -target 11 -nowarn -encoding UTF-8 -cp "$CP" -d "$TMP/out" "$ROOT"/src/android/*.java
    echo "1) compilación de src/android: OK ($(find "$TMP/out" -name '*.class' | wc -l | tr -d ' ') clases, $(basename "$(dirname "$ANDROID_JAR")"))"
fi

javac -source 11 -target 11 -nowarn -encoding UTF-8 -d "$TMP/hout" $(find "$HERE/stubs" "$HERE/harness" -name "*.java") \
    "$ROOT/src/android/MusicControlsServiceConnection.java" "$ROOT/src/android/KillBinder.java"
echo "2) harness de la máquina de estados:"
java -cp "$TMP/hout" com.homerours.musiccontrols.Harness | grep -v "^   I/"
