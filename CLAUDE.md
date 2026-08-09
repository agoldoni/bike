# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Il progetto

Bike è un ciclocomputer Android nativo (Kotlin + Jetpack Compose): traccia il giro via
GPS, disegna il percorso su una mappa OpenStreetMap e stima le calorie bruciate.
Modulo Gradle singolo (`:app`), nessun backend.

**Tutto il progetto è in italiano**: commenti, KDoc, stringhe UI, nomi dei test
(`fun \`onStart azzera il giro precedente\`()`), messaggi di commit e documenti in
`docs/`. Le nuove aggiunte devono seguire la stessa lingua.

I commenti spiegano il **perché**, non il cosa: quasi ogni costante e ogni scelta non
ovvia ha accanto la ragione per cui esiste (di solito un problema reale incontrato sul
campo). Mantenere questa densità e questo taglio quando si modifica il codice.

## Comandi

La JDK di sistema può non essere compatibile col daemon Gradle: la JDK del progetto è
fissata in `.sdkmanrc` (21.0.6-tem). **`build.sh` la seleziona da solo via SDKMAN**,
quindi va preferito a `./gradlew` diretto. Per invocare Gradle a mano, prima `sdk env`.

```bash
./build.sh                 # build debug -> app/build/outputs/apk/debug/app-debug.apk
./build.sh release         # build release firmata (vedi variabili d'ambiente sotto)
./build.sh clean

./install-all.sh           # installa l'APK debug su tutti i dispositivi connessi
./install-all.sh --build   # compila e poi installa

./emulator.sh [nome_avd]   # avvia AVD, compila, installa e lancia l'app
./demo-ride.sh [nome_avd]  # demo end-to-end: simula un giro con fix GPS finti
```

Test (unit test JVM, JUnit 4):

```bash
sdk env && ./gradlew testDebugUnitTest
sdk env && ./gradlew testDebugUnitTest --tests "it.agoldoni.bike.CalorieModelTest"
sdk env && ./gradlew testDebugUnitTest --tests "*.CalorieModelTest.il MET sale a scatti con la velocita"
```

Il lint della release è severo (`./gradlew lintRelease` gira dentro `assembleRelease`) e
ha già bloccato una build per una dipendenza transitiva disallineata: verificare sempre
la release, non solo la debug, prima di dichiarare finito un cambio di dipendenze.

### Build release

Firma da variabili d'ambiente, mai da file versionati:

```bash
export KEYSTORE_FILE=~/.android/release-key.jks   # default se non impostata
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=release                          # default
export KEY_PASSWORD=...                           # default: KEYSTORE_PASSWORD
```

## Architettura

Il flusso dei dati è a senso unico: **`TrackingService` produce → `RideTracker` conserva
→ la UI Compose osserva**. Non c'è ViewModel.

- **[RideTracker.kt](app/src/main/java/it/agoldoni/bike/RideTracker.kt)** — `object`
  singleton di processo, unico stato condiviso dell'app. Sopravvive alla ricreazione
  dell'Activity, quindi non serve salvare/ripristinare lo stato del giro.
  Espone **tre flow separati apposta**, perché cambiano con ritmi diversi:
  `state` (cronometro, ogni secondo), `position` (ogni fix), `track` (un vertice ogni
  10 m). Unirli farebbe ridisegnare la mappa a ogni tick del cronometro.
  I metodi `on*` sono l'unico punto di mutazione dello stato: tutta la logica del giro
  testabile vive qui e in `CalorieModel`.

- **[TrackingService.kt](app/src/main/java/it/agoldoni/bike/TrackingService.kt)** —
  foreground service (`foregroundServiceType="location"`) comandato via intent action
  `ACTION_START` / `ACTION_STOP`. Campiona il fused provider a ~1 Hz e fa girare un
  ticker che, ogni secondo, avanza cronometro e calorie insieme.
  Distingue due canali: **`onPosition` accetta ogni fix** (serve a inquadrare la mappa e
  a mostrare che il segnale c'è), mentre **distanza e velocità passano dai filtri**
  `MAX_ACCURACY_M` (35 m) e `MIN_STEP_M` (2 m) — senza, il jitter GPS da fermi gonfia il
  contachilometri. La velocità è `max(velocità del provider, spazio/tempo)`: alcune
  sorgenti, fra cui l'emulatore, riportano sempre `speed = 0`.

- **[CalorieModel.kt](app/src/main/java/it/agoldoni/bike/CalorieModel.kt)** — stima MET
  (tabella velocità → MET), oggetto puro senza dipendenze Android. Le calorie si
  **integrano secondo per secondo** e non si ricavano a fine giro dalla media: una sosta
  lunga abbasserebbe la media e con essa il MET di tutto il percorso.

- **[RideMap.kt](app/src/main/java/it/agoldoni/bike/RideMap.kt)** — **unico file che
  conosce osmdroid**. Il resto del codice parla del `GeoPoint` di dominio definito in
  `RideTracker.kt`, così la libreria di mappa resta sostituibile. Wrappa una `MapView`
  in `AndroidView`, ne gestisce a mano il ciclo di vita (`onResume`/`onPause`/`onDetach`)
  e accoda i nuovi vertici con `addPoint` invece di ricostruire la polilinea.

- **[MainActivity.kt](app/src/main/java/it/agoldoni/bike/MainActivity.kt)** — permessi,
  controllo che la geolocalizzazione di sistema sia attiva, `FLAG_KEEP_SCREEN_ON` durante
  il giro, e il composable `RideScreen` che tiene lo stato solo-UI (pannello espanso,
  inseguimento della posizione, dialog dei pesi).

- **[RidePanel.kt](app/src/main/java/it/agoldoni/bike/RidePanel.kt)** — pannello dati in
  basso, due stati (compatto 34% / espanso 50%) con caratteri che crescono insieme
  all'altezza tramite un fattore `growth` derivato dall'animazione.

- **Persistenza**: `SharedPreferences` con nome file `"bike"`, condiviso da
  `RiderProfileStore` (pesi) e `LastKnownPosition` (ultimo punto, per non aprire la mappa
  in mezzo al Golfo di Guinea). I `Double` si salvano come bit pattern in `Long`.

## Convenzioni Android del progetto

- `debug` ha `applicationIdSuffix = ".debug"` → il package installato è
  `it.agoldoni.bike.debug`; gli `adb shell am start` / `pm grant` vanno su quello.
- Icona launcher con background per buildType: blu `#FF1565C0` in
  `app/src/debug/res/values/`, verde `#FF388E3C` in `app/src/release/res/values/`.
- Versioni delle dipendenze centralizzate in
  [gradle/libs.versions.toml](gradle/libs.versions.toml), mai inline nel `build.gradle.kts`.
- `minSdk 26`, `compileSdk`/`targetSdk 36`, `jvmTarget 17`.

## Sviluppo su emulatore

`./demo-ride.sh` installa l'app, concede i permessi da riga di comando, avvia un giro e
simula un percorso con `adb emu geo fix`; gli screenshot finiscono in `/tmp/bike-demo/`.
Due limiti noti dell'emulatore, da tenere presenti prima di dare la caccia a un bug che
non esiste:

- L'app usa il **fused provider di Google Play services**: su un'immagine AOSP senza GMS
  non arriva nessun fix e la schermata resta su «attendo il segnale GPS».
- I fix arrivano a scatti, quindi la **velocità istantanea è gonfiata** (e con lei le
  kcal, che la seguono). Distanza e velocità media invece tornano.

## Documenti di feature

Le feature pianificate vivono in `docs/features/NNN-nome/` con i tre documenti prodotti
dalla skill `claude-code-feature`: `phase-1-requirements.md`, `phase-2-analysis.md`,
`phase-3-implementation-plan.md`. Usare la stessa numerazione e struttura per le nuove.
