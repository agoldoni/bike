#!/usr/bin/env bash
# Demo end-to-end su emulatore: installa l'app, avvia un giro e simula un percorso
# GPS con un profilo di velocità a fasi, per vedere all'opera velocità, distanza,
# tracciato e stima delle calorie senza uscire di casa.
#
# Uso:
#   ./demo-ride.sh                    # AVD di default, profilo di default
#   ./demo-ride.sh Redmi_Note_13_Pro_5G
#   PROFILO="30:15 30:25 20:0" ./demo-ride.sh
#
# Gli screenshot finiscono in /tmp/bike-demo/.
set -euo pipefail

# awk sotto locale italiana stamperebbe i decimali con la virgola, che poi né awk né
# printf riaccettano in ingresso: tutta la matematica di questo script va in C.
export LC_ALL=C

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
PKG="it.agoldoni.bike.debug"
OUT="${OUT:-/tmp/bike-demo}"

# L'app usa il fused provider di Google Play services: su un'immagine AOSP senza GMS
# non arriva nessun fix e la demo resta ferma su "attendo il segnale GPS".
AVD="${1:-Emulator_x86_64}"

# Partenza: Milano, Porta Venezia. Il percorso simulato va verso nord.
LAT0=45.4642
LON0=9.1900

# Fasi del giro, "durata_secondi:velocità_kmh". La sosta finale a 0 km/h serve a
# mostrare che il cronometro avanza mentre le calorie restano ferme.
PROFILO="${PROFILO:-60:20 60:28 30:0}"

# Intervallo fra due fix. Sotto i ~2 s l'emulatore consegna i punti a scatti e la
# velocità istantanea diventa illeggibile (vedi nota in fondo).
STEP=2

mkdir -p "$OUT"

shot() {
    "$ADB" exec-out screencap -p > "$OUT/$1.png"
    echo "[shot] $OUT/$1.png"
}

# --- Emulatore ---------------------------------------------------------------
if ! "$ADB" devices | grep -q "emulator-"; then
    echo "[INFO] Avvio AVD $AVD..."
    "$EMULATOR" -avd "$AVD" -no-snapshot-load -no-audio -no-boot-anim >/dev/null 2>&1 &
    "$ADB" wait-for-device
    until [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
        sleep 3
    done
    echo "[INFO] Emulatore pronto."
else
    echo "[INFO] Emulatore già in esecuzione."
fi

# --- App ---------------------------------------------------------------------
[ -f "$APK" ] || "$PROJECT_DIR/build.sh" debug
"$ADB" install -r "$APK"

# Concessi da riga di comando: il dialog dei permessi bloccherebbe la demo.
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION POST_NOTIFICATIONS; do
    "$ADB" shell pm grant "$PKG" "android.permission.$p" || true
done
"$ADB" shell settings put secure location_mode 3

# Un primo fix prima dell'avvio: così la mappa parte già inquadrata.
"$ADB" emu geo fix "$LON0" "$LAT0"
"$ADB" shell am start -n "$PKG/it.agoldoni.bike.MainActivity"
sleep 5

# --- Coordinate dei tap ------------------------------------------------------
# In frazioni di schermo, non in pixel: l'AVD di default è 1080x2400 ma gli altri no.
read -r W H < <("$ADB" shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/' | tr -d '\r')
TAP_X=$((W / 2))
TAP_PANEL=$((H * 82 / 100))   # zona metriche: da compatto espande, da espanso apre i pesi
TAP_BUTTON=$((H * 90 / 100))  # START/STOP, alla stessa altezza in entrambi gli stati

# I pesi restano nelle SharedPreferences e sopravvivono a `install -r`: si impostano
# una volta sola, a mano, toccando la riga "kcal · NN kg" del pannello espanso.
echo "[INFO] Pesi attuali:"
"$ADB" shell run-as "$PKG" cat "/data/data/$PKG/shared_prefs/bike.xml" 2>/dev/null |
    grep -E "rider_kg|bike_kg" || echo "       (mai impostati: si usano i default 75 + 12 kg)"

"$ADB" shell input tap "$TAP_X" "$TAP_PANEL"   # espande, per vedere la riga calorie
sleep 1
shot 00-pronto

# --- Giro --------------------------------------------------------------------
"$ADB" shell input tap "$TAP_X" "$TAP_BUTTON"  # START
echo "[INFO] Giro avviato. Profilo: $PROFILO"

TOTALE=$(awk -v p="$PROFILO" 'BEGIN{n=split(p,a," "); for(i=1;i<=n;i++){split(a[i],f,":"); s+=f[1]} print s}')
INIZIO=$(date +%s%3N)

while :; do
    T=$(awk -v n="$(date +%s%3N)" -v s="$INIZIO" 'BEGIN{printf "%.3f", (n-s)/1000}')
    [ "$(awk -v t="$T" -v e="$TOTALE" 'BEGIN{print (t>=e)?1:0}')" = "1" ] && break

    # La posizione si ricalcola dal tempo davvero trascorso, non accumulando passi:
    # così l'overhead di ogni `adb emu` non falsa la velocità risultante.
    LAT=$(awk -v t="$T" -v lat0="$LAT0" -v p="$PROFILO" 'BEGIN{
        n = split(p, a, " ")
        rem = t; d = 0
        for (i = 1; i <= n; i++) {
            split(a[i], f, ":")
            dur = f[1] + 0; v = f[2] / 3.6
            seg = (rem > dur) ? dur : ((rem > 0) ? rem : 0)
            d += v * seg; rem -= seg
        }
        printf "%.7f", lat0 + d / 111132   # metri -> gradi di latitudine
    }')

    "$ADB" emu geo fix "$LON0" "$LAT" >/dev/null 2>&1
    printf "\rt=%6.1fs / %ss" "$T" "$TOTALE"
    sleep "$STEP"
done
echo

shot 01-fine-giro
"$ADB" shell input tap "$TAP_X" "$TAP_BUTTON"  # STOP
sleep 2
shot 02-dopo-stop

echo "[OK] Demo finita. Screenshot in $OUT/"
echo
echo "NOTA sulla velocità istantanea: l'emulatore consegna i fix a scatti, quindi il"
echo "     tachimetro mostra picchi (40+ km/h su un profilo da 20). La media km/h e la"
echo "     distanza invece tornano. Le calorie seguono la velocità istantanea, quindi"
echo "     sull'emulatore risultano più alte di quanto il profilo simulato giustifichi."
